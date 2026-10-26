package dev.corn.flashing.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Small persisted UI settings (screen 10 and workbench toggles), backed by SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        val state = mutableStateOf(sp.getBoolean(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value; sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun choice(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        val state = mutableStateOf(sp.getInt(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            state.value = value; sp.edit().putInt(key, value).apply()
        }
    }

    /** Simulated programmer: without it and without a real probe the workbench shows screen 17. */
    var simulator by flag("simulator", false)
    var injectFaults by flag("injectFaults", false)
    var autoNextBoard by flag("autoNext", true)
    var resetAfter by flag("resetAfter", true)
    var successTone by flag("tone", true)
    var vibrateOnFail by flag("vibrate", true)
    var keepScreenOn by flag("screenOn", false)
    var eraseMode by choice("erase", 0)
    var verifyMode by choice("verify", 0)
    var preset by choice("preset", 0)

    init {
        // Legacy index 1 was labelled CRC32 but always performed full readback.
        // Keep that protection when migrating to the two-option UI; skip remains index 2.
        val normalized = VerificationPreferences.normalize(verifyMode)
        if (verifyMode != normalized) verifyMode = normalized
    }
}
