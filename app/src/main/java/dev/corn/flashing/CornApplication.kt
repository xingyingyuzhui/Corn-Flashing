package dev.corn.flashing

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.room.Room
import dev.corn.flashing.service.FlashService
import dev.corn.flashing.storage.BatchController
import dev.corn.flashing.storage.FlashDatabase
import dev.corn.flashing.storage.FlashRepository
import dev.corn.flashing.storage.JobLauncher
import dev.corn.flashing.usb.UsbProbeMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CornApplication : Application() {
    lateinit var repository: FlashRepository
        private set
    lateinit var usb: UsbProbeMonitor
        private set
    lateinit var batches: BatchController
        private set
    lateinit var launcher: JobLauncher
        private set
    private val _foreground = MutableStateFlow(false)
    /** True while any activity is started; batch boards only start then. */
    val foreground = _foreground.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundTracker())
        val database = Room.databaseBuilder(this, FlashDatabase::class.java, "corn-flashing.db").build()
        repository = FlashRepository(this, database.dao())
        usb = UsbProbeMonitor(this)
        launcher = JobLauncher { id ->
            try {
                ContextCompat.startForegroundService(this, Intent(this, FlashService::class.java).putExtra(FlashService.EXTRA_JOB_ID, id))
                true
            } catch (_: IllegalStateException) {
                false // ForegroundServiceStartNotAllowedException and the pre-31 background-start error
            } catch (_: SecurityException) {
                false
            }
        }
        batches = BatchController(this, database.dao(), repository, launcher, foreground)
    }

    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) { started++; _foreground.value = true }
        override fun onActivityStopped(activity: Activity) { started--; _foreground.value = started > 0 }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
