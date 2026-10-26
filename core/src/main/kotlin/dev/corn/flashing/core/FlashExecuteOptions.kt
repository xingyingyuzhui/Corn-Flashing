package dev.corn.flashing.core

/**
 * Runtime choices for a flash job (from UI prefs).
 * [eraseMode]: 0 = only planned pages, 1 = every page in profile Flash (still skips reserved via planner pages).
 * [verifyMode]: 0 = full readback, 1 = legacy alias for full readback, 2 = skip verify (not recommended).
 */
data class FlashExecuteOptions(
    val resetAfter: Boolean = true,
    val eraseMode: Int = 0,
    val verifyMode: Int = 0,
)
