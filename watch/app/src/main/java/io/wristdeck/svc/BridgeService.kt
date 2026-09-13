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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val client = BridgeHolder.get(this)
        if (client.state == BridgeClient.State.DISCONNECTED) client.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (!Prefs.load(this).keepAlive) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristDeck:link")
            ?.apply { acquire(WAKE_LOCK_TIMEOUT) }

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
    }
}
