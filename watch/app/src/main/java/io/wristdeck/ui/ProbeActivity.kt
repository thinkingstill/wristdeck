package io.wristdeck.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.wristdeck.R
import io.wristdeck.gesture.GestureProbe
import io.wristdeck.svc.ProbeService

/**
 * 手势探针页（阶段 3 的前置测量，不参与正式功能）。
 *
 * 用法（Activity 已 exported，方便 adb 直接驱动）：
 *   adb shell am start -n io.wristdeck/.ui.ProbeActivity            # 只采样，不启前台服务
 *   adb shell am start -n io.wristdeck/.ui.ProbeActivity --ez fgs true   # 同时带前台服务
 * 数据全在 logcat：`adb logcat -s WristDeck:I`，关注 `PROBE ` 前缀。
 *
 * 对照实验：屏幕熄灭后，
 *  - 不带 fgs → 测"纯后台进程"能不能拿到 continuous 传感器；
 *  - 带 fgs   → 测前台服务豁免是否生效。
 * 唤醒锁由 GestureProbe 持有并每 5 分钟续期，所以 AP 不该睡 —— 一旦事件停投，
 * 就直接指向后台限制，而不是"设备睡着了"。
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var probeText: TextView
    private lateinit var btnMode: Button
    private var gateForced = false
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            probeText.text = if (GestureProbe.isDetectMode()) {
                GestureProbe.detectionSnapshot()
            } else {
                GestureProbe.snapshot()
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)

        probeText = findViewById(R.id.probeText)
        probeText.movementMethod = ScrollingMovementMethod.getInstance()

        val withFgs = intent?.getBooleanExtra(EXTRA_FGS, false) ?: false
        if (withFgs) ProbeService.start(this)

        btnMode = findViewById(R.id.btnMode)
        findViewById<Button>(R.id.btnStop).setOnClickListener { shutdown() }
        btnMode.setOnClickListener { setDetect(!GestureProbe.isDetectMode()) }

        // 续期间隔可调（秒），用来验证"被系统回收后能否靠频繁续期顶住"。
        val renewSec = intent?.getIntExtra(EXTRA_RENEW, 0) ?: 0
        GestureProbe.start(this, renewSec * 1000L)
        // 也可以用 adb 直接进识别调试模式，省得在床上戳那 20dp 的小按钮：
        //   adb shell am start -n io.wristdeck/.ui.ProbeActivity --ez detect true
        // 想验证"屏幕亮"这道闸门本身（测日常佩戴误触率），再补一个 --ez gate true。
        gateForced = intent?.getBooleanExtra(EXTRA_GATE, false) ?: false
        if (intent?.getBooleanExtra(EXTRA_DETECT, false) == true) setDetect(true) else renderMode()

        handler.post(refresh)
        reportLayoutOnce()
        Log.i(TAG, "PROBE ui onCreate fgs=$withFgs renew=${if (renewSec > 0) "${renewSec}s" else "默认"}")
    }

    /**
     * 版面自检：把真实测量尺寸打进日志。
     *
     * 为什么需要这个：USB 供电时系统充电界面（SysUI.Charging）会顶在最上层，
     * **截屏和 uiautomator 都只能看到它**（实测 dump 出来的树里只有 systemui 的 VideoView），
     * 所以"底部按钮有没有被裁"根本没法用截图确认。尺寸进日志就能读出来，
     * 尤其有用的是 372×430 这种纵向余量很小的表面。
     */
    private fun reportLayoutOnce() {
        val root = findViewById<View>(android.R.id.content)
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val row = findViewById<LinearLayout>(R.id.btnRow)
                    val text = findViewById<TextView>(R.id.probeText)
                    Log.i(
                        TAG,
                        "PROBE ui 版面 根=${root.width}x${root.height} " +
                            "文本框=${text.width}x${text.height}@y${text.top} " +
                            "按钮行=${row.width}x${row.height}@y${row.top}..${row.bottom} " +
                            "底部余量=${root.height - row.bottom}px 被裁=${row.bottom > root.height}",
                    )
                }
            },
        )
    }

    /**
     * 切识别模式。识别器只在本次 Activity 存活期间喂养；切模式不重开采样流，
     * 免得反复注册/反注册传感器把 50Hz 数据切出断口。
     */
    private fun setDetect(on: Boolean) {
        GestureProbe.setDetectMode(on, bypassArm = !gateForced)
        renderMode()
    }

    private fun renderMode() {
        btnMode.text = getString(
            if (GestureProbe.isDetectMode()) R.string.probe_mode_detect else R.string.probe_mode_raw,
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        // 息屏不会销毁 Activity，只有真正退出才停采样。
        if (isFinishing) shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        GestureProbe.stop()
        ProbeService.stop(this)
        Log.i(TAG, "PROBE ui shutdown")
    }

    companion object {
        private const val TAG = "WristDeck"
        private const val EXTRA_FGS = "fgs"
        private const val EXTRA_RENEW = "renew"
        private const val EXTRA_DETECT = "detect"
        private const val EXTRA_GATE = "gate"
        private const val REFRESH_MS = 1_000L
    }
}
