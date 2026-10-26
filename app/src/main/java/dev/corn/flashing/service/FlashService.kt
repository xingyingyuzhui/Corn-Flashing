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

/**
 * Runs one board per start, as a short service. A batch starts it again for each board, so no
 * single start comes near the system's ~3 minute limit. Real USB sessions will use connectedDevice.
 */
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
        val id = intent?.getStringExtra(EXTRA_JOB_ID)
        // Always enter the foreground promptly; Android crashes the app if startForeground is skipped.
        promote(idle = id == null || active?.isActive == true)
        if (id == null || active?.isActive == true) {
            if (active?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        active = scope.launch {
            try { repository.runJob(id) }
            finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(idle: Boolean) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "烧录任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 0, Intent(this, FlashService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_corn)
            .setContentTitle(if (idle) "Corn Flashing" else "Corn Flashing · 模拟烧录")
            .setContentText(if (idle) "任务服务已启动" else "正在演示烧录流程，不操作真实芯片")
            .setContentIntent(open).setOngoing(!idle)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(1, notification)
        }
    }

    /** shortService time limit reached (API 34). Stopping records the board as cancelled, never as succeeded. */
    override fun onTimeout(startId: Int) { active?.cancel() }

    /** Same limit, API 35+ signature. */
    override fun onTimeout(startId: Int, fgsType: Int) { active?.cancel() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_CANCEL = "dev.corn.flashing.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL = "flashing_tasks"
    }
}
