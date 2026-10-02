package io.wristdeck.net

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.wristdeck.model.Config
import io.wristdeck.model.Link
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 与 PC 的会话客户端。
 *
 * 职责边界（M1 抽出 [Transport] 之后）：这里只管**协议解析 + 状态机 + 重连策略**，
 * 字节怎么出去完全交给 Transport。所以退避表、世代号护栏、pending 超时清理、
 * `playing` 真相源这些踩过血案换来的逻辑，换传输时一条都不用重写。
 *
 * M2 起支持两条链路（[Link.BLE] / [Link.WIFI]）：传输实现在构造时按 `config.link` 选定，
 * 这里除了多一个 [appContext]（BLE 需要它拿 `BluetoothManager`）之外，其余一行没动。
 */
class BridgeClient(
    private val appContext: Context,
    private var config: Config,
) : Transport.Sink {

    private class Pending(val action: String, val at: Long)

    enum class State { DISCONNECTED, CONNECTING, READY, DENIED }

    data class Ack(
        val id: String,
        val action: String,
        val ok: Boolean,
        val playing: Boolean?,
        val reason: String?,
        val e2e: Int,
    )

    interface Callback {
        fun onStateChanged(state: State, detail: String?)
        fun onAck(ack: Ack)

        /** 指令超时后才回来的执行结果：只需按 playing 静默纠正图标，不要重复震动。 */
        fun onCmdLate(ok: Boolean, playing: Boolean?) {}
    }

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    @Volatile
    var execOnline: Boolean = false
        private set

    /**
     * 浏览器最近一次回传的**真实播放状态**（ack / cmd_late 里的 `state.playing`）。
     * null = 未知，会随每次断开连接一起清回 null。
     *
     * 为什么需要它：所有"翻表盘"类动作本质是**相对动作**（toggle），而相对动作
     * 一旦超时重试就会把状态翻回去。主页按钮早就按这个思路改成发幂等的 play/pause 了，
     * 手势这一路必须拿到同一个真相源，否则两处会各自按自己的猜测发指令。
     * 断线时清空是刻意的：重连后 welcome 不带播放状态，留着旧值就是凭猜。
     */
    @Volatile
    var playing: Boolean? = null
        private set

    var callback: Callback? = null

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val backoff = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L)

    /**
     * 传输实现，按 `config.link` 选定。
     *
     * 是 `var` 而不是 `val`：设置页里可以把"蓝牙/Wi-Fi"整个换掉，换链路等于换一条通道，
     * 必须显式停掉旧的（见 [updateConfig]）。`WsTransport(this)` / `BleTransport(ctx, this)`
     * 构造期间都不会回调，所以这里传出 `this` 是安全的。
     */
    private var transport: Transport = newTransport(config.link)

    private fun newTransport(link: Link): Transport = when (link) {
        Link.WIFI -> WsTransport(this)
        Link.BLE -> BleTransport(appContext, this)
    }

    /** 重连任务全局只保留一份：排程前先撤销旧的，杜绝定时器叠加后并发发起连接。 */
    private val reconnectTask = Runnable {
        pendingReconnect = false
        connect()
    }

    private var attempt = 0
    private var stopped = false
    private var pendingReconnect = false

    /** `denied` 给出的原因，交给随后的 onClosed 消费，保证一次拒绝只触发一次重连。 */
    private var denyReason: String? = null

    fun updateConfig(cfg: Config) {
        val changed = cfg.host != config.host || cfg.port != config.port || cfg.pin != config.pin
        val linkChanged = cfg.link != config.link
        if (linkChanged) {
            // 换传输 = 换一整条链路：旧实现必须显式拆掉，否则 BLE 广播和 WS socket
            // 会同时挂在进程里，还各自往上抛状态（"同时开两条就是自己顶掉自己"）。
            transport.stop()
            transport = newTransport(cfg.link)
        }
        config = cfg
        // 改了 IP / 端口 / PIN / 连接方式就立刻重来一轮，别让用户干等 60s 的退避。
        // 尤其是 bad_pin 停在 DENIED 时，这里是唯一的恢复路径。
        if ((changed || linkChanged) && !stopped) {
            main.post {
                attempt = 0
                main.removeCallbacks(reconnectTask)
                pendingReconnect = false
                connect()
            }
        }
    }

    fun start() {
        stopped = false
        attempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        transport.stop()
        playing = null
        postState(State.DISCONNECTED, null)
    }

    fun sendAction(action: String): Boolean {
        if (state != State.READY) return false
        val now = System.currentTimeMillis()
        prunePending(now)
        val id = "w${seq.incrementAndGet()}"
        pending[id] = Pending(action, now)
        return transport.send(Protocol.cmd(id, action, now))
    }

    /** 超时/重连后残留的 id 关联不再有用，过期即清，避免 map 无限增长。 */
    private fun prunePending(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at > PENDING_TTL_MS) it.remove()
        }
    }

    private fun connect() {
        if (stopped) return
        // "目标地址是否有效"只有 Wi-Fi 才需要判：BLE 由对方扫描+连接决定，手表这边没有地址可填。
        // 这个检查刻意留在这里而不是下沉给 WsTransport —— 它要表达的是"别重连了，
        // 等用户去设置页填 IP"，而 Transport 的 onClosed 通道语义是"失败，请按退避重试"，
        // 走那条路会变成每 60s 空转一次。
        if (config.link == Link.WIFI && config.host.isBlank()) {
            postState(State.DISCONNECTED, NetError.describe("no_host"))
            return
        }
        main.removeCallbacks(reconnectTask)
        pendingReconnect = false
        denyReason = null
        postState(State.CONNECTING, null)
        transport.updateTarget(config.host, config.port)
        transport.start()
    }

    /**
     * 唯一的重连入口。
     *
     * 两条护栏：
     *  - pendingReconnect：同一轮只排一次，重复触发（Denied + onClosed / 多个旧 socket）直接被吞掉；
     *  - backoff 上限拉到 60s：即使真掉线，也只是每分钟一次，不再是每秒几百次。
     *
     * bad_pin / pin_locked 不重连 —— 不换 PIN 重试一万次也是同样的结果，
     * 只会把 Bridge 的日志和手表电量一起烧掉。停在 DENIED 等用户去设置页改。
     */
    private fun scheduleReconnect(detail: String?) {
        if (stopped) return
        val permanent = detail != null && PERMANENT_DENY.contains(detail)
        if (!permanent && pendingReconnect) return
        // 连接没了，播放状态也就无从得知了。留着旧值会让下一次翻表盘按过期状态发
        // play/pause（发反了就是"按了没反应"），退化成 toggle 反而更安全。
        playing = null
        postState(if (permanent) State.DENIED else State.DISCONNECTED, detail)
        if (permanent) return
        pendingReconnect = true
        val delay = backoff[attempt.coerceAtMost(backoff.size - 1)]
        attempt += 1
        main.postDelayed(reconnectTask, delay)
    }

    private fun postState(s: State, detail: String?) {
        main.post {
            state = s
            callback?.onStateChanged(s, detail)
        }
    }

    private fun handle(text: String) {
        when (val msg = Protocol.parse(text)) {
            is Protocol.In.Welcome -> {
                execOnline = msg.execOnline
                attempt = 0
                postState(State.READY, if (msg.execOnline) null else "exec_offline")
            }

            is Protocol.In.Denied -> {
                // 只记下原因并关连接，重连统一交给随后的 onClosed 走，
                // 否则 Denied 分支与回调各排一次重连 —— 就是风暴的起点。
                denyReason = msg.reason
                transport.stop()
            }

            is Protocol.In.Ack -> {
                val action = pending.remove(msg.id)?.action.orEmpty()
                // 播放状态只在服务端给了的时候才更新：方向键的 ack 里没有 state，
                // 用 optBoolean 的默认值去覆盖会把已知状态抹成 false。
                msg.playing?.let { playing = it }
                main.post {
                    callback?.onAck(
                        Ack(msg.id, action, msg.ok, msg.playing, msg.reason, msg.e2e)
                    )
                }
            }

            is Protocol.In.Evt -> {
                execOnline = msg.online
                postState(state, if (msg.online) null else "exec_offline")
            }

            is Protocol.In.CmdLate -> {
                msg.playing?.let { playing = it }
                main.post { callback?.onCmdLate(msg.ok, msg.playing) }
            }

            Protocol.In.Ping -> transport.send(Protocol.pong())
            Protocol.In.Unknown -> Unit
        }
    }

    // ---------- Transport.Sink ----------

    override fun onOpen() {
        // 刻意**不**在这里把 attempt 归零：TCP + 握手成功不等于会话可用，
        // 服务端可能立刻回 replaced / bad_pin。在这里清零会让退避永远停在 1s，
        // 变成每秒一次的连接风暴。真正的"连上了"以收到 welcome 为准。
        transport.send(Protocol.hello(Build.MODEL, config.pin))
    }

    override fun onMessage(text: String) {
        handle(text)
    }

    override fun onClosed(rawReason: String?) {
        // 给 UI 的 detail 一律先翻译成人话；认不出的异常 detail 为 null，只显示"未连接"。
        // 服务端下发的 denied.reason 优先（它比异常 message 精确得多）。
        val reason = denyReason ?: NetError.describe(rawReason)
        denyReason = null
        scheduleReconnect(reason)
    }

    private companion object {
        const val PENDING_TTL_MS = 30_000L

        /** 这些拒绝原因靠重试无法恢复，必须用户去设置页改 PIN，所以不自动重连。 */
        val PERMANENT_DENY = setOf("bad_pin", "pin_locked")
    }
}
