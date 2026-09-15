package io.wristdeck.net

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.wristdeck.model.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BridgeClient(private var config: Config) {

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

    /**
     * 每次 connect() 自增，回调里先比对世代号。
     *
     * 为什么必须有它：被顶替或被 cancel 的旧 socket 仍会回调 onFailure（典型是
     * "sent ping but didn't receive pong"），若不加甄别就又排一次重连，
     * 新旧连接互相顶替会让连接数指数级发散 —— 实测 Bridge 侧一晚上收到 6719 个会话。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var socket: WebSocket? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun updateConfig(cfg: Config) {
        val changed = cfg.host != config.host || cfg.port != config.port || cfg.pin != config.pin
        config = cfg
        // 改了 IP / 端口 / PIN 就立刻重来一轮，别让用户干等 60s 的退避。
        // 尤其是 bad_pin 停在 DENIED 时，这里是唯一的恢复路径。
        if (changed && !stopped) {
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
        socket?.close(1000, "bye")
        socket = null
        playing = null
        postState(State.DISCONNECTED, null)
    }

    fun sendAction(action: String): Boolean {
        val s = socket ?: return false
        if (state != State.READY) return false
        val now = System.currentTimeMillis()
        prunePending(now)
        val id = "w${seq.incrementAndGet()}"
        pending[id] = Pending(action, now)
        return s.send(Protocol.cmd(id, action, now))
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
        if (config.host.isBlank()) {
            postState(State.DISCONNECTED, NetError.describe("no_host"))
            return
        }
        // 同一时刻只允许一个在途连接：撤掉待执行的重连，并废弃上一个 socket。
        // 用 cancel() 而不是 close()：close() 会触发 onClosed 再排一次重连（旧的双重排程 bug）。
        main.removeCallbacks(reconnectTask)
        pendingReconnect = false
        val prev = socket
        socket = null
        prev?.cancel()
        denyReason = null
        generation += 1
        val gen = generation
        postState(State.CONNECTING, null)
        val url = "ws://${config.host}:${config.port}/ws?role=watch"
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener(gen))
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
        socket = null
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
                // 只记下原因并关连接，重连统一交给随后的 onClosed / onFailure 走，
                // 否则 Denied 分支与回调各排一次重连 —— 就是风暴的起点。
                denyReason = msg.reason
                socket?.close(1000, "denied")
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

            Protocol.In.Ping -> socket?.send(Protocol.pong())
            Protocol.In.Unknown -> Unit
        }
    }

    /** 每个连接一份，携带发起时的世代号；过期连接的回调直接丢弃。 */
    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun stale() = gen != generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale()) return
            // 刻意**不**在这里把 attempt 归零：TCP + 握手成功不等于会话可用，
            // 服务端可能立刻回 replaced / bad_pin。在这里清零会让退避永远停在 1s，
            // 变成每秒一次的连接风暴。真正的"连上了"以收到 welcome 为准。
            webSocket.send(Protocol.hello(Build.MODEL, config.pin))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale()) return
            handle(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stale()) return
            // 原始 message 只进日志（含 cleartext 拦截、ECONNREFUSED 等英文片段），
            // 给 UI 的 detail 一律先翻译成人话；认不出的异常 detail 为 null，只显示"未连接"。
            Log.w(TAG, "连接失败: ${t.message}", t)
            val reason = denyReason ?: NetError.describe(t.message)
            denyReason = null
            scheduleReconnect(reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stale()) return
            Log.i(TAG, "连接关闭 code=$code reason=$reason")
            val r = denyReason ?: NetError.describe(reason)
            denyReason = null
            scheduleReconnect(r)
        }
    }

    private companion object {
        const val TAG = "WristDeck"
        const val PENDING_TTL_MS = 30_000L

        /** 这些拒绝原因靠重试无法恢复，必须用户去设置页改 PIN，所以不自动重连。 */
        val PERMANENT_DENY = setOf("bad_pin", "pin_locked")
    }
}
