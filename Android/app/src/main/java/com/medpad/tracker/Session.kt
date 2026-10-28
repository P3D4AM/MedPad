package com.medpad.tracker

import android.content.Context
import com.medpad.tracker.util.SecureStore

/**
 * Simple session/prefs store (server URL + token + employee info).
 *
 * package 10 (phase 2 / task 7) — SECURITY:
 *  - the app TOKEN is now stored AES/GCM-ENCRYPTED with an AndroidKeyStore
 *    key (see SecureStore); a legacy plaintext token from an older install
 *    is migrated transparently on first read and never left on disk.
 *  - if the Keystore is unavailable (exotic ROMs), the token lives in
 *    memory only — NO plaintext fallback (auto-login degrades honestly).
 *  - auto-login remains TOKEN-based: the password is never stored, and the
 *    "remember username" option keeps only the (non-sensitive) username,
 *    in a SEPARATE prefs file so logout does not wipe the user's choice.
 */
object Session {
    private const val PREFS = "medpad_session"
    private const val LOGIN_PREFS = "medpad_login" // survives logout

    // in-memory mirror for the degraded (no-Keystore) path & fast reads
    private var memToken: String? = null

    var serverUrl: String
        get() = prefs()?.getString(KEY_URL, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_URL, v.trimEnd('/'))?.apply() ?: Unit

    var token: String
        get() = synchronized(this) {
            memToken?.let { return it }
            val ctx = ctx ?: return ""
            val stored = prefs()?.getString(KEY_TOKEN, "") ?: ""
            if (stored.isEmpty()) return ""
            if (SecureStore.isEncrypted(stored)) {
                val plain = SecureStore.decrypt(ctx, stored)
                memToken = plain ?: ""
                // a stored blob that no longer decrypts (keystore reset) is
                // dead weight — drop it so isLoggedIn() reports honestly
                if (plain == null) prefs()?.edit()?.remove(KEY_TOKEN)?.apply()
                return memToken ?: ""
            }
            // legacy plaintext token (pre-package-10 install) → migrate:
            // use it for the current session, re-store encrypted, and if
            // encryption is unavailable REMOVE the plaintext instead of
            // keeping it on disk.
            memToken = stored
            val enc = SecureStore.encrypt(ctx, stored)
            if (enc != null) prefs()?.edit()?.putString(KEY_TOKEN, enc)?.apply()
            else prefs()?.edit()?.remove(KEY_TOKEN)?.apply()
            return stored
        }
        set(v) = synchronized(this) {
            memToken = v
            val ctx = ctx
            val enc = if (v.isEmpty() || ctx == null) null else SecureStore.encrypt(ctx, v)
            when {
                v.isEmpty() -> prefs()?.edit()?.remove(KEY_TOKEN)?.apply()
                enc != null -> prefs()?.edit()?.putString(KEY_TOKEN, enc)?.apply()
                else -> prefs()?.edit()?.remove(KEY_TOKEN)?.apply() // no plaintext, ever
            }
            Unit
        }

    var employeeId: String
        get() = prefs()?.getString(KEY_EMP_ID, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_EMP_ID, v)?.apply() ?: Unit

    var employeeName: String
        get() = prefs()?.getString(KEY_EMP_NAME, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_EMP_NAME, v)?.apply() ?: Unit

    var panelVersion: String
        get() = prefs()?.getString(KEY_PANEL_VER, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_PANEL_VER, v)?.apply() ?: Unit

    /** package 10 — remembered username (non-sensitive; own prefs file so
     *  logout keeps it when the user opted in). */
    var rememberedUsername: String
        get() = loginPrefs()?.getString(KEY_REMEMBER_USER, "") ?: ""
        set(v) = loginPrefs()?.edit()?.putString(KEY_REMEMBER_USER, v)?.apply() ?: Unit

    /** True when the user ticked «نام کاربری را به یاد بسپار» (persisted too,
     *  so the checkbox state survives restarts and logouts). */
    var rememberUsername: Boolean
        get() = loginPrefs()?.getBoolean(KEY_REMEMBER_FLAG, false) ?: false
        set(v) = loginPrefs()?.edit()?.putBoolean(KEY_REMEMBER_FLAG, v)?.apply() ?: Unit

    val isLoggedIn: Boolean get() = token.isNotEmpty() && serverUrl.isNotEmpty()

    fun clear(context: Context) {
        synchronized(this) { memToken = null }
        prefs(context)?.edit()?.clear()?.apply()
        // loginPrefs intentionally NOT cleared — the username choice survives
    }

    private var ctx: Context? = null
    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs(context: Context? = null) =
        (context ?: ctx)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loginPrefs(context: Context? = null) =
        (context ?: ctx)?.getSharedPreferences(LOGIN_PREFS, Context.MODE_PRIVATE)

    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMP_ID = "employee_id"
    private const val KEY_EMP_NAME = "employee_name"
    private const val KEY_PANEL_VER = "panel_version"
    private const val KEY_REMEMBER_USER = "remembered_username"
    private const val KEY_REMEMBER_FLAG = "remember_username_flag"
}
