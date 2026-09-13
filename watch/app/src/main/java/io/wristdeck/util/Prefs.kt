package io.wristdeck.util

import android.content.Context
import io.wristdeck.model.Config

object Prefs {
    private const val FILE = "wristdeck"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "pin"
    private const val K_KEEP = "keep_alive"

    fun load(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Config(
            host = sp.getString(K_HOST, "") ?: "",
            port = sp.getInt(K_PORT, Config.DEFAULT_PORT),
            pin = sp.getString(K_PIN, "") ?: "",
            keepAlive = sp.getBoolean(K_KEEP, true),
        )
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(K_HOST, cfg.host.trim())
            .putInt(K_PORT, cfg.port)
            .putString(K_PIN, cfg.pin.trim())
            .putBoolean(K_KEEP, cfg.keepAlive)
            .apply()
    }
}
