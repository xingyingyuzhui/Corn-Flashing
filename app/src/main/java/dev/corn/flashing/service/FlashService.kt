package dev.corn.flashing.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import dev.corn.flashing.CornApplication
import dev.corn.flashing.MainActivity
import dev.corn.flashing.R
import dev.corn.flashing.usb.StLinkHardwareBackend
import kotlinx.coroutines.*

/** Simulations are short services; real USB jobs run as connected-device services. */
class FlashService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null
    private var activeSimulation = true
    private var activeStartId: Int? = null
    private var latestStartId = 0
    private val app get() = application as CornApplication
    private val repository get() = app.repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            active?.cancel()
            if (active == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // A repeated start must not replace a real job's service type or notification.
        if (active?.isActive == true) {
            promote(idle = false, simulator = activeSimulation)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_JOB_ID)
        val simulator = id?.let(repository::pendingUsesSimulator)
        if (id == null || simulator == null) {
            // Satisfy startForegroundService promptly even for stale/invalid launches.
            promote(idle = true, simulator = true)
            finishStart(startId)
            return START_NOT_STICKY
        }
        if (!simulator && !hasAuthorizedProbe()) {
            activeSimulation = true
            promote(idle = true, simulator = true)
            activeStartId = startId
            active = scope.launch {
                try { repository.startFailed("未找到已授权的 ST-LINK，请在设备页授权后再试") }
                finally { finishStart(startId) }
            }
            return START_NOT_STICKY
        }
        activeSimulation = simulator
        try {
            promote(idle = false, simulator = simulator)
        } catch (error: SecurityException) {
            // Permissions can be revoked between the USB check and foreground promotion.
            activeSimulation = true
            promote(idle = true, simulator = true)
            activeStartId = startId
            active = scope.launch {
                try { repository.startFailed("无法启动 USB 任务服务：${error.message}") }
                finally { finishStart(startId) }
            }
            return START_NOT_STICKY
        }
        activeStartId = startId
        active = scope.launch {
            try { repository.runJob(id) }
            finally { finishStart(startId) }
        }
        return START_NOT_STICKY
    }

    private fun hasAuthorizedProbe(): Boolean {
        app.usb.refresh()
        val manager = getSystemService(UsbManager::class.java)
        return StLinkHardwareBackend.findAuthorized(manager, app.usb.devices.value) != null
    }

    private fun finishStart(startId: Int) {
        // An older job's finally must not stop a more recently started service task.
        if (activeStartId != null && activeStartId != startId) return
        activeStartId = null
        // Duplicate starts and cancellation have newer start IDs but still belong to this run.
        if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun promote(idle: Boolean, simulator: Boolean) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "烧录任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 0, Intent(this, FlashService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_corn)
            .setContentTitle(when { idle -> "Corn Flashing"; simulator -> "Corn Flashing · 模拟烧录"; else -> "Corn Flashing · ST-LINK 烧录" })
            .setContentText(when { idle -> "任务服务已启动"; simulator -> "正在演示烧录流程，不操作真实芯片"; else -> "正在处理真实控制板，请勿断开 USB 或目标供电" })
            .setContentIntent(open).setOngoing(!idle)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (simulator) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }

    private fun timeout(startId: Int) {
        if (!activeSimulation || startId != activeStartId && startId != latestStartId) return
        active?.cancel()
        // Stop promptly; repository.runJob records cancellation in its NonCancellable cleanup.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    override fun onTimeout(startId: Int) = timeout(startId)
    override fun onTimeout(startId: Int, fgsType: Int) = timeout(startId)

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_CANCEL = "dev.corn.flashing.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL = "flashing_tasks"
    }
}
