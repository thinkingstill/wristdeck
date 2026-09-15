package io.wristdeck.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import io.wristdeck.R
import io.wristdeck.gesture.GestureController
import io.wristdeck.model.Config
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.net.Protocol
import io.wristdeck.svc.BridgeService
import io.wristdeck.util.Prefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var editPin: EditText
    private lateinit var switchKeepAlive: SwitchMaterial
    private lateinit var switchGesture: SwitchMaterial
    private lateinit var textResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
                // 服务没起来的话，连通知栏的前台服务也一起补上（正常由主页负责启动）。
                BridgeService.start(this)
                textResult.setText(R.string.gesture_enabled)
            } else {
                GestureController.stop()
                textResult.setText(R.string.gesture_disabled)
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }
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
        port = editPort.text.toString().toIntOrNull() ?: Config.DEFAULT_PORT,
        pin = editPin.text.toString().trim(),
        keepAlive = switchKeepAlive.isChecked,
    )

    private fun save() {
        val cfg = currentConfig()
        if (cfg.host.isBlank()) {
            textResult.setText(R.string.need_host)
            return
        }
        Prefs.save(this, cfg)
        BridgeHolder.restart(this)
        BridgeHolder.get(this).callback = callback
        BridgeService.start(this)
        textResult.setText(R.string.saved)
    }

    private fun test() {
        val cfg = currentConfig()
        if (cfg.host.isBlank()) {
            textResult.setText(R.string.need_host)
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
                textResult.text = "正在连接 ${cfg.host}:${cfg.port}…"
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
}
