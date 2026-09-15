package io.wristdeck.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 阶段 3（IMU 手腕四向识别）的前置探针：只测量、不识别。
 *
 * 要回答三个问题：
 *  1. 息屏 / 挂起时，continuous 传感器（accel / gyro）还能不能收到事件；
 *  2. significant_motion(17) 这种 one-shot wakeUp 传感器能否把 App 叫醒；
 *  3. 唤醒锁"续期"是否有效（现有 BridgeService 是 acquire(30min) 一次性、不续期）。
 *
 * 所有输出统一前缀 `PROBE`，抓取方式：`adb logcat -s WristDeck:I`。
 * 心跳里 `gapMax` 是判定"数据是否断流"的关键指标：屏幕熄灭后若事件停投，
 * 下一次恢复时 gapMax 会跳到几十秒以上。
 */
object GestureProbe {

    private const val TAG = "WristDeck"
    private const val P = "PROBE "
    /** 识别命中用独立前缀：`adb logcat -s WristDeck:I | grep GEST` 就能单独统计命中率。 */
    private const val G = "GEST "

    private const val HEARTBEAT_MS = 10_000L
    private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val WAKE_RENEW_MS = 5 * 60 * 1000L

    /** 相邻两帧间隔超过它就当场打点：这是"数据断流"的现场快照。 */
    private const val GAP_WARN_MS = 2_000L

    /** 20ms = 50Hz，手腕姿态识别够用；不是最高速率，避免无谓功耗。 */
    private const val ACCEL_PERIOD_US = 20_000
    private const val GYRO_PERIOD_US = 20_000

    /** Sensor.TYPE_WRIST_TILT_GESTURE 是 @SystemApi，编译期取不到常量，用字面量。 */
    private const val TYPE_WRIST_TILT = 26

    /** 活跃窗口比识别器默认值短一点：调试时能看到"窗口过期"这件事本身。 */
    private const val ARM_HOLD_MS = 5_000L

    /** 屏幕亮时每秒刷新一次活跃窗口就够了。 */
    private const val ARM_RENEW_STEP_MS = 1_000L

    /* 识别轨迹记录：200ms 一帧（比 50Hz 采样稀，但足够看出一条摆动从起动到停稳的整条曲线）。 */
    private const val DBG_PERIOD_MS = 200L
    /** 完全静止时的保活间隔。 */
    private const val DBG_IDLE_MS = 2_000L
    /** 超过它才算"有动静"，避免静止噪声把日志刷爆。 */
    private const val DBG_MIN_DEG = 8f
    private const val DBG_MIN_GYRO = 60f

    /** 轨迹打印时每行放多少帧（28 帧 ≈ 560ms@50Hz），避开 logcat 单行截断。 */
    private const val TRACE_CHUNK = 28

    /**
     * 轨迹往前多回放多少毫秒。
     * 必须大于识别器建基准所需的静止时长（`restHoldMs`=800ms），否则离线回放时
     * 基准建不起来，所有段都会回放成"无动作"，把阈值调参带沟里去。
     */
    private const val TRACE_LEAD_MS = 1_200L

    private val accelCount = AtomicInteger()
    private val gyroCount = AtomicInteger()
    private val tiltCount = AtomicInteger()
    private val sigCount = AtomicInteger()

    @Volatile private var accelLastTs = 0L
    @Volatile private var accelMaxGapMs = 0L
    @Volatile private var accelX = 0f
    @Volatile private var accelY = 0f
    @Volatile private var accelZ = 0f

    /* 运动指纹：每个心跳窗口内的峰值。
     * 用途一：即使手势传感器不触发，也能证明"用户确实做了动作"，并量化幅度（甩腕 vs 抬手差别很大）。
     * 用途二：为阶段 3 自己的识别状态机提供阈值参考。 */
    @Volatile private var accelPeak = 0f
    @Volatile private var gyroPeak = 0f
    @Volatile private var tiltAt = 0L
    @Volatile private var sigAt = 0L
    @Volatile private var running = false
    @Volatile private var startedAt = 0L
    @Volatile private var registerReport = "-"

    private var sensorManager: SensorManager? = null
    private var powerManager: PowerManager? = null
    private var batteryManager: BatteryManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var sigmotion: Sensor? = null
    private var wristTilt: Sensor? = null

    private val handler = Handler(Looper.getMainLooper())

    /* ---------- 阶段 3 识别调试模式 ----------
     * 探针测的是"能不能拿到数据"，调试模式看的是"拿到的数据能识别出什么"。
     * 二者共用同一份 50Hz 采样流，避免开两路监听互相干扰。
     * 识别器只进日志、只上屏，**不接 bridge**（还没到那一步）。 */
    private var detector: WristGestureDetector? = null
    @Volatile private var detectMode = false
    @Volatile private var armRenewAt = 0L

    /** 上一帧是否已建立基准，用来只在"就绪状态翻转"时打一行日志。 */
    private var baselineReady = false

    /** 上一次打识别轨迹的时间（[dbgTick] 里的节流用）。 */
    private var dbgAt = 0L

    /*
     * 全速率轨迹环形缓冲。
     *
     * 动机：`onSwing` 只能给每条摆动的**峰值**，而"发不发"取决于触发那一瞬的角度。
     * 只有峰值数据时，调阈值等于盲调——曾把一次深压腕的漏发归错因（怪到四向上限头上，
     * 真实原因是那条摆动根本没走到"停稳"）。所以每次摆动都把完整轨迹落盘，
     * 事后可以在 JVM 上原样回放。
     */
    private val traceBuf = ArrayDeque<FloatArray>()

    /**
     * 约 50Hz × 6s。
     * 得同时装下"最长一次摆动 + [TRACE_LEAD_MS] 前置"——实测最长的摆动到过 2536ms，
     * 加 1200ms 前置就是 3.7s；留到 6s 是为了别在长摆动上把前置段挤掉。
     */
    private val traceMax = 300

    /** 上一帧的绝对时间戳，用来算 dt（见 [dumpTrace] 与缓冲写入处的注释）。 */
    private var traceLastTs = 0L

    private var hbAt = 0L
    private var hbAccel = 0
    private var hbGyro = 0
    private var renewMs = WAKE_RENEW_MS
    private var sigPeriodUs = SensorManager.SENSOR_DELAY_NORMAL

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = System.currentTimeMillis()
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelCount.incrementAndGet()
                    if (accelLastTs > 0) {
                        val gap = now - accelLastTs
                        if (gap > accelMaxGapMs) accelMaxGapMs = gap
                        // 断流当场打点：只有在这里才能拿到"停投那一瞬间"的电源状态。
                        if (gap > GAP_WARN_MS) {
                            Log.i(
                                TAG,
                                "${P}断流 gap=${gap}ms interactive=${powerManager?.isInteractive} " +
                                    "doze=${powerManager?.isDeviceIdleMode} wl=${wakeLock?.isHeld == true}",
                            )
                        }
                    }
                    accelLastTs = now
                    accelX = e.values[0]
                    accelY = e.values[1]
                    accelZ = e.values[2]
                    val norm = kotlin.math.sqrt(
                        accelX * accelX + accelY * accelY + accelZ * accelZ,
                    )
                    val dev = kotlin.math.abs(norm - 9.81f)
                    if (dev > accelPeak) accelPeak = dev
                    if (detectMode) {
                        detector?.onAccel(accelX, accelY, accelZ, now)
                        // 屏幕亮着就持续刷新活跃窗口（每秒一次足够，窗口本身有 5s）。
                        // 屏幕灭 → 自然过期 → 不发动作，这是"零误触"的来源。
                        if (powerManager?.isInteractive == true && now - armRenewAt >= ARM_RENEW_STEP_MS) {
                            armRenewAt = now
                            detector?.arm(now, ARM_HOLD_MS)
                        }
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroCount.incrementAndGet()
                    val mag = kotlin.math.sqrt(
                        e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2],
                    )
                    if (mag > gyroPeak) gyroPeak = mag
                    if (detectMode) {
                        // 陀螺按**原样**喂进去：不做 rad→deg 换算。该 HAL 的单位本身存疑，
                        // 换算只会把存疑藏起来，而识别器已经不拿陀螺做判定了。
                        detector?.onGyro(e.values[0], e.values[1], e.values[2], now)
                    }
                }

                TYPE_WRIST_TILT -> {
                    tiltCount.incrementAndGet()
                    tiltAt = now
                    Log.i(TAG, "${P}wrist_tilt 命中 v0=${e.values.firstOrNull() ?: -1f}")
                }

                Sensor.TYPE_SIGNIFICANT_MOTION -> {
                    sigCount.incrementAndGet()
                    sigAt = now
                    // one-shot 传感器触发后自动停用，想要下一次必须重新 request。
                    sigmotion?.let { sensorManager?.requestTriggerSensor(trigger, it) }
                    Log.i(TAG, "${P}significant_motion 命中（已重新 request 等待下一次）")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** one-shot 传感器（significant_motion）必须走 TriggerEventListener，不能用 registerListener。 */
    private val trigger = object : SigTrigger() {
        override fun onFired() {
            sigCount.incrementAndGet()
            sigAt = System.currentTimeMillis()
            sigmotion?.let { sensorManager?.requestTriggerSensor(this, it) }
            Log.i(TAG, "${P}significant_motion 命中（已重新 request 等待下一次）")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val a = accelCount.get()
            val g = gyroCount.get()
            val t = tiltCount.get()
            val s = sigCount.get()
            val dt = if (hbAt == 0L) HEARTBEAT_MS / 1000.0 else (now - hbAt) / 1000.0
            val ra = (a - hbAccel) / dt
            val rg = (g - hbGyro) / dt
            hbAt = now
            hbAccel = a
            hbGyro = g

            Log.i(
                TAG,
                "${P}hb up=${(now - startedAt) / 1000}s accel=$a(${f(ra)}/s) gyro=$g(${f(rg)}/s) " +
                    "tilt=$t sig=$s gapMax=${accelMaxGapMs}ms " +
                    "aPeak=${f(accelPeak)} gPeak=${f(gyroPeak)} chg=${batteryManager?.isCharging} " +
                    "wl=${wakeLock?.isHeld == true} interactive=${powerManager?.isInteractive} " +
                    "doze=${powerManager?.isDeviceIdleMode}",
            )
            accelPeak = 0f
            gyroPeak = 0f
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * 识别轨迹记录 —— 标定的主记录，不是那一屏数字。
     *
     * 原因很实际：**做手腕动作时人是看不见屏幕的**。屏幕上只能看到"当前角度"，
     * 手腕一回中就归零，什么也留不下；日志才能事后把整段轨迹拉出来看。
     *
     * 只在"有动静"时打点（角度 / 角速度 / 转角积分超过小阈值，或状态离开 IDLE），
     * 完全静止时按 DBG_IDLE_MS 打一条保活行 → 一次 5 个动作的标定通常只有几十行。
     */
    private val dbgTick = object : Runnable {
        override fun run() {
            if (!detectMode) return
            val d = detector ?: return
            val now = System.currentTimeMillis()
            val s = d.snapshot(now)
            /*
             * 基准就绪/丢失必须显式打点。
             *
             * 实测教训：基准未建立时状态是 NEED_BASELINE，此时**所有 φ 都相对默认的
             * (0,0,1) 算**，于是动作会静默地被丢掉——用户和我都看不出来，
             * 白白把锅扣到阈值头上（上一轮 10 段里 2 段就是这么丢的）。
             *   · 建立基准要求连续 restHoldMs(800ms) 静止；
             *   · 而屏幕灭时加速度计根本不投数据（实测 accel=0），
             *     所以「亮屏 → 立刻做动作」必然丢前两下。
             * 这条日志让"还没就绪"变成看得见的事实。
             */
            val ready = s.state != WristGestureDetector.State.NEED_BASELINE
            if (ready != baselineReady) {
                baselineReady = ready
                val b = d.baselineVector()
                Log.i(
                    TAG,
                    if (ready) {
                        "${G}基准已建立 u=(${f3(b[0])},${f3(b[1])},${f3(b[2])}) —— 此后才可能有命中"
                    } else {
                        "${G}基准丢失（回到 NEED_BASELINE）—— 需静置 ${WristGestureDetector.Config().restHoldMs}ms 才会重建"
                    },
                )
            }
            val active = s.state != WristGestureDetector.State.IDLE ||
                kotlin.math.abs(s.phiArm) > DBG_MIN_DEG ||
                kotlin.math.abs(s.phiBend) > DBG_MIN_DEG
            if (active || now - dbgAt >= DBG_IDLE_MS) {
                dbgAt = now
                Log.i(
                    TAG,
                    "${G}dbg st=${s.state} θ=${f(s.thetaDeg)} φarm=${f(s.phiArm)} φbend=${f(s.phiBend)} " +
                        "轴系数 臂=${f(s.coefArm)} 屈=${f(s.coefBend)} " +
                        "速率=${f(s.rateDegPerSec)}°/s 中=${f(s.coolRemainMs / 1000f)}s 静=${f(s.stillMs / 1000f)}s " +
                        "ωraw=${f(s.gyroRaw)} 退化=${d.isArmDegenerate()}",
                )
            }
            handler.postDelayed(this, DBG_PERIOD_MS)
        }
    }

    private val renew = object : Runnable {        override fun run() {
            // 关键：不能只调 acquire()。系统把唤醒锁强制回收后，客户端 mHeld 仍是 true，
            // 此时再 acquire() 是空操作（trace 里根本没有新的 ACQ），所以必须重建对象。
            acquireWake("续期")
            handler.postDelayed(this, renewMs)
        }
    }

    private fun acquireWake(reason: String) {
        val pm = powerManager ?: return
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristDeck:probe")?.apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
        Log.i(TAG, "${P}wakelock $reason held=${wakeLock?.isHeld == true} 间隔=${renewMs}ms")
    }

    fun start(ctx: Context, renewMsOverride: Long = 0L) {
        if (running) {
            Log.i(TAG, "${P}已在运行，忽略重复启动")
            return
        }
        renewMs = if (renewMsOverride > 0) renewMsOverride else WAKE_RENEW_MS
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        sensorManager = sm
        powerManager = pm
        batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sigmotion = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        wristTilt = sm.getDefaultSensor(TYPE_WRIST_TILT)

        val okA = accel?.let { sm.registerListener(listener, it, ACCEL_PERIOD_US) } ?: false
        val okG = gyro?.let { sm.registerListener(listener, it, GYRO_PERIOD_US) } ?: false
        val okT = wristTilt?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false

        // significant_motion 是 one-shot + wakeUp：正确入口是 requestTriggerSensor。
        // （上一轮用 registerListener 的三种参数组合实测全部返回 false。）
        val sigTry = mutableListOf<String>()
        var okS = false
        sigmotion?.let { s ->
            okS = sm.requestTriggerSensor(trigger, s)
            sigTry += "requestTrigger=$okS"
            if (!okS) {
                for ((name, f) in listOf<Pair<String, () -> Boolean>>(
                    "normal" to { sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL) },
                    "delay0" to { sm.registerListener(listener, s, 0) },
                )) {
                    val r = f()
                    sigTry += "$name=$r"
                    if (r) {
                        okS = true
                        break
                    }
                }
            }
        }
        registerReport = "accel=$okA gyro=$okG sig=$okS tilt=$okT"
        acquireWake("首次")

        running = true
        startedAt = System.currentTimeMillis()
        hbAt = 0L
        hbAccel = 0
        hbGyro = 0
        accelMaxGapMs = 0
        accelLastTs = 0

        Log.i(
            TAG,
            "${P}start accel=${accel?.name}/${accel?.maxDelay}us gyro=${gyro?.name} " +
                "sig=${sigmotion?.name}/mode=${sigmotion?.reportingMode}/wakeup=${sigmotion?.isWakeUpSensor} " +
                "tilt=${wristTilt?.name} 注册结果 $registerReport 续期间隔=${renewMs}ms " +
                "sig 尝试=${sigTry.joinToString(",")} wl=${wakeLock?.isHeld == true}",
        )
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        handler.postDelayed(renew, renewMs)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(renew)
        handler.removeCallbacks(dbgTick)
        sensorManager?.unregisterListener(listener)
        sensorManager?.cancelTriggerSensor(trigger, sigmotion)
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        Log.i(
            TAG,
            "${P}stop 累计 accel=${accelCount.get()} gyro=${gyroCount.get()} " +
                "tilt=${tiltCount.get()} sig=${sigCount.get()} gapMax=${accelMaxGapMs}ms",
        )
    }

    /* ---------------- 识别调试模式 ---------------- */

    /**
     * 开 / 关识别调试模式。只有打开时才创建识别器——纯探针模式不该白算一份。
     *
     * [bypassArm] 默认 true：标定阶段必须旁路"屏幕亮"闸门，否则做动作时屏幕一灭就白做。
     * 想验证闸门本身（抬手亮屏关着时日常佩戴会不会误触）就传 false。
     */
    fun setDetectMode(on: Boolean, bypassArm: Boolean = true) {
        detectMode = on
        if (on) {
            ensureDetector()
            detector?.bypassArm = bypassArm
            armRenewAt = 0L
            // 开/关都清一次轨迹：否则重开时环里还压着上一次会话的旧帧，
            // dumpTrace 会从旧数据里切段，打出来的轨迹前后对不上。
            traceBuf.clear()
            traceLastTs = 0L
            handler.removeCallbacks(dbgTick)
            handler.post(dbgTick)
            Log.i(TAG, "${P}识别调试模式 开 旁路窗口=$bypassArm cfg=${detector?.config()}")
        } else {
            traceBuf.clear()
            traceLastTs = 0L
            handler.removeCallbacks(dbgTick)
            Log.i(TAG, "${P}识别调试模式 关")
        }
    }

    fun isDetectMode(): Boolean = detectMode

    private fun ensureDetector() {
        if (detector != null) return
        val d = WristGestureDetector()
        d.onSample = { ts, ux, uy, uz ->
            /*
             * 存的是**帧间增量 dt**，不是绝对时间戳。
             * 绝对毫秒约 1.75e12，而 Float 只能精确表示到 1.67e7 ——
             * 直接塞进 FloatArray 会让所有帧的时间戳塌成同一个值，回放出来 dt=0，
             * 整个穿越/停稳判定全废。增量只有 ~20ms，Float 表示精确。
             *
             * 后三个数是重力单位向量（不是 φ）：回放要逐帧等价，就只能存原始观测量，
             * 见 [WristGestureDetector.onSample] 的说明。
             */
            val dt = if (traceBuf.isEmpty()) 0f else (ts - traceLastTs).coerceIn(0L, 1_000L).toFloat()
            traceLastTs = ts
            traceBuf.addLast(floatArrayOf(dt, ux, uy, uz))
            while (traceBuf.size > traceMax) traceBuf.removeFirst()
        }
        d.onEvent = { e ->
            // 命中一律落在日志里：调试页只能看一屏，日志才能事后统计命中率。
            // θ 与两个轴系数是**判定依据**（谁大决定轴，幅度窗口卡 θ），φ 只留着交叉核对。
            Log.i(
                TAG,
                "${G}命中 action=${e.action} θ=${f(e.thetaDeg)} " +
                    "轴系数 臂=${f(e.coefArm)} 屈=${f(e.coefBend)} " +
                    "φarm=${f(e.phiArm)} φbend=${f(e.phiBend)} " +
                    "state=${e.state} 退化=${d.isArmDegenerate()}",
            )
        }
        d.onSwing = { s ->
            // 每次摆动都记，**包括没触发动作的**——标定最需要的恰恰是"这个动作为什么没发"。
            // 于是"一个动作 = 一行日志"，不必再从时间轴上去猜哪一段对应哪个动作。
            // ⚠️ 这里打的是 **θ峰 + 轴判**（轴判 = 只按轴与幅度窗口算，本该发什么），
            // 不再打"主轴=φarm/φbend 谁大"——那个判据本身就是本轮修掉的 bug。
            Log.i(
                TAG,
                "${G}摆动 ${s.durMs}ms θ峰=${f(s.thetaPeak)} " +
                    "φarm=${f(s.armPeak)} φbend=${f(s.bendPeak)} " +
                    "轴判=${s.axisAction ?: "无"} 峰值速率=${f(s.peakRate)}°/s 结果=${s.fired ?: "无"}",
            )
            dumpTrace(s.durMs)
        }
        detector = d
    }

    /**
     * 把刚发生的那次摆动的完整轨迹打进日志，供事后离线回放。
     *
     * 缓冲里存的是帧间增量，这里累加成"相对摆动结束点的毫秒"再打印。
     * 用相对时间而不是挂钟时间，是因为离线回放只关心 dt——只要增量不变，
     * 状态机的穿越/停稳判定就与现场完全一致，而相对时间读起来也干净得多。
     *
     * 分块打印：一行 28 帧（≈560ms@50Hz），既方便 `grep` 好切，又远离 logcat 单行截断。
     * 只回放"结束前 durMs+400ms"这一段——环里更早的数据属于上一个动作，混进来只会干扰判读。
     */
    private fun dumpTrace(durMs: Long) {
        if (traceBuf.isEmpty()) return
        val all = traceBuf.toList()
        // 累加 dt 得到每帧的相对时刻，并把整段挪到"以最后一帧为 0"的坐标系。
        val relMs = LongArray(all.size)
        var acc = 0L
        for (i in all.indices) {
            acc += all[i][0].toLong()
            relMs[i] = acc
        }
        val tEnd = relMs.last()
        /*
         * 往前多留 [TRACE_LEAD_MS]：离线回放时识别器要从零重建基准，
         * 而启动基准要求静止 [WristGestureDetector.Config.restHoldMs]（800ms）。
         * 原先只留 400ms，减去摆动本身后剩下的静止段根本不够建基准 ——
         * 回放出来会一路"无动作"，白白以为阈值调错了。
         */
        val from = tEnd - durMs - TRACE_LEAD_MS
        val idx = relMs.indexOfFirst { it >= from }.let { if (it < 0) 0 else it }
        val seg = all.subList(idx, all.size)
        if (seg.isEmpty()) return
        val span = tEnd - relMs[idx]
        val b = detector?.baselineVector()
        val bTxt = if (b == null) "无" else "${f3(b[0])},${f3(b[1])},${f3(b[2])}"
        Log.i(
            TAG,
            "${G}轨迹 ${seg.size}帧 span=${span}ms 静止前置=${span - durMs}ms " +
                "基准=$bTxt 格式=t:ux:uy:uz",
        )
        seg.chunked(TRACE_CHUNK).forEachIndexed { rowIdx, row ->
            val base = idx + rowIdx * TRACE_CHUNK
            val s = row.withIndex().joinToString(" ") { (i, v) ->
                "${relMs[base + i]}:${f3(v[1])}:${f3(v[2])}:${f3(v[3])}"
            }
            Log.i(TAG, "${G}  $s")
        }
    }

    fun isRunning(): Boolean = running

    /** 给探针页面显示用的一屏快照（可用区只有 174dp 宽，行要短）。 */
    fun snapshot(): String {
        if (!running) return "未运行"
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("up ${(now - startedAt) / 1000}s  gapMax ${accelMaxGapMs}ms")
            appendLine("accel ${accelCount.get()}  gyro ${gyroCount.get()}")
            appendLine("tilt ${tiltCount.get()}  sig ${sigCount.get()}")
            appendLine("aPeak ${f(accelPeak)} gPeak ${f(gyroPeak)}")
            appendLine("chg ${batteryManager?.isCharging}  wl ${wakeLock?.isHeld == true}")
            appendLine("最近 tilt ${ago(now, tiltAt)} / sig ${ago(now, sigAt)}")
        }
    }

    /**
     * 识别调试页的一屏快照。
     *
     * 设计要点：把 **θ / 轴系数（臂、屈）** 同时显示——它们才是现在的判定依据
     * （幅度窗口看 θ，判轴看两个系数谁大）。φarm/φbend 不再上屏：它们只是共用 uz 的
     * 球极投影，纯旋前到 74° 时副轴 φ 会比主轴还大，摆在屏幕上只会误导人。
     * 日志里仍然打 φ，留着交叉核对。
     */
    fun detectionSnapshot(): String {
        val d = detector ?: return "识别未开启\n（按模式键打开）"
        val now = System.currentTimeMillis()
        val s = d.snapshot(now)
        return buildString {
            val armState = when {
                d.bypassArm -> "常开(调试)"
                s.armed -> "${s.armRemainMs / 1000}s"
                else -> "否"
            }
            appendLine("state ${s.state}  窗口 $armState")
            appendLine("θ ${f(s.thetaDeg)}°  轴 臂${f(s.coefArm)} 屈${f(s.coefBend)}")
            appendLine("速率 ${f(s.rateDegPerSec)}°/s")
            appendLine("中 ${f(s.coolRemainMs / 1000f)}s  静 ${f(s.stillMs / 1000f)}s")
            appendLine("基准 ${ago(now, s.baselineAt)}  退化 ${d.isArmDegenerate()}")
            appendLine("命中 ${s.eventCount} 次  ωraw ${f(s.gyroRaw)}")
            appendLine(s.lastEvent?.let { "最近 ${it.action} θ${f(it.thetaDeg)}°" } ?: "最近 -")
        }
    }

    private fun ago(now: Long, at: Long) = if (at <= 0) "-" else "${(now - at) / 1000}s 前"
    private fun f(v: Float) = String.format("%.2f", v)
    private fun f(v: Double) = String.format("%.1f", v)

    /** 轨迹里重力分量保留 3 位小数（向量要精度，少一位都可能让判定变样）。 */
    private fun f3(v: Float) = String.format("%.3f", v)
}
