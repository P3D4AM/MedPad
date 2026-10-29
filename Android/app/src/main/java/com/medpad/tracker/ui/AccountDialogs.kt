package com.medpad.tracker.ui

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.medpad.tracker.R
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.location.TrackingService
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.LocationSync
import com.medpad.tracker.util.NetSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * phase 2 / §5 + §6 (dev.4) — the account sheets, opened from the home
 * header's «حساب کاربری» button.
 *
 *  §6 CHANGE PASSWORD — client validation mirrors the server route
 *     (Validators.passwordChange); the in-dialog error box shows the FIRST
 *     complaint, so nothing is sent that the server would reject anyway.
 *     On success the CURRENT device stays logged in — the server revokes
 *     every OTHER session and the honest count is shown in the toast.
 *     A 401 (session expired mid-flow) is reported honestly; the home
 *     screen's G-2 guard then handles the re-login.
 *
 *  §5 CHANGE SERVER AFTER LOGIN — the honest migration path:
 *     1. the new address gets the SAME checks as the login screen
 *        (http/https scheme; public-cleartext gets the one-time P09
 *        informed-consent warning);
 *     2. the address is PROBED with the current token (GET /api/app/config
 *        at the new base URL) BEFORE anything is saved:
 *          200 → the database was migrated with the panel (backup/restore)
 *                and the session SURVIVES — only the stored URL is swapped
 *                (and the panel version cache refreshed); every running
 *                component reads Session.serverUrl per request, so the
 *                whole app switches over instantly;
 *          401 → the address hosts a MedPad panel but does not know this
 *                token — the session is cleared (the tracking service is
 *                stopped first), the NEW address is kept for the login
 *                screen and the user is sent there with an honest message;
 *          other errors → NOTHING is saved; the specific Persian guidance
 *                (the same family as the login screen) explains why.
 */
object AccountDialogs {

    fun show(activity: AppCompatActivity) {
        val items = arrayOf(
            activity.getString(R.string.account_change_password),
            activity.getString(R.string.account_change_server),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.account_title)
            .setItems(items) { _, which ->
                if (which == 0) changePassword(activity) else changeServer(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── §6 — change password ─────────────────────────────────────────

    fun changePassword(activity: AppCompatActivity) {
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val cur = passwordField(activity, R.string.account_current_password)
        val new = passwordField(activity, R.string.account_new_password)
        val conf = passwordField(activity, R.string.account_confirm_password)
        val hint = TextView(activity).apply {
            text = activity.getString(R.string.account_password_hint)
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        val errBox = TextView(activity).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            setTextColor(0xFFB91C1C.toInt())
            visibility = View.GONE
        }
        val progress = ProgressBar(activity).apply { visibility = View.GONE }
        box.addView(cur); box.addView(new); box.addView(conf)
        box.addView(hint); box.addView(errBox); box.addView(progress)

        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.account_change_password)
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dlg.setOnShowListener {
            val saveBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.setOnClickListener {
                errBox.visibility = View.GONE
                val curS = cur.editText?.text?.toString() ?: ""
                val newS = new.editText?.text?.toString() ?: ""
                val confS = conf.editText?.text?.toString() ?: ""
                val err = when (Validators.passwordChange(curS, newS, confS)) {
                    Validators.PwError.EMPTY -> activity.getString(R.string.err_field_required)
                    Validators.PwError.MISMATCH -> activity.getString(R.string.err_confirm_mismatch)
                    Validators.PwError.TOO_SHORT -> activity.getString(R.string.err_password_short)
                    Validators.PwError.SAME_AS_CURRENT -> activity.getString(R.string.err_password_same)
                    null -> null
                }
                if (err != null) {
                    errBox.text = err
                    errBox.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                saveBtn.isEnabled = false
                progress.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            ApiClient.changePassword(curS, newS, confS)
                        }
                        dlg.dismiss()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.account_password_changed, resp.optInt("revokedSessions", 0)),
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (e: Exception) {
                        saveBtn.isEnabled = true
                        progress.visibility = View.GONE
                        errBox.text = describe(activity, e)
                        errBox.visibility = View.VISIBLE
                    }
                }
            }
        }
        dlg.show()
    }

    // ── §5 — change server after login ───────────────────────────────

    fun changeServer(activity: AppCompatActivity) {
        val pad = dp(activity, 16)
        var cleartextAck = false
        var queueLossAck = false
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val current = TextView(activity).apply {
            text = activity.getString(R.string.account_server_current, Session.serverUrl)
            textSize = 13f
        }
        val input = TextInputEditText(activity).apply {
            hint = activity.getString(R.string.account_server_new)
            setText(Session.serverUrl)
            setSingleLine(true)
        }
        val wrap = TextInputLayout(activity).apply { addView(input) }
        val note = TextView(activity).apply {
            text = activity.getString(R.string.account_server_note)
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        val errBox = TextView(activity).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            setTextColor(0xFFB91C1C.toInt())
            visibility = View.GONE
        }
        val progress = ProgressBar(activity).apply { visibility = View.GONE }
        box.addView(current); box.addView(wrap); box.addView(note)
        box.addView(errBox); box.addView(progress)

        val dlg = AlertDialog.Builder(activity)
            .setTitle(R.string.account_change_server)
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dlg.setOnShowListener {
            val saveBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.setOnClickListener {
                errBox.visibility = View.GONE
                val newUrl = input.text?.toString()?.trim() ?: ""
                if (!Validators.isHttpServerUrl(newUrl)) {
                    errBox.text = activity.getString(R.string.err_server_url_invalid)
                    errBox.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val normalized = newUrl.trimEnd('/')
                if (normalized == Session.serverUrl) {
                    errBox.text = activity.getString(R.string.account_server_same)
                    errBox.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                // P09 — same one-time informed-consent warning as the login
                // screen for a PUBLIC cleartext address (token + voice + all
                // app traffic would travel unencrypted to the new server).
                if (NetSecurity.needsCleartextWarning(normalized) && !cleartextAck) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.cleartext_warning_title)
                        .setMessage(R.string.cleartext_warning_body)
                        .setPositiveButton(R.string.continue_anyway) { _, _ ->
                            cleartextAck = true
                            saveBtn.performClick()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@setOnClickListener
                }
                saveBtn.isEnabled = false
                progress.visibility = View.VISIBLE
                CoroutineScope(Dispatchers.Main).launch {
                    // §5 — per-server queue separation, honest loss warning:
                    // unsent items belong to the OLD server's database. If
                    // the new address turns out to host the MIGRATED database
                    // the queue survives (same data, idempotent by localId);
                    // otherwise (the 401 path below) it is DROPPED — the user
                    // must confirm that risk BEFORE anything is saved.
                    val pending = withContext(Dispatchers.IO) { CheckinSync.pendingCount() }
                    if (pending > 0 && !queueLossAck) {
                        saveBtn.isEnabled = true
                        progress.visibility = View.GONE
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.account_server_queue_title)
                            .setMessage(activity.getString(R.string.account_server_queue_warning, pending))
                            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                                queueLossAck = true
                                saveBtn.performClick()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        return@launch
                    }
                    try {
                        val cfg = withContext(Dispatchers.IO) { ApiClient.configAt(normalized) }
                        // 200 — the token is valid on the new server too
                        // (migrated database): keep the session, swap the URL.
                        Session.serverUrl = normalized
                        Session.panelVersion = cfg.optString("panelVersion", "")
                        dlg.dismiss()
                        Toast.makeText(activity, R.string.account_server_changed, Toast.LENGTH_LONG).show()
                    } catch (e: ApiClient.HttpException) {
                        saveBtn.isEnabled = true
                        progress.visibility = View.GONE
                        if (e.code == 401) {
                            // A MedPad panel, but not for this token — the
                            // honest path: stop the old-server service, drop
                            // the old server's offline queues (check-ins just
                            // confirmed above; location pings belong to the
                            // old server's database and can never be uploaded
                            // to the new one), clear the session, keep the
                            // NEW address for the login screen.
                            withContext(Dispatchers.IO) {
                                CheckinSync.clearQueue()
                                LocationSync.clearQueue(activity)
                            }
                            TrackingService.stop(activity)
                            Session.clear(activity)
                            Session.serverUrl = normalized
                            dlg.dismiss()
                            Toast.makeText(activity, R.string.account_server_relogin, Toast.LENGTH_LONG).show()
                            activity.startActivity(Intent(activity, LoginActivity::class.java))
                            activity.finish()
                        } else {
                            errBox.text = describe(activity, e)
                            errBox.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) {
                        saveBtn.isEnabled = true
                        progress.visibility = View.GONE
                        errBox.text = describe(activity, e)
                        errBox.visibility = View.VISIBLE
                    }
                }
            }
        }
        dlg.show()
    }

    // ── shared helpers ───────────────────────────────────────────────

    /** Map an account-sheet failure to a specific, actionable Persian
     *  message — the same classification the login screen uses, minus the
     *  login-only wording. Server 4xx bodies carry Persian text already
     *  (the panel writes them) and are shown verbatim. */
    private fun describe(activity: AppCompatActivity, e: Exception): String {
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
                activity.getString(R.string.session_expired)
            e is ApiClient.HttpException && e.code == 429 ->
                activity.getString(R.string.err_rate_limited)
            e is ApiClient.HttpException ->
                e.message ?: activity.getString(R.string.account_password_change_failed)
            net ->
                activity.getString(R.string.err_network_unreachable, e.javaClass.simpleName)
            else ->
                activity.getString(R.string.err_login_generic, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun passwordField(activity: AppCompatActivity, hintRes: Int): TextInputLayout =
        TextInputLayout(activity).apply {
            hint = activity.getString(hintRes)
            addView(TextInputEditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            })
        }

    private fun dp(activity: AppCompatActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
