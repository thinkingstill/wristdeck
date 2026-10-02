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
            client ?: newClient(ctx).also { client = it }
        }
    }

    /**
     * 换一套连接参数重来。
     *
     * ⚠️ 只在 [Prefs.linkEnabled] 为真时才自动连上：否则用户在主页点了"断开"，
     * 去设置页动一下参数就会被悄悄连回来，"断开"变成假的。
     */
    fun restart(ctx: Context) {
        synchronized(this) {
            client?.stop()
            val app = ctx.applicationContext
            client = newClient(ctx).also { if (Prefs.linkEnabled(app)) it.start() }
        }
    }

    /**
     * 彻底停下（主页的「断开」）。幂等。
     *
     * [BridgeClient.stop] 做三件事：置 `stopped`（此后 [BridgeClient.connect] 直接返回）、
     * 撤销所有排程中的重连、关掉传输。BLE 那条路会连带拆 GATT Server 与广播，
     * 所以 Mac 网关会看到掉线——这正是"断开"该有的样子。
     *
     * 刻意**不把 client 置 null**：界面可能还挂着回调，保留一个停在 DISCONNECTED 的
     * 实例比制造一个"取出来是新对象、状态还是旧的"更不容易出错。
     */
    fun stop(ctx: Context) {
        synchronized(this) {
            client?.stop()
        }
    }

    /**
     * 一律传 `applicationContext`：BLE 那条链路要拿 `BluetoothManager`，持有 Activity 的
     * Context 会把整页泄漏在一个常驻对象里。
     */
    private fun newClient(ctx: Context): BridgeClient {
        val app = ctx.applicationContext
        return BridgeClient(app, Prefs.load(app))
    }
}
