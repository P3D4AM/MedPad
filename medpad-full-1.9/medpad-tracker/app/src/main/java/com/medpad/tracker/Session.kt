package com.medpad.tracker

import android.content.Context

/** Simple session/prefs store (server URL + token + employee info). */
object Session {
    private const val PREFS = "medpad_session"

    var serverUrl: String
        get() = prefs()?.getString(KEY_URL, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_URL, v.trimEnd('/'))?.apply() ?: Unit

    var token: String
        get() = prefs()?.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_TOKEN, v)?.apply() ?: Unit

    var employeeId: String
        get() = prefs()?.getString(KEY_EMP_ID, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_EMP_ID, v)?.apply() ?: Unit

    var employeeName: String
        get() = prefs()?.getString(KEY_EMP_NAME, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_EMP_NAME, v)?.apply() ?: Unit

    var panelVersion: String
        get() = prefs()?.getString(KEY_PANEL_VER, "") ?: ""
        set(v) = prefs()?.edit()?.putString(KEY_PANEL_VER, v)?.apply() ?: Unit

    val isLoggedIn: Boolean get() = token.isNotEmpty() && serverUrl.isNotEmpty()

    fun clear(context: Context) {
        prefs(context)?.edit()?.clear()?.apply()
    }

    private var ctx: Context? = null
    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs(context: Context? = null) =
        (context ?: ctx)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMP_ID = "employee_id"
    private const val KEY_EMP_NAME = "employee_name"
    private const val KEY_PANEL_VER = "panel_version"
}
