package dev.corn.flashing

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.room.Room
import dev.corn.flashing.service.FlashService
import dev.corn.flashing.storage.BatchController
import dev.corn.flashing.storage.FlashDatabase
import dev.corn.flashing.storage.FlashRepository
import dev.corn.flashing.storage.JobLauncher
import dev.corn.flashing.usb.StLinkHardwareBackend
import dev.corn.flashing.usb.UsbProbeMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CornApplication : Application() {
    lateinit var reservedRegions: dev.corn.flashing.storage.ReservedRegionStore
        private set
    lateinit var algorithms: dev.corn.flashing.pack.AlgorithmLibrary
        private set
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
        android.util.Log.i("CornUsb", dev.corn.flashing.usb.NativeUsbFs.availabilityDescription)
        registerActivityLifecycleCallbacks(ForegroundTracker())
        val database = Room.databaseBuilder(this, FlashDatabase::class.java, "corn-flashing.db").build()
        usb = UsbProbeMonitor(this)
        val usbManager = getSystemService(UsbManager::class.java)
        reservedRegions = dev.corn.flashing.storage.ReservedRegionStore(this)
        algorithms = dev.corn.flashing.pack.AlgorithmLibrary(this)
        repository = FlashRepository(this, database.dao(), targetRegistry = { algorithms.snapshot().targets },
            reservedPolicies = { reservedRegions.snapshot() },
            reservedPreview = { reservedRegions.state.value.policies }) {
            val device = StLinkHardwareBackend.findAuthorized(usbManager, usb.devices.value) ?: return@FlashRepository null
            val snapshot = algorithms.snapshot()
            StLinkHardwareBackend(usbManager, device, snapshot.targets, snapshot.algorithms)
        }
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
        batches = BatchController(
            this, database.dao(), repository, launcher, foreground,
            cancelRunning = {
                startService(Intent(this, FlashService::class.java).setAction(FlashService.ACTION_CANCEL))
            },
        )
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
