package io.wristdeck.net

import android.content.Context
import io.wristdeck.util.Prefs

/**
 * 进程内共享的客户端实例：前台 Service 负责持有并维持连接，
 * Activity 只注册/注销回调，退出界面后连接不中断。
 */
object BridgeHolder {

    @Volatile
    private var client: BridgeClient? = null

    fun get(ctx: Context): BridgeClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: BridgeClient(Prefs.load(ctx.applicationContext)).also { client = it }
        }
    }

    fun restart(ctx: Context) {
        synchronized(this) {
            client?.stop()
            client = BridgeClient(Prefs.load(ctx.applicationContext)).also { it.start() }
        }
    }
}
