package io.wristdeck.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.wristdeck.R

/**
 * 探针专用的最小前台服务。刻意不复用 [BridgeService]：
 *  BridgeService 会连 PC 并自己占一把 30 分钟的唤醒锁，会把"唤醒锁续期是否有效"这个
 *  观测项污染掉（分不清到底是我们的续期生效，还是它那把锁在托着）。这里只做 startForeground，
 *  用来单独验证一件事：**同一个 App 里有前台服务时，息屏/后台还能不能收到 continuous 传感器**。
 */
class ProbeService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, build())
        Log.i(TAG, "PROBE fgs on")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "PROBE fgs off")
        stopForeground(true)
        super.onDestroy()
    }

    private fun build(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "手势探针", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手势探针")
            .setContentText("IMU 采样验证中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WristDeck"
        private const val CHANNEL_ID = "wristdeck.probe"
        private const val NOTIFICATION_ID = 2

        fun start(ctx: Context) {
            val i = Intent(ctx, ProbeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ProbeService::class.java))
        }
    }
}
