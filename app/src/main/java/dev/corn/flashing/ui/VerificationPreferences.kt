package dev.corn.flashing.ui

/** Persisted indices stay stable: legacy CRC32 (1) means full readback, skip stays 2. */
internal object VerificationPreferences {
    fun normalize(stored: Int): Int = if (stored == 2) 2 else 0
    fun uiIndex(stored: Int): Int = if (normalize(stored) == 2) 1 else 0
    fun storedValue(uiIndex: Int): Int = if (uiIndex == 1) 2 else 0
}
