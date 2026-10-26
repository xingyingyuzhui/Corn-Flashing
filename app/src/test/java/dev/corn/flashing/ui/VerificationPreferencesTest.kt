package dev.corn.flashing.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationPreferencesTest {
    @Test fun legacyCrcAndUnknownSettingsRemainVerified() {
        for (stored in listOf(0, 1, -1, 3, Int.MAX_VALUE)) {
            assertEquals(0, VerificationPreferences.normalize(stored))
            assertEquals(0, VerificationPreferences.uiIndex(stored))
        }
    }

    @Test fun existingSkipSettingKeepsItsPersistedValue() {
        assertEquals(2, VerificationPreferences.normalize(2))
        assertEquals(1, VerificationPreferences.uiIndex(2))
    }

    @Test fun selectingReadbackNeverStoresTheLegacySkipIndex() {
        assertEquals(0, VerificationPreferences.storedValue(0))
        assertEquals(2, VerificationPreferences.storedValue(1))
        assertEquals(0, VerificationPreferences.storedValue(99))
    }
}
