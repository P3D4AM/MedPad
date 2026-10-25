package com.medpad.tracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Login — v1.7.0: guest mode REMOVED. Server URL + username + password only.
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

        url.setText(Session.serverUrl)

        btn.setOnClickListener {
            val sUrl = url.text.toString().trim()
            val sUser = user.text.toString().trim()
            val sPass = pass.text.toString()
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
                        fail()
                    }
                } catch (e: Exception) {
                    fail()
                }
            }
        }
    }

    private fun fail() {
        Toast.makeText(this, R.string.login_failed, Toast.LENGTH_LONG).show()
        findViewById<Button>(R.id.btnLogin).isEnabled = true
        findViewById<ProgressBar>(R.id.progress).visibility = View.GONE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
