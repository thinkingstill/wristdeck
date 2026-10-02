package io.wristdeck.util

import android.content.Context
import io.wristdeck.model.Config
import io.wristdeck.model.Link

object Prefs {
    private const val FILE = "wristdeck"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "pin"
    private const val K_KEEP = "keep_alive"
    private const val K_GESTURE = "gesture_on"

    /** 连接方式。存枚举 name，读不认时退回 [Link.BLE]（默认值）。 */
    private const val K_LINK = "link"

    /** 用户的连接意图，见 [linkEnabled]。 */
    private const val K_LINK_ENABLED = "link_enabled"

    fun load(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Config(
            host = sp.getString(K_HOST, "") ?: "",
            port = sp.getInt(K_PORT, Config.DEFAULT_PORT),
            pin = sp.getString(K_PIN, "") ?: "",
            keepAlive = sp.getBoolean(K_KEEP, true),
            link = readLink(sp.getString(K_LINK, null)),
        )
    }

    /**
     * 老版本升级上来时没有 `link` 键 ⇒ 取默认 BLE。
     * 用 `valueOf` 包一层是为了防脏数据（比如手改 SharedPreferences 写错名字）直接把加载打崩。
     */
    private fun readLink(raw: String?): Link =
        runCatching { Link.valueOf(raw ?: Link.BLE.name) }.getOrDefault(Link.BLE)

    /**
     * 手势开关。默认开。
     *
     * 刻意**不放进 [Config]**：Config 是"连哪台 PC"的连接参数，会被设置页的
     * `save()` 整体覆盖；把手势开关混进去，改一次 IP 就会顺带把它重置。
     * 它是独立的一把开关，读写也独立。
     */
    fun gestureOn(ctx: Context): Boolean = sp(ctx).getBoolean(K_GESTURE, true)

    fun setGestureOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_GESTURE, on).apply()
    }

    /**
     * 用户的**连接意图**（主页「连接 / 断开」按钮）。默认 true。
     *
     * 这是"要不要工作"的总闸，和 [gestureOn]（手势这一项功能本身的开关）不是一个东西：
     * - false ⇒ 前台服务停、链路断、手势停，**且不允许任何路径把它偷偷拉起来**；
     * - true  ⇒ 服务应该活着（掉线按退避重连）。
     *
     * 必须持久化：否则用户点完断开、App 一重启又自己连上，"断开"就没有意义了。
     * 默认 true 是为了**保持升级前的行为**（装上即自动连）。
     */
    fun linkEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_LINK_ENABLED, true)

    fun setLinkEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_LINK_ENABLED, on).apply()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(ctx: Context, cfg: Config) {
        sp(ctx)
            .edit()
            .putString(K_HOST, cfg.host.trim())
            .putInt(K_PORT, cfg.port)
            .putString(K_PIN, cfg.pin.trim())
            .putBoolean(K_KEEP, cfg.keepAlive)
            .putString(K_LINK, cfg.link.name)
            .apply()
    }
}
