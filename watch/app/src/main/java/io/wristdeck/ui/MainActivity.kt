package io.wristdeck.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.wristdeck.R
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.net.Protocol
import io.wristdeck.svc.BridgeService
import io.wristdeck.util.Feedback

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnToggle: ImageView

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

        btnToggle.setOnClickListener { onAction(toggleAction(), KEY_TOGGLE) }
        // 十字方向盘：四个方向各自独立去重（dedupeKey 默认取动作名），便于快速连按。
        // 图标用矢量图（ImageView），不走文字字形，避免字体回退导致的居中偏差。
        findViewById<ImageView>(R.id.btnUp).setOnClickListener { onAction(Protocol.ACTION_UP) }
        findViewById<ImageView>(R.id.btnDown).setOnClickListener { onAction(Protocol.ACTION_DOWN) }
        findViewById<ImageView>(R.id.btnLeft).setOnClickListener { onAction(Protocol.ACTION_LEFT) }
        findViewById<ImageView>(R.id.btnRight).setOnClickListener { onAction(Protocol.ACTION_RIGHT) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        renderToggle()
        BridgeService.start(this)
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

    private fun renderStatus(state: BridgeClient.State, detail: String?) {
        lastDetail = detail
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
        statusText.setText(pair.first)
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
        private const val DEDUPE_MS = 120L
        private const val KEY_TOGGLE = "toggle-button"
        private const val EXEC_OFFLINE = "exec_offline"
        private const val REASON_TIMEOUT = "exec_timeout"
        private const val REASON_BUSY = "busy"
        private const val REASON_BAD_PIN = "bad_pin"
        private const val REASON_PIN_LOCKED = "pin_locked"

        /** 收到这两个 reason 说明鉴权没过，和"没连上"是两回事，文案要分开。 */
        private val PIN_REASONS = setOf(REASON_BAD_PIN, REASON_PIN_LOCKED)
    }
}
