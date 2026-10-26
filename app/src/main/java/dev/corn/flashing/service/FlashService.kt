package dev.corn.flashing.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.corn.flashing.CornApplication
import dev.corn.flashing.MainActivity
import dev.corn.flashing.R
import kotlinx.coroutines.*

/** Short user-started simulation only. Real USB sessions will use connectedDevice. */
class FlashService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null
    private val repository get() = (application as CornApplication).repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            active?.cancel()
            if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        if (active?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra(EXTRA_JOB_ID) ?: run { stopSelf(); return START_NOT_STICKY }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "烧录任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 0, Intent(this, FlashService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_corn)
            .setContentTitle("Corn Flashing · 模拟任务")
            .setContentText("正在演示烧录流程，不操作真实芯片")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(1, notification)
        }
        active = scope.launch {
            try { repository.runDemo(id) }
            finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_CANCEL = "dev.corn.flashing.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL = "flashing_tasks"
    }
}
