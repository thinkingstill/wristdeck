package io.wristdeck.net

import org.json.JSONObject

object Protocol {
    const val ACTION_TOGGLE = "toggle"
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"

    /* 方向盘：四个方向不做语义翻译，原样转发 ArrowUp/Down/Left/Right 键盘事件，
     * 由页面自己解释（抖音推荐流里上下翻视频、左右快进退）。 */
    const val ACTION_UP = "up"
    const val ACTION_DOWN = "down"
    const val ACTION_LEFT = "left"
    const val ACTION_RIGHT = "right"

    /** 这些动作会改变播放状态，ack 里的 state.playing 可用于纠正本地图标。 */
    val TOGGLE_ACTIONS = setOf(ACTION_TOGGLE, ACTION_PLAY, ACTION_PAUSE)

    sealed class In {
        data class Welcome(val execOnline: Boolean) : In()
        data class Ack(
            val id: String,
            val ok: Boolean,
            val playing: Boolean?,
            val reason: String?,
            val e2e: Int,
        ) : In()

        data class Evt(val online: Boolean) : In()

        /** 超时后才回来的执行结果：只用于静默纠正图标，不参与失败判定。 */
        data class CmdLate(val ok: Boolean, val playing: Boolean?) : In()

        data class Denied(val reason: String?) : In()
        object Ping : In()
        object Unknown : In()
    }

    fun hello(dev: String, pin: String): String = JSONObject()
        .put("v", 1)
        .put("t", "hello")
        .put("role", "watch")
        .put("dev", dev)
        .put("pin", pin)
        .toString()

    fun cmd(id: String, action: String, ts: Long): String = JSONObject()
        .put("v", 1)
        .put("t", "cmd")
        .put("id", id)
        .put("a", action)
        .put("ts", ts)
        .toString()

    fun pong(): String = JSONObject()
        .put("v", 1)
        .put("t", "pong")
        .toString()

    fun parse(text: String): In = try {
        val o = JSONObject(text)
        when (o.optString("t")) {
            "welcome" -> In.Welcome(o.optJSONObject("cfg")?.optBoolean("execOnline") ?: false)
            "ack" -> In.Ack(
                id = o.optString("id"),
                ok = o.optBoolean("ok"),
                playing = o.optJSONObject("state")
                    ?.takeIf { it.has("playing") }
                    ?.optBoolean("playing"),
                reason = o.optString("reason").takeIf { it.isNotBlank() },
                e2e = o.optJSONObject("lat")?.optInt("e2e") ?: 0,
            )
            "evt" -> when (o.optString("e")) {
                "cmd_late" -> In.CmdLate(
                    ok = o.optBoolean("ok"),
                    playing = o.optJSONObject("state")
                        ?.takeIf { it.has("playing") }
                        ?.optBoolean("playing"),
                )

                else -> In.Evt(o.optString("e") == "exec_online")
            }
            "denied" -> In.Denied(o.optString("reason").takeIf { it.isNotBlank() })
            "ping" -> In.Ping
            else -> In.Unknown
        }
    } catch (e: Exception) {
        In.Unknown
    }
}
