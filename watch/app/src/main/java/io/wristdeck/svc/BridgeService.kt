package io.wristdeck.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.wristdeck.R
import io.wristdeck.gesture.GestureController
import io.wristdeck.model.Link
import io.wristdeck.net.BridgeClient
import io.wristdeck.net.BridgeHolder
import io.wristdeck.util.Prefs

class BridgeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        BridgeHolder.get(this).start()
        // 手势跟着连接服务走：这个 Service 已经是常驻前台服务，
        // 再单开一个组件只会多一份保活负担，也更容易被厂商省电策略掐掉。
        if (Prefs.gestureOn(this)) GestureController.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val client = BridgeHolder.get(this)
        if (client.state == BridgeClient.State.DISCONNECTED) client.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GestureController.stop()
        /*
         * 收掉客户端，而不只是停掉自己。
         *
         * 主页「断开」走的是 stopService()，只会触发 onDestroy；如果这里不显式停客户端，
         * BLE 的 GATT Server / 广播会留在进程里（网关照样连得上），
         * 表现就是"点了断开但 Mac 那边还连着、手表还在耗电"。
         * [BridgeHolder.stop] 是幂等的，系统回收服务时重复调用也无害。
         */
        BridgeHolder.stop(this)
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val cfg = Prefs.load(this)
        if (!cfg.keepAlive) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristDeck:link")
            ?.apply { acquire(WAKE_LOCK_TIMEOUT) }

        // Wi-Fi 锁只在 Wi-Fi 链路下需要：它是防 Doze 掐断 TCP 长连接用的。
        // BLE 模式下还攥着 WIFI_MODE_FULL_LOW_LATENCY，等于把 Wi-Fi 芯片按低延迟高性能态养着，
        // 与"换 BLE 也就是为了更省电"这个目标正好相反。CPU 那边有 wakeLock 就够了
        // （BLE 回调也靠它才跑得起来）。
        if (cfg.link != Link.WIFI) return

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = if (Build.VERSION.SDK_INT >= 29) {
            wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WristDeck:link")
        } else {
            @Suppress("DEPRECATION")
            wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WristDeck:link")
        }
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WristDeck 连接",
                NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WristDeck")
            .setContentText("保持与 PC 的连接")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wristdeck"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT = 30 * 60 * 1000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /**
         * 只在用户处于"已连接"意图时才拉起服务。
         *
         * 所有**顺手**的启动路径（主页 onResume/onCreate、设置页保存、手势开关）都走这个，
         * 只有主页那个「连接」按钮才直接调 [start] —— 否则用户点了断开，
         * 随便点两下界面就又连上了，断开状态根本守不住。
         */
        fun startIfEnabled(ctx: Context) {
            if (Prefs.linkEnabled(ctx)) start(ctx)
        }
    }
}
