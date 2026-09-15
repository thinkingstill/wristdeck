package io.wristdeck.gesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.net.Protocol
import io.wristdeck.util.Feedback

/**
 * 正式路径的手势入口：把 IMU 采样接成**真的会发按键**的那条链路。
 *
 * 与 [GestureProbe] 的分工是刻意分开的：
 * - [GestureProbe] 是标定工具，跟着调试页走，只测量、只进日志，**不发指令**；
 * - 本类跟着 [io.wristdeck.svc.BridgeService] 走，是产品行为。
 * 两条链路共用同一份识别算法（[WristGestureDetector]），所以标定出来的阈值直接生效。
 *
 * ## 为什么用 object 而不是由 Service 持有实例
 * 传感器在整机上是**独占资源**：同一时刻只应该有一条自己人开的采样流。
 * 做成进程单例，重复 start() 天然被 [running] 挡住，不必依赖调用方保证不重复启动。
 *
 * ## 省电：跟着屏幕走，而不是常开
 * 识别只在"屏幕亮后 [WristGestureDetector.ARM_HOLD_MS]"的滑动窗口内生效
 * （[WristGestureDetector.arm] 的语义），所以屏幕灭着的时候采样纯属浪费。
 * 屏幕灭后**不立刻**注销，留 [OFF_GRACE_MS] 宽限：翻表盘往往就发生在屏幕刚灭那一刻
 * （抬手看表 → 屏幕亮 → 手腕翻过去），立刻停采样会把最后那一下吃掉。
 *
 * ## 指令怎么发
 * - 四向：原样转发 `up/down/left/right`（与 [Protocol] 里的字面量一致，但本类刻意
 *   不 import 那些常量，改为从识别器取 —— 识别器的常量是与协议的口头契约）。
 * - **翻表盘是相对动作**（`toggle`）：超时重试会把状态翻回去，所以先按
 *   [BridgeClient.playing] 解析成幂等的 `play` / `pause`，与主页按钮同一个策略。
 *   本地状态未知时才退化成 `toggle`。
 * - 连接未就绪时给"离线"震动，**不让动作静默消失** —— 用户做一次手势是有成本的，
 *   什么都没发生比给个失败反馈更糟。
 */
object GestureController {

    private const val TAG = "WristDeck"
    private const val G = "GEST "

    /** 50Hz。手腕姿态识别够用，不是最高速率，避免无谓功耗。 */
    private const val ACCEL_PERIOD_US = 20_000

    /** 屏幕亮时每秒续一次活跃窗口就够（窗口本身有 5s）。 */
    private const val ARM_RENEW_STEP_MS = 1_000L

    /** 屏幕灭后继续采样的宽限期，见类注释。 */
    private const val OFF_GRACE_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var app: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var screenOn = false

    private var registered = false
    private var lastArmRenew = 0L
    private var sent = 0
    private var dropped = 0

    private var sensorManager: SensorManager? = null
    private var powerManager: PowerManager? = null
    private var accel: Sensor? = null

    private val detector = WristGestureDetector()

    /**
     * 屏幕灭且宽限期已过 → 注销采样。
     *
     * 必须用定时任务而不是"顺手下一次采样时检查"：注销之后就再也没有采样事件了，
     * 靠采样驱动就等于永远不会触发。
     */
    private val releaseWhenIdle = Runnable {
        if (!screenOn) release()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val now = System.currentTimeMillis()
            // 屏幕亮 → 持续续期活跃窗口；屏幕灭 → 不再续期，窗口 5s 后自然过期。
            if (screenOn && now - lastArmRenew >= ARM_RENEW_STEP_MS) {
                lastArmRenew = now
                detector.arm(now)
            }
            detector.onAccel(e.values[0], e.values[1], e.values[2], now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun isRunning(): Boolean = running

    fun isScreenOn(): Boolean = screenOn

    /** 启用手势。重复调用无副作用。 */
    fun start(ctx: Context) {
        if (running) return
        val c = ctx.applicationContext
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.w(TAG, "${G}无 SensorManager，手势不启用")
            return
        }
        val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (a == null) {
            Log.w(TAG, "${G}无加速度计，手势不启用")
            return
        }
        app = c
        sensorManager = sm
        powerManager = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        accel = a

        // 正式路径必须走活跃窗口这道闸门。调试页会把 bypassArm 置 true —— 那是标定专用，
        // 这里显式写回 false，免得被上一次调试会话的残留值带着走（单例，状态是跨界面共享的）。
        detector.bypassArm = false
        detector.onEvent = { e -> deliver(e) }

        c.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        running = true
        screenOn = powerManager?.isInteractive == true
        lastArmRenew = 0L
        if (screenOn) acquire() else main.postDelayed(releaseWhenIdle, OFF_GRACE_MS)

        Log.i(
            TAG,
            "${G}手势启用 屏幕亮=$screenOn 采样=${if (registered) "已注册" else "待屏幕亮"} " +
                "窗口=${WristGestureDetector.ARM_HOLD_MS}ms 宽限=${OFF_GRACE_MS}ms",
        )
    }

    /** 停用手势并释放传感器。 */
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(releaseWhenIdle)
        release()
        app?.let { runCatching { it.unregisterReceiver(screenReceiver) } }
        detector.onEvent = null
        Log.i(TAG, "${G}手势停用 累计发=$sent 丢弃=$dropped")
    }

    private fun onScreenOn() {
        if (!running) return
        screenOn = true
        lastArmRenew = 0L
        acquire()
    }

    private fun onScreenOff() {
        if (!running) return
        screenOn = false
        main.removeCallbacks(releaseWhenIdle)
        main.postDelayed(releaseWhenIdle, OFF_GRACE_MS)
    }

    private fun acquire() {
        if (registered) return
        val sm = sensorManager ?: return
        val a = accel ?: return
        main.removeCallbacks(releaseWhenIdle)
        registered = sm.registerListener(listener, a, ACCEL_PERIOD_US)
        Log.i(TAG, "${G}采样开始 registered=$registered")
    }

    private fun release() {
        if (registered) {
            sensorManager?.unregisterListener(listener)
            registered = false
        }
        // 无论有没有注册过都要 disarm：否则窗口可能在停止期间"过期"，
        // 下次启用时第一个动作会莫名其妙被放行/拦掉。
        detector.disarm()
        Log.i(TAG, "${G}采样停止 已发=$sent 丢弃=$dropped")
    }

    private fun deliver(e: WristGestureDetector.Event) {
        val c = app ?: return
        if (!running) return

        /*
         * 探针开着的时候只记日志、不发指令。
         *
         * 必要而不是洁癖：标定用的是同一个加速度计，SensorManager 允许同时挂多个监听者，
         * 于是探针的识别器和这里会**各发一次**，一个动作在浏览器上变成两次按键。
         */
        if (GestureProbe.isDetectMode()) {
            Log.i(TAG, "${G}探针模式开启，手势只记录不发送 ${e.action}")
            return
        }

        val client = BridgeHolder.get(c)
        val action = resolve(client, e.action)

        if (client.state != BridgeClient.State.READY) {
            dropped++
            Feedback.offline(c)
            Log.i(TAG, "${G}手势 ${e.action}→$action 丢弃：连接未就绪 state=${client.state}")
            return
        }

        val ok = client.sendAction(action)
        if (ok) {
            sent++
            Feedback.tap(c)
        } else {
            dropped++
            Feedback.fail(c)
        }
        Log.i(
            TAG,
            // θ 与轴系数是判定依据（幅度窗口看 θ、判轴看系数谁大），φ 只留着交叉核对。
            // 排查"某个方向发不出来"时，先看轴系数是不是把轴判反了，再看 θ 有没有掉出 45~130 窗口。
            "${G}手势 ${e.action}→$action ok=$ok θ=${e.thetaDeg} " +
                "轴 臂=${e.coefArm} 屈=${e.coefBend} φarm=${e.phiArm} φbend=${e.phiBend} " +
                "累计发=$sent 丢=$dropped",
        )
    }

    /**
     * 把识别器给出的动作翻译成实际要发的指令。
     *
     * 只有"翻表盘"需要翻译：它是**相对动作**（toggle），超时重试会把状态翻回去，
     * 而 play/pause 是幂等的。真相源用浏览器 ack 回传的 [BridgeClient.playing]，
     * 未知时才退化成 toggle。
     */
    private fun resolve(client: BridgeClient, action: String): String {
        if (action != WristGestureDetector.ACTION_TOGGLE) return action
        return when (client.playing) {
            true -> Protocol.ACTION_PAUSE
            false -> Protocol.ACTION_PLAY
            null -> Protocol.ACTION_TOGGLE
        }
    }
}
