package dev.corn.flashing

import android.app.Application
import androidx.room.Room
import dev.corn.flashing.storage.FlashDatabase
import dev.corn.flashing.storage.FlashRepository
import dev.corn.flashing.usb.UsbProbeMonitor

class CornApplication : Application() {
    lateinit var repository: FlashRepository
        private set
    lateinit var usb: UsbProbeMonitor
        private set
    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, FlashDatabase::class.java, "corn-flashing.db").build()
        repository = FlashRepository(this, database.dao())
        usb = UsbProbeMonitor(this)
    }
}
