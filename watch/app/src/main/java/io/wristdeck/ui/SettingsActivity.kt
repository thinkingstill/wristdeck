package io.wristdeck.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.switchmaterial.SwitchMaterial
import io.wristdeck.R
import io.wristdeck.gesture.GestureController
import io.wristdeck.model.Config
import io.wristdeck.model.Link
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.net.Protocol
import io.wristdeck.svc.BridgeService
import io.wristdeck.util.Prefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchLink: SwitchMaterial
    private lateinit var groupWifi: View
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editPin: EditText
    private lateinit var switchKeepAlive: SwitchMaterial
    private lateinit var switchGesture: SwitchMaterial
    private lateinit var textResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchLink = findViewById(R.id.switchLink)
        groupWifi = findViewById(R.id.groupWifi)
        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        editPin = findViewById(R.id.editPin)
        switchKeepAlive = findViewById(R.id.switchKeepAlive)
        switchGesture = findViewById(R.id.switchGesture)
        textResult = findViewById(R.id.textResult)

        val cfg = Prefs.load(this)
        editHost.setText(cfg.host)
        editPort.setText(cfg.port.toString())
        editPin.setText(cfg.pin)
        switchKeepAlive.isChecked = cfg.keepAlive
        switchGesture.isChecked = Prefs.gestureOn(this)

        /*
         * 连接方式开关。
         *
         * 与手势开关不同，这里**不即时生效** —— 它和 IP/端口一样属于"连接参数"，
         * 语义是"改了要重连"，所以统一走"保存并连接"。切换时只做一件事：
         * 把用不到的 Wi-Fi 输入区收起来（视觉上说明"这块跟当前模式无关"）。
         */
        switchLink.isChecked = cfg.link == Link.BLE
        groupWifi.isVisible = cfg.link == Link.WIFI
        switchLink.setOnCheckedChangeListener { _, checked ->
            groupWifi.isVisible = !checked
        }

        /*
         * 手势开关**即时生效**，不走"保存"按钮。
         *
         * 理由：这是个"现在就要它生效/现在就要它闭嘴"的开关（比如发现误触想立刻关掉），
         * 而保存按钮的语义是"改连接参数并重连"—— 把它卷进去，用户关个手势会顺带断一次连接。
         * 传感器是独占资源，所以开关同时负责启停采样，不只是记一个标志位。
         */
        switchGesture.setOnCheckedChangeListener { _, checked ->
            Prefs.setGestureOn(this, checked)
            if (checked) {
                GestureController.start(this)
                // 服务没起来的话，连通知栏的前台服务也一起补上。
                // ⚠️ 走 startIfEnabled：用户在主页面点了「断开」时，这里不许把链路拉回来
                // （断开 = 全停，包括手势；想恢复得回主页点「连接」）。
                BridgeService.startIfEnabled(this)
                textResult.setText(R.string.gesture_enabled)
            } else {
                GestureController.stop()
                textResult.setText(R.string.gesture_disabled)
            }
        }

        /*
         * 返回。顶部那条固定按钮。
         *
         * 这一页此前没有任何返回入口（主题是 NoActionBar，也没有 Toolbar），
         * 只能靠系统返回手势（左边缘右划）离开 —— 实测用户当场卡住。
         * 用 finish() 而不是手动 startActivity(MainActivity)：MainActivity 还在返回栈里，
         * finish 才是"退一层"的语义，也不会把它的 onResume 重跑一遍。
         */
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }

        applyRoundInset()
    }

    /* ───────────────────────── 圆表版面 ─────────────────────────
     *
     * 本页每行都是 match_parent，而圆屏顶部/底部的**弦很窄**：实测返回按钮占
     * x∈[20,446]、y∈[12,68]，可 y=12 处圆的弦只有 [159,307] ⇒ 按钮大部分落在圆外。
     *
     * 主页能用"逐行收窄"（每行的 y 固定），本页不行 —— 滚动列表的任何一行都可能滑到
     * 视口的任意 y。所以这里只做一件事：**把视口收进圆的内接正方形**，视口是正方形，
     * 里面任何矩形就都在圆内，与滚动位置无关。一次性内缩，之后不用再管。
     *
     * 非圆屏**不设任何 padding**，方表逐像素不变（`pageInset` 直接返回 ZERO）。
     *
     * ⚠️ 这里**必须用 displayMetrics 直接算、在第一次布局之前把 padding 设好**，
     * 绝不能走 `addOnLayoutChangeListener` 在布局回调里 `setPadding()` —— 那是踩过的坑：
     * 在 `onLayout` 之后调 `setPadding()`，那次 `requestLayout()` 落在**布局过程当中**，
     * 结果是 **padding 生效、子视图却停在旧坐标**：父容器按新的内容区裁剪，而孩子还在
     * 原地。实测症状就是"整页被切"——左边文字少了半个字（「用蓝牙连接」显示成「丁连接」）、
     * 右边开关被削掉一半、顶部「返回」整个消失。
     * 铁证（`uiautomator dump`）：子视图 bounds 仍是加 padding 之前的 `[20,88][446,156]`，
     * 而像素上内容的可见范围正好是 `x∈[80,385]`，即内缩后的内容区。
     */
    private fun applyRoundInset() {
        if (!resources.configuration.isScreenRound) return
        val dm = resources.displayMetrics
        val margin = (INSET_MARGIN_DP * dm.density).toInt()
        val inset = DisplayGeometry.pageInset(dm.widthPixels, dm.heightPixels, margin, true)
        if (inset.isZero) return
        findViewById<View>(R.id.settingsRoot)
            .setPadding(inset.hPx, inset.vPx, inset.hPx, inset.vPx)
        Log.i(
            TAG,
            "UI 设置页圆表内缩 水平=${inset.hPx}px 垂直=${inset.vPx}px " +
                    "（屏 ${dm.widthPixels}x${dm.heightPixels}，" +
                    "视口 ${dm.widthPixels - 2 * inset.hPx}x${dm.heightPixels - 2 * inset.vPx}）"
        )
    }

    override fun onResume() {
        super.onResume()
        BridgeHolder.get(this).callback = callback
    }

    override fun onPause() {
        BridgeHolder.get(this).callback = null
        super.onPause()
    }

    private fun currentConfig(): Config = Config(
        host = editHost.text.toString().trim(),
        port = editPort.text.toString().trim().toIntOrNull() ?: Config.DEFAULT_PORT,
        pin = editPin.text.toString().trim(),
        keepAlive = switchKeepAlive.isChecked,
        link = if (switchLink.isChecked) Link.BLE else Link.WIFI,
    )

    /**
     * 只有 Wi-Fi 需要填地址；BLE 由 Mac 侧扫描连接，没有"目标"可填。
     * 在 BLE 模式下拿空 host 拦人，会让用户以为哪里没填对。
     */
    private fun missingHost(cfg: Config): Boolean =
        cfg.link == Link.WIFI && cfg.host.isBlank()

    private fun save() {
        val cfg = currentConfig()
        if (missingHost(cfg)) {
            textResult.setText(R.string.need_host)
            return
        }
        Prefs.save(this, cfg)
        // restart() 自己会看 link_enabled 决定要不要连（见 BridgeHolder）：
        // 处于断开状态时这里只换参数、不连接。文案也要跟着变，否则按钮在说谎。
        BridgeHolder.restart(this)
        BridgeHolder.get(this).callback = callback
        BridgeService.startIfEnabled(this)
        textResult.setText(
            if (Prefs.linkEnabled(this)) R.string.saved else R.string.saved_off
        )
    }

    private fun test() {
        val cfg = currentConfig()
        if (missingHost(cfg)) {
            textResult.setText(R.string.need_host)
            return
        }
        // "测试"本质就是连一下试试。断开状态下不许绕过总闸自己去连，
        // 否则用户会发现"我没点连接，它怎么又连上了"。
        if (!Prefs.linkEnabled(this)) {
            textResult.setText(R.string.need_link)
            return
        }
        Prefs.save(this, cfg)
        val client = BridgeHolder.get(this)
        client.updateConfig(cfg)
        client.callback = callback
        when (client.state) {
            BridgeClient.State.READY -> {
                client.sendAction(Protocol.ACTION_TOGGLE)
                textResult.text = "已发送测试指令"
            }

            else -> {
                client.start()
                textResult.text = when (cfg.link) {
                    Link.BLE -> getString(R.string.waiting_gateway)
                    Link.WIFI -> "正在连接 ${cfg.host}:${cfg.port}…"
                }
            }
        }
    }

    private val callback = object : BridgeClient.Callback {
        override fun onStateChanged(state: BridgeClient.State, detail: String?) {
            runOnUiThread {
                textResult.text = when (state) {
                    BridgeClient.State.READY -> if (detail == "exec_offline") {
                        "已连接，但浏览器执行器未就绪"
                    } else {
                        "已连接"
                    }

                    BridgeClient.State.CONNECTING -> "连接中…"
                    BridgeClient.State.DENIED -> "PIN 错误，请检查"
                    // DENIED 会被紧随其后的 DISCONNECTED 覆盖，靠 detail 把原因留住
                    BridgeClient.State.DISCONNECTED -> when (detail) {
                        "bad_pin", "pin_locked" -> "PIN 错误，请检查"
                        else -> "未连接${detail?.let { "：$it" } ?: ""}"
                    }
                }
            }
        }

        override fun onAck(ack: BridgeClient.Ack) {
            runOnUiThread {
                textResult.text = if (ack.ok) {
                    "执行成功 ${ack.e2e}ms"
                } else {
                    "执行失败：${ack.reason}"
                }
            }
        }
    }

    companion object {
        private const val TAG = "WristDeck"

        /**
         * 圆表内缩时距圆边再留的视觉余量。
         * 内接正方形的四个角**正好落在圆上**，不留余量会被圆框切着看；8dp 实测够用。
         */
        private const val INSET_MARGIN_DP = 8f
    }
}
