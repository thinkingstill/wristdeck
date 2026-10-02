package io.wristdeck.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import io.wristdeck.R
import io.wristdeck.gesture.GestureController
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.net.Protocol
import io.wristdeck.svc.BridgeService
import io.wristdeck.util.Feedback
import io.wristdeck.util.Prefs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnToggle: ImageView
    private lateinit var btnLink: Button

    /** 圆表版面上一次生效的"屏尺寸 + 状态行高"指纹，避免每帧重复规划。 */
    private var roundKey: String? = null

    /** 上一次规划时的屏尺寸（换了尺寸就重置 [roundPlans]）。 */
    private var roundSizeKey: String? = null

    /** 当前屏尺寸下已经规划过几次，用 [MAX_ROUND_PLANS] 封顶。 */
    private var roundPlans = 0

    /** 覆写尺寸后要等**下一次布局**才能量实测值（这一帧量的还是旧值）。 */
    private var verifyPending = false

    private var playing: Boolean? = null
    private var lastAction = ""
    private var lastClickAt = 0L
    private var lastDetail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnToggle = findViewById(R.id.btnToggle)
        btnLink = findViewById(R.id.btnLink)

        btnToggle.setOnClickListener { onAction(toggleAction(), KEY_TOGGLE) }
        // 连接总闸：一个按钮两个方向，文案跟着当前意图走。
        btnLink.setOnClickListener { if (Prefs.linkEnabled(this)) disconnect() else connect() }
        // 十字方向盘：四个方向各自独立去重（dedupeKey 默认取动作名），便于快速连按。
        // 图标用矢量图（ImageView），不走文字字形，避免字体回退导致的居中偏差。
        findViewById<ImageView>(R.id.btnUp).setOnClickListener { onAction(Protocol.ACTION_UP) }
        findViewById<ImageView>(R.id.btnDown).setOnClickListener { onAction(Protocol.ACTION_DOWN) }
        findViewById<ImageView>(R.id.btnLeft).setOnClickListener { onAction(Protocol.ACTION_LEFT) }
        findViewById<ImageView>(R.id.btnRight).setOnClickListener { onAction(Protocol.ACTION_RIGHT) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        /*
         * 圆表状态行的字号必须在**第一次布局之前**定下来。
         *
         * 规划器要实测状态行高，而字号直接决定它（真机量过：12sp → 34px，11sp → 30px）。
         * 如果把改字号留到 applyPlan 里，第一轮会按 34px 算出一个偏小的版面，
         * 布局一刷新、高度变 30px、指纹变了，又要再规划一轮 —— 白算一帧，日志里也是两行。
         */
        if (resources.configuration.isScreenRound) {
            statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROUND_STATUS_SP)
        }

        renderToggle()
        renderLink()
        // 只在"已连接"意图下拉服务。断开状态下进主页不能自己连回来，
        // 否则用户点了断开、退出去再进来就又连上了，断开形同虚设。
        BridgeService.startIfEnabled(this)

        findViewById<View>(R.id.mainRoot).addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            onRootLayout(v, r - l, b - t)
        }
    }

    /* ───────────────────────── 圆表版面 ─────────────────────────
     *
     * 方表（372×430）的版面是手算完钉死在 XML 里的，圆表（466×466 ≈ 233×233dp）直接套会坏：
     * 百分比按屏宽算 ⇒ 圆表上按钮绝对值更大 ⇒ 纵向链条顶爆；且圆屏顶部/底部的行两端落在圆外。
     *
     * 所以这里在布局完成后**量着改**：交给 [DisplayGeometry] 算出行宽行高，再覆写 LayoutParams。
     * 非圆屏**一个像素都不动**（`isRound=false` 时规划恒返回 null），
     * 这条有离线台 `.workbuddy/tests/display/` 断言守着（80 项）。
     *
     * 为什么用 OnLayoutChangeListener 而不是 onCreate 里 post{}：onCreate 时视图还没 attach，
     * post 出去的 runnable 会跑在第一次布局**之前**，量到的宽高全是 0（这个坑踩过）。
     */

    private fun onRootLayout(root: View, w: Int, h: Int) {
        if (!resources.configuration.isScreenRound) return
        if (w <= 0 || h <= 0) return

        if (verifyPending) {
            verifyPending = false
            logMeasured(w, h)
        }

        // 状态行高只能实测（12sp 文字 wrap_content）。它不随下面的按钮变化，
        // 所以第一帧量到的就是最终值，规划一次即收敛。
        //
        // 但不能无脑"指纹变了就重算"：真机上它出现过 29↔30px 的抖动（同一字号、不同测量时机），
        // 而每次重规划都会 requestLayout —— 万一来回抖就是无限布局循环（开着页就一直烧电）。
        // 所以这里按屏尺寸分段计数，最多重规划 3 次。
        val sizeKey = "${w}x$h"
        if (sizeKey != roundSizeKey) {
            roundSizeKey = sizeKey
            roundPlans = 0
        }
        val key = "$sizeKey/${statusText.height}"
        if (key == roundKey) return
        if (roundPlans >= MAX_ROUND_PLANS) {
            Log.w(TAG, "UI 圆表版面已规划 $roundPlans 次仍在变（$key），停止重规划")
            return
        }
        roundKey = key
        roundPlans++

        val plan = DisplayGeometry.planFor(roundSpec(w, h, statusText.height), isRound = true)
        if (plan == null) {
            Log.w(TAG, "UI 圆表版面规划失败（${w}x$h），保持 XML 原样")
            return
        }
        applyPlan(plan)
        Log.i(
            TAG,
            "UI 圆表版面 ${w}x$h 状态行高=${statusText.height}px k=${"%.3f".format(plan.scale)} " +
                    "链=${plan.chainH}px " +
                    "上/下=${plan.upSize} 播放=${plan.toggleSize} 左右=${plan.sideSize} " +
                    "状态=${plan.statusW} 底=${plan.bottomW} 点下移=${plan.dotMarginTop}" +
                    (if (plan.warnings.isEmpty()) " 警告=0" else " 警告=${plan.warnings}")
        )
        verifyPending = true
    }

    /**
     * 圆表的规划输入。
     *
     * ⚠️ 这里的 dp 常量与 `res/layout/activity_main.xml` 里的数值**必须一一对应**
     * （6dp padding / 8dp 点 / 5dp 间距 / 4dp 上键间距 / 20dp 底行 / 0.26 / 0.33）；
     * 它们是布局的"事实"，改了布局就要改这里。
     * `baseUpW` 等基准值刻意按**本屏宽度**重算 —— 这正好复现"百分比在圆表上被放大"这件事，
     * 再由规划器收窄回去，语义才是"收窄"而不是"另起一套尺寸"。
     */
    private fun roundSpec(w: Int, h: Int, statusRowH: Int): DisplayGeometry.Spec {
        val d = resources.displayMetrics.density
        fun px(dp: Float) = (dp * d).roundToInt()
        val contentW = w - 2 * px(PAD_DP)
        return DisplayGeometry.Spec(
            widthPx = w,
            heightPx = h,
            density = d,
            contentPadPx = px(PAD_DP),
            statusRowH = statusRowH,
            dotD = px(DOT_DP),
            statusTextGapPx = px(STATUS_GAP_DP),
            bottomRowH = px(BOTTOM_ROW_DP),
            gapStatus = px(GAP_STATUS_DP),
            gapToggle = px(GAP_TOGGLE_DP),
            gapDown = px(GAP_DOWN_DP),
            gapBottom = px(GAP_BOTTOM_DP),
            gapPill = px(GAP_PILL_DP),
            baseUpW = (PCT_UP * contentW).roundToInt(),
            baseToggleW = (PCT_TOGGLE * contentW).roundToInt(),
            baseSideW = (PCT_UP * contentW).roundToInt(),
            // 状态行最小宽 = 点 + 间距 + 最长**常用**文案（"PIN 错误" 按 4.5em 估）。
            // "未确认（可能已执行）"这类长文案在圆表顶部本来就放不下，让它 ellipsize（头部信息保留）。
            needStatusW = px(DOT_DP) + px(STATUS_GAP_DP) +
                    (NEED_STATUS_EM * ROUND_STATUS_SP * d).roundToInt(),
            // 底部两个按钮各 34dp —— 34dp 装得下 11sp 的两个汉字，再小就只剩"点得到看不见"。
            needBottomW = 2 * px(34f) + px(GAP_PILL_DP),
            needToggleW = px(40f),
            needUpW = px(40f),
            safePx = px(SAFE_DP),
            minTouchPx = px(MIN_TOUCH_DP),
        )
    }

    /**
     * 把规划结果写回 LayoutParams。
     *
     * - 方向键/播放键：`layout_width=0dp` + 百分比在圆表上算出来的值偏大，这里改成**固定 px**
     *   （宽高一并给 ⇒ XML 上的 `layout_constraintDimensionRatio` 自动失效，不再参与计算）。
     * - 居中不靠 margin：这几行本来就有 start/end 双向约束，宽度一改自然居中。
     * - 状态行是唯一要手动居中的（点只贴左）：点吃 marginStart，文字吃固定宽 + bias=0，
     *   让省略号边界也落在圆内，而不是贴着屏幕右边缘（那已经在圆外了）。
     */
    private fun applyPlan(plan: DisplayGeometry.Plan) {
        fun square(id: Int, size: Int) {
            val v = findViewById<View>(id)
            val lp = v.layoutParams as ConstraintLayout.LayoutParams
            lp.matchConstraintPercentWidth = 0f
            lp.width = size
            lp.height = size
            v.layoutParams = lp
        }
        square(R.id.btnUp, plan.upSize)
        square(R.id.btnToggle, plan.toggleSize)
        square(R.id.btnLeft, plan.sideSize)
        square(R.id.btnRight, plan.sideSize)
        square(R.id.btnDown, plan.downSize)

        findViewById<View>(R.id.rowBottom).let {
            val lp = it.layoutParams as ConstraintLayout.LayoutParams
            lp.matchConstraintPercentWidth = 0f
            lp.width = plan.bottomW
            it.layoutParams = lp
        }

        statusDot.let {
            val lp = it.layoutParams as ConstraintLayout.LayoutParams
            lp.marginStart = plan.dotMarginStart
            lp.topMargin = plan.dotMarginTop
            it.layoutParams = lp
        }
        statusText.let {
            val lp = it.layoutParams as ConstraintLayout.LayoutParams
            lp.width = plan.statusTextW
            lp.horizontalBias = 0f
            it.layoutParams = lp
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, ROUND_STATUS_SP)
        }
    }

    /**
     * 覆写之后再量一次实测值，跟圆心逐块核对 —— 这是"到底收进圆里没有"的硬证据
     * （`adb logcat -s WristDeck:I`）。规划器是算法，这里是布局引擎实际摆出来的结果，
     * 两者不一致就说明我对 ConstraintLayout 的假设有偏差。
     */
    private fun logMeasured(w: Int, h: Int) {
        val cx = w / 2.0
        val cy = h / 2.0
        val r = minOf(w, h) / 2.0
        val ids = intArrayOf(
            R.id.statusText, R.id.btnUp, R.id.btnToggle, R.id.btnLeft,
            R.id.btnRight, R.id.btnDown, R.id.rowBottom
        )
        val names = arrayOf("状态行", "上", "播放", "左", "右", "下", "底部行")
        val sb = StringBuilder()
        var worstOut = -Double.MAX_VALUE
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            // getLocationOnScreen 返回的是屏幕坐标；本页全屏、无状态栏偏移，可直接用
            val l = loc[0]
            val t = loc[1]
            val rr = l + v.width
            val bb = t + v.height
            var out = -Double.MAX_VALUE
            for (x in intArrayOf(l, rr)) {
                for (y in intArrayOf(t, bb)) {
                    val dx = x - cx
                    val dy = y - cy
                    out = maxOf(out, sqrt(dx * dx + dy * dy) - r)
                }
            }
            worstOut = maxOf(worstOut, out)
            sb.append("%s=(%d,%d,%d,%d 越界%.1f) ".format(names[i], l, t, rr, bb, out))
        }
        Log.i(TAG, "UI 实测 ${w}x$h R=$r ${sb}最差越界=%.1fpx".format(worstOut))
    }

    override fun onResume() {
        super.onResume()
        val client = BridgeHolder.get(this)
        client.callback = callback
        renderStatus(client.state, lastDetail)
    }

    override fun onPause() {
        BridgeHolder.get(this).callback = null
        super.onPause()
    }

    private fun onAction(action: String, dedupeKey: String = action) {
        val now = System.currentTimeMillis()
        if (dedupeKey == lastAction && now - lastClickAt < DEDUPE_MS) return
        lastAction = dedupeKey
        lastClickAt = now

        val client = BridgeHolder.get(this)

        if (client.state != BridgeClient.State.READY) {
            Feedback.offline(this)
            client.start()
            return
        }

        Feedback.tap(this)
        if (!client.sendAction(action)) {
            Feedback.fail(this)
            return
        }

        applyOptimistic(action)
    }

    /* 主按钮发"显式目标动作"（play/pause）而不是 toggle：
     * toggle 是相对动作，超时后重试会把状态翻回去；play/pause 才是幂等的。
     * 只有在本地状态未知时才退化为 toggle。
     */
    private fun toggleAction(): String = when (playing) {
        true -> Protocol.ACTION_PAUSE
        false -> Protocol.ACTION_PLAY
        null -> Protocol.ACTION_TOGGLE
    }

    private fun applyOptimistic(action: String) {
        val next = when (action) {
            Protocol.ACTION_PLAY -> true
            Protocol.ACTION_PAUSE -> false
            Protocol.ACTION_TOGGLE -> playing?.let { !it } ?: false
            else -> return
        }
        playing = next
        renderToggle()
    }

    private fun renderToggle() {
        btnToggle.setImageResource(if (playing == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /**
     * 连接总闸，两个方向。
     *
     * [Prefs.linkEnabled] 是**唯一真相**，服务和手势都只是它的执行者。
     * 顺序刻意"先写开关、再动服务"：反过来中间那一瞬，服务里的回调读到的还是旧意图，
     * 会把刚断开的状态又渲染回"连接中"。
     */
    private fun connect() {
        Prefs.setLinkEnabled(this, true)
        renderLink()
        BridgeService.start(this)
    }

    /**
     * 断开 = 停前台服务 + 断链路 + 停手势。
     *
     * 三件事都要做，少一件就漏：
     * - [stopService] 触发 onDestroy → 收客户端 + 释放 WakeLock/WifiLock；
     * - `GestureController.stop()` 立刻停采样，不等 onDestroy 的时机（用户点一下就该停）；
     * - [BridgeHolder.stop] 兜住"服务压根没在跑"的情况（比如装完 App 直接进主页就点断开）。
     */
    private fun disconnect() {
        Prefs.setLinkEnabled(this, false)
        renderLink()
        stopService(Intent(this, BridgeService::class.java))
        GestureController.stop()
        BridgeHolder.stop(this)
        renderStatus(BridgeHolder.get(this).state, null)
    }

    private fun renderLink() {
        val on = Prefs.linkEnabled(this)
        btnLink.text = getString(if (on) R.string.link_disconnect else R.string.link_connect)
    }

    private fun renderStatus(state: BridgeClient.State, detail: String?) {
        lastDetail = detail

        /*
         * "用户主动断开"要盖过一切链路状态。
         *
         * 不这么写的话，断开后会先显示"已断开"，紧接着 BridgeClient 那条
         * DISCONNECTED 回调进来又把它刷成"未连接"——用户会以为断开没生效。
         */
        if (!Prefs.linkEnabled(this)) {
            val off = ContextCompat.getColor(this, R.color.idle)
            statusText.text = getString(R.string.status_off)
            statusText.setTextColor(off)
            statusDot.background.mutate().setTint(off)
            return
        }

        val pair = when (state) {
            BridgeClient.State.READY -> when (detail) {
                EXEC_OFFLINE -> R.string.status_exec_offline to R.color.warn
                REASON_TIMEOUT -> R.string.status_unconfirmed to R.color.warn
                REASON_BUSY -> R.string.status_busy to R.color.warn
                else -> R.string.status_ready to R.color.ok
            }

            BridgeClient.State.CONNECTING -> R.string.status_connecting to R.color.warn
            BridgeClient.State.DENIED -> R.string.status_denied to R.color.bad
            // DENIED 之后 BridgeClient 会立刻转成 DISCONNECTED 去走重连退避，
            // 于是"PIN 错误"只闪一帧就被"未连接"盖掉。这里按 detail 把原因找回来。
            BridgeClient.State.DISCONNECTED -> if (detail in PIN_REASONS) {
                R.string.status_denied to R.color.bad
            } else {
                R.string.status_idle to R.color.idle
            }
        }

        val color = ContextCompat.getColor(this, pair.second)
        /*
         * 手势关掉时在状态条尾部缀一个短标记。
         *
         * 只报"异常态"（关了），正常开着就不加字 —— 主页纵向余量只剩 20px（372×430 的表），
         * 为它单开一行会把底部的设置按钮挤出去（这个坑踩过）。而"手势开着"本来就是默认预期，
         * 用户需要主动察觉的是"我把它关了，怎么没反应"。
         * 开关本体放在设置页（那里是 ScrollView，加行没有版面风险）。
         */
        val suffix = if (Prefs.gestureOn(this)) "" else getString(R.string.gesture_off_suffix)
        statusText.text = getString(pair.first) + suffix
        statusText.setTextColor(color)
        statusDot.background.mutate().setTint(color)
    }

    private val callback = object : BridgeClient.Callback {
        override fun onStateChanged(state: BridgeClient.State, detail: String?) {
            runOnUiThread { renderStatus(state, detail) }
        }

        override fun onAck(ack: BridgeClient.Ack) {
            runOnUiThread {
                if (ack.ok) {
                    if (ack.action in Protocol.TOGGLE_ACTIONS && ack.playing != null) {
                        playing = ack.playing
                        renderToggle()
                    }
                    // 成功即清掉上一次的告警文案，状态条回到绿色
                    renderStatus(BridgeHolder.get(this@MainActivity).state, null)
                } else {
                    Feedback.fail(this@MainActivity)
                    renderStatus(BridgeHolder.get(this@MainActivity).state, ack.reason)
                }
            }
        }

        /* 超时后才回来的结果：页面其实已经响应了。静默对齐图标，不重复震动、
         * 也不再提示失败，避免"看到画面变了但手表说失败"的不一致。
         */
        override fun onCmdLate(ok: Boolean, playing: Boolean?) {
            runOnUiThread {
                if (playing == null) return@runOnUiThread
                if (playing != this@MainActivity.playing) {
                    this@MainActivity.playing = playing
                    renderToggle()
                }
                renderStatus(BridgeHolder.get(this@MainActivity).state, null)
            }
        }
    }

    companion object {
        private const val TAG = "WristDeck"
        private const val DEDUPE_MS = 120L
        private const val KEY_TOGGLE = "toggle-button"
        private const val EXEC_OFFLINE = "exec_offline"
        private const val REASON_TIMEOUT = "exec_timeout"
        private const val REASON_BUSY = "busy"
        private const val REASON_BAD_PIN = "bad_pin"
        private const val REASON_PIN_LOCKED = "pin_locked"

        // —— 圆表版面的"布局事实"：与 res/layout/activity_main.xml 一一对应，改一边要改两边 ——
        /** 根布局四边 padding。 */
        private const val PAD_DP = 6f
        /** 状态点直径。 */
        private const val DOT_DP = 8f
        /** 状态点与状态文字的间距。 */
        private const val STATUS_GAP_DP = 5f
        /** 状态行 → 上键。 */
        private const val GAP_STATUS_DP = 4f
        /** 上键 → 播放键。 */
        private const val GAP_TOGGLE_DP = 5f
        /** 播放键 → 下键。 */
        private const val GAP_DOWN_DP = 5f
        /** 下键 → 底部行。 */
        private const val GAP_BOTTOM_DP = 5f
        /** 左右键与播放键的横向间距。 */
        private const val GAP_PILL_DP = 5f
        /** 底部按钮行高。 */
        private const val BOTTOM_ROW_DP = 20f
        /** 上/下/左右键的宽度占可用宽比例（方表基准，圆表由此收窄）。 */
        private const val PCT_UP = 0.26f
        /** 播放键的宽度占比。 */
        private const val PCT_TOGGLE = 0.33f

        // —— 圆表专属的产品判断 ——
        /** 距圆边留的视觉安全边（弦宽里先扣掉两侧）。 */
        private const val SAFE_DP = 4f
        /** 圆表上状态文字字号：顶部弧内宽度有限，12sp 放不下"浏览器未就绪"这种 6 字文案。 */
        private const val ROUND_STATUS_SP = 11f
        /** 估最长常用状态文案的宽度（em），用于判断状态行够不够 */
        private const val NEED_STATUS_EM = 4.5f
        /** 建议最小可点边长，低于它只告警不失败（表上 48dp 不现实）。 */
        private const val MIN_TOUCH_DP = 44f
        /** 同一屏尺寸下最多规划几次，防止测量抖动把布局循环起来。 */
        private const val MAX_ROUND_PLANS = 3

        /** 收到这两个 reason 说明鉴权没过，和"没连上"是两回事，文案要分开。 */
        private val PIN_REASONS = setOf(REASON_BAD_PIN, REASON_PIN_LOCKED)
    }
}
