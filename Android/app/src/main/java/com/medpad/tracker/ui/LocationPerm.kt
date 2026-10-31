package com.medpad.tracker.ui

/**
 * mission 12 / item 12 — the "Allow all the time" permission-flow logic.
 *
 * PURE (no Context, no Android framework) so every rule is JVM-testable.
 * The ACTUAL permission checks live in the activity; this object only
 * classifies the facts and picks the next honest step:
 *
 *  - NONE          — no location permission at all → the standard runtime
 *                    flow (fine/coarse) comes FIRST; background can never
 *                    be granted before the foreground grants (OS rule).
 *  - WHILE_IN_USE  — fine/coarse granted, background not → tracking works
 *                    only while the app is visible; the guided flow offers
 *                    "Allow all the time".
 *  - ALL_THE_TIME  — background granted → continuous tracking, banner hidden.
 *
 * The direct runtime dialog for ACCESS_BACKGROUND_LOCATION (asked ALONE,
 * after the foreground grants) is only honoured on API 29–30; from API 31
 * the system auto-denies it, so the ONLY honest path is this app's own
 * settings page (ACTION_APPLICATION_DETAILS_SETTINGS) — exactly what
 * [DirectDialog.DIALOG] vs [DirectDialog.SETTINGS_ONLY] encodes.
 */
object LocationPerm {

    enum class State { NONE, WHILE_IN_USE, ALL_THE_TIME }

    enum class DirectDialog { DIALOG, SETTINGS_ONLY }

    fun state(foregroundGranted: Boolean, backgroundGranted: Boolean): State = when {
        backgroundGranted -> State.ALL_THE_TIME
        foregroundGranted -> State.WHILE_IN_USE
        else -> State.NONE
    }

    /** Background location exists from API 29 (Android 10) — below it the
     *  "while-in-use" foreground service IS the background mechanism and
     *  the whole flow is a no-op. */
    fun flowAvailable(sdkInt: Int): Boolean = sdkInt >= 29

    /** API 29–30: the runtime dialog can still grant background directly
     *  (when asked alone, after the foreground grants). API 31+: the system
     *  auto-denies background dialogs — settings is the only honest path. */
    fun directDialog(sdkInt: Int): DirectDialog =
        if (sdkInt in 29..30) DirectDialog.DIALOG else DirectDialog.SETTINGS_ONLY
}
