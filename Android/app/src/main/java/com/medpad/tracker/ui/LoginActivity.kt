package com.medpad.tracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.util.NetSecurity
import com.medpad.tracker.util.ServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Login — v1.7.0: guest mode REMOVED. Server URL + username + password only.
 *
 * v1.9.13-dev.2 (user report #2 — "the app cannot connect to the server"):
 * every failure gets a SPECIFIC, actionable Persian message in an inline
 * error box (network vs credentials vs rate-limit vs not-MedPad).
 *
 * mission 12 / item 3 (Nov 2026): after the FIRST successful login the
 * server address is remembered independently of the session (see
 * Session.lastServerUrl) and SURVIVES logout. The field collapses into a
 * summary row — the rep logs in with username + password only — and
 * «تغییر آدرس سرور» brings the input back for when the panel moves.
 *
 * mission 12 / item 4: the address field is forgiving in exactly one
 * direction — it starts pre-filled with `http://`, a bare host/IP is
 * auto-completed with the default scheme, an explicit `https://` is
 * preserved, doubled scheme prefixes are collapsed and pasted bidi/zero-
 * width characters are stripped (ServerUrl.normalize). Only genuinely
 * unusable input (empty, no host, unsupported scheme) is rejected with an
 * honest message.
 */
class LoginActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    /** P09: set when the user explicitly acknowledged the public-cleartext
     *  warning for the address currently being attempted. */
    private var cleartextAck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.isLoggedIn) {
            goMain()
            return
        }
        setContentView(R.layout.activity_login)

        val url = findViewById<EditText>(R.id.edServer)
        val boxServer = findViewById<TextInputLayout>(R.id.boxServer)
        val boxSaved = findViewById<LinearLayout>(R.id.boxSavedServer)
        val tvSaved = findViewById<TextView>(R.id.tvSavedServer)
        val btnChangeServer = findViewById<Button>(R.id.btnChangeServer)
        val user = findViewById<EditText>(R.id.edUsername)
        val pass = findViewById<EditText>(R.id.edPassword)
        val btn = findViewById<Button>(R.id.btnLogin)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val errBox = findViewById<TextView>(R.id.tvLoginError)
        val chkRemember = findViewById<CheckBox>(R.id.chkRemember)

        // ── mission 12 / item 3 — remembered address: collapse the field ──
        // The remembered address survives logout by design; the FIRST login
        // on a device (no remembered address) shows the input, pre-filled
        // with the default scheme (item 4).
        val saved = Session.lastServerUrl
        if (saved.isNotEmpty()) {
            url.setText(saved)
            tvSaved.text = getString(R.string.login_saved_server, saved)
            boxSaved.visibility = View.VISIBLE
            boxServer.visibility = View.GONE
        } else {
            url.setText(ServerUrl.DEFAULT_SCHEME)
        }
        btnChangeServer.setOnClickListener {
            boxSaved.visibility = View.GONE
            boxServer.visibility = View.VISIBLE
            url.setText(if (saved.isNotEmpty()) saved else ServerUrl.DEFAULT_SCHEME)
            url.requestFocus()
            url.setSelection(url.text?.length ?: 0)
        }

        // package 10 — remembered username (explicit user opt-in; the
        // password is NEVER stored — auto-login is token-based)
        chkRemember.isChecked = Session.rememberUsername
        if (Session.rememberUsername) user.setText(Session.rememberedUsername)

        btn.setOnClickListener {
            val rawUrl = url.text.toString().trim()
            val sUser = user.text.toString().trim()
            val sPass = pass.text.toString()
            errBox.visibility = View.GONE
            // ── mission 12 / item 4 — normalize instead of reject ──
            // A bare host gets the default http:// prefix, an explicit
            // https:// is preserved, doubled schemes collapse, pasted bidi
            // junk disappears. Only unusable input fails, with a specific
            // Persian message.
            val sUrl = ServerUrl.normalize(rawUrl) ?: run {
                val msg = when {
                    rawUrl.isBlank() -> getString(R.string.err_server_url_empty)
                    ServerUrl.hasUnsupportedScheme(rawUrl) -> getString(R.string.err_server_url_invalid)
                    else -> getString(R.string.err_server_url_no_host)
                }
                fail(msg)
                return@setOnClickListener
            }
            if (sUser.isEmpty() || sPass.isEmpty()) {
                Toast.makeText(this, R.string.login_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // P09 (permanent protocol): honest one-time warning for a PUBLIC
            // cleartext address — username/password/token/voice would travel
            // unencrypted and be sniffable/tamperable on a hostile network.
            // Private/LAN addresses and HTTPS never warn, so existing LAN
            // installs are completely unaffected.
            if (NetSecurity.needsCleartextWarning(sUrl) && !cleartextAck) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.cleartext_warning_title)
                    .setMessage(R.string.cleartext_warning_body)
                    .setPositiveButton(R.string.continue_anyway) { _, _ ->
                        cleartextAck = true
                        btn.performClick() // re-run with the acknowledgment
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }
            cleartextAck = false
            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            scope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        ApiClient.login(sUrl, sUser, sPass)
                    }
                    if (resp.optBoolean("ok")) {
                        Session.serverUrl = sUrl
                        // mission 12 / item 3 — remember the address for the
                        // NEXT login (survives logout; independent of the
                        // session token)
                        Session.lastServerUrl = sUrl
                        Session.token = resp.getString("token")
                        // package 10 — persist the username choice
                        Session.rememberUsername = chkRemember.isChecked
                        Session.rememberedUsername = if (chkRemember.isChecked) sUser else ""
                        val emp = resp.getJSONObject("employee")
                        Session.employeeId = emp.optString("id")
                        Session.employeeName = emp.optString("name")
                        // mission item 2 (Oct 31): first name for the greeting.
                        // JsonSafe keeps an old panel's ABSENT field (and the
                        // JSON-null answer of a legacy employee) from leaking
                        // the literal string "null" into the UI.
                        Session.employeeFirstName =
                            com.medpad.tracker.util.JsonSafe.text(emp, "firstName")
                        // best-effort: fetch panel version for old-panel warning
                        try {
                            val cfg = withContext(Dispatchers.IO) { ApiClient.config() }
                            Session.panelVersion = cfg.optString("panelVersion", "")
                        } catch (_: Exception) { /* ignore */ }
                        goMain()
                    } else {
                        fail(getString(R.string.err_wrong_credentials))
                    }
                } catch (e: Exception) {
                    fail(describe(e))
                }
            }
        }
    }

    /** Map a login exception to a specific, actionable Persian message. */
    private fun describe(e: Exception): String {
        val net = e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.SocketException ||
            e is javax.net.ssl.SSLException ||
            (e.cause is java.net.UnknownHostException) ||
            (e.cause is java.net.ConnectException) ||
            (e.cause is java.net.SocketTimeoutException)
        return when {
            e is ApiClient.HttpException && e.code == 401 ->
                getString(R.string.err_wrong_credentials)
            e is ApiClient.HttpException && e.code == 429 ->
                getString(R.string.err_rate_limited)
            e is ApiClient.HttpException ->
                getString(R.string.err_server_rejected, e.code, e.message ?: "")
            net ->
                getString(R.string.err_network_unreachable, e.javaClass.simpleName + ": " + (e.message ?: ""))
            e is JSONException ->
                getString(R.string.err_bad_response)
            else ->
                getString(R.string.err_login_generic, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun fail(message: String) {
        val errBox = findViewById<TextView>(R.id.tvLoginError)
        errBox.text = message
        errBox.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnLogin).isEnabled = true
        findViewById<ProgressBar>(R.id.progress).visibility = View.GONE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
