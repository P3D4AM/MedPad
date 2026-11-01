package com.medpad.tracker.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Customer-360 / UI-UX phase 1 — the app theme preference.
 *
 * Three states (light / dark / system), persisted in SharedPreferences so
 * the choice survives close/reopen. Applying = one AppCompatDelegate call:
 * every activity (DayNight parent theme) + every values-night color flips.
 * The preference is read once at app start (TrackerApp.onCreate) and after
 * every change (AccountDialogs' theme row).
 */
object ThemePrefs {
    private const val PREFS = "medpad_theme"
    private const val KEY_MODE = "mode"

    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_SYSTEM = "system"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM

    /** store + apply immediately. Returns the stored mode. */
    fun setMode(context: Context, mode: String): String {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
        apply(context)
        return mode
    }

    /** map the stored preference onto AppCompatDelegate's night modes. */
    fun apply(context: Context) {
        when (mode(context)) {
            MODE_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            MODE_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
