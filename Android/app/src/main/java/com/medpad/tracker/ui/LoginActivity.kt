package com.medpad.tracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Login — v1.7.0: guest mode REMOVED. Server URL + username + password only.
 *
 * v1.9.13-dev.2 (user report #2 — "the app cannot connect to the server"):
 * the old single generic toast ("check address and password") made network
 * problems, wrong credentials and malformed URLs indistinguishable — the #1
 * support complaint. Now every failure gets a SPECIFIC, actionable Persian
 * message in an inline error box:
 *  - UnknownHost / Connect refused / timeout / SSL  → network guidance
 *    (LAN URL format, same Wi-Fi, server running, Windows firewall allow)
 *  - HTTP 401  → credentials + the fact that the APP logs in with an
 *    EMPLOYEE account created in the panel (not the panel admin)
 *  - HTTP 429  → rate-limit lockout (wait ~15 minutes)
 *  - JSON/HTML garbage → the URL probably misses the port / is not MedPad
 */
class LoginActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.isLoggedIn) {
            goMain()
            return
        }
        setContentView(R.layout.activity_login)

        val url = findViewById<EditText>(R.id.edServer)
        val user = findViewById<EditText>(R.id.edUsername)
        val pass = findViewById<EditText>(R.id.edPassword)
        val btn = findViewById<Button>(R.id.btnLogin)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val errBox = findViewById<TextView>(R.id.tvLoginError)
        val chkRemember = findViewById<CheckBox>(R.id.chkRemember)

        url.setText(Session.serverUrl)
        // package 10 — remembered username (explicit user opt-in; the
        // password is NEVER stored — auto-login is token-based)
        chkRemember.isChecked = Session.rememberUsername
        if (Session.rememberUsername) user.setText(Session.rememberedUsername)

        btn.setOnClickListener {
            val sUrl = url.text.toString().trim()
            val sUser = user.text.toString().trim()
            val sPass = pass.text.toString()
            errBox.visibility = View.GONE
            if (!sUrl.startsWith("http")) {
                url.error = getString(R.string.server_url_hint)
                return@setOnClickListener
            }
            if (sUser.isEmpty() || sPass.isEmpty()) {
                Toast.makeText(this, R.string.login_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            scope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        ApiClient.login(sUrl, sUser, sPass)
                    }
                    if (resp.optBoolean("ok")) {
                        Session.serverUrl = sUrl
                        Session.token = resp.getString("token")
                        // package 10 — persist the username choice
                        Session.rememberUsername = chkRemember.isChecked
                        Session.rememberedUsername = if (chkRemember.isChecked) sUser else ""
                        val emp = resp.getJSONObject("employee")
                        Session.employeeId = emp.optString("id")
                        Session.employeeName = emp.optString("name")
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
