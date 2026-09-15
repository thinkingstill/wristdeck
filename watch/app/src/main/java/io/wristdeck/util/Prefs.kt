package io.wristdeck.util

import android.content.Context
import io.wristdeck.model.Config

object Prefs {
    private const val FILE = "wristdeck"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "pin"
    private const val K_KEEP = "keep_alive"
    private const val K_GESTURE = "gesture_on"

    fun load(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Config(
            host = sp.getString(K_HOST, "") ?: "",
            port = sp.getInt(K_PORT, Config.DEFAULT_PORT),
            pin = sp.getString(K_PIN, "") ?: "",
            keepAlive = sp.getBoolean(K_KEEP, true),
        )
    }

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

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(ctx: Context, cfg: Config) {
        sp(ctx)
            .edit()
            .putString(K_HOST, cfg.host.trim())
            .putInt(K_PORT, cfg.port)
            .putString(K_PIN, cfg.pin.trim())
            .putBoolean(K_KEEP, cfg.keepAlive)
            .apply()
    }
}
