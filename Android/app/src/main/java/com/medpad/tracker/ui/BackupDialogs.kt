package com.medpad.tracker.ui

import android.app.Activity
import android.content.Intent
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.medpad.tracker.R
import com.medpad.tracker.backup.BackupManager
import com.medpad.tracker.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Backup & Restore + legacy migration (owner mission, Nov 3 2026) — the
 * «پشتیبان‌گیری و بازگردانی» settings section, opened from the account
 * sheet on the home screen.
 *
 * SAF flows (no storage permission needed — the user picks the file):
 *   export JSON   ACTION_CREATE_DOCUMENT  application/json
 *   export .db    ACTION_CREATE_DOCUMENT  application/octet-stream
 *   import JSON   ACTION_OPEN_DOCUMENT    application/json (+ fallbacks)
 *   import legacy ACTION_OPEN_DOCUMENT    any SQLite .db (v4..v14)
 *
 * The pickers use the classic startActivityForResult API on purpose: they
 * are launched from dialogs (registerForActivityResult is only callable
 * during activity initialization); MainActivity.onActivityResult forwards
 * every backup request code here. Results survive activity recreation —
 * the SAF contract delivers them to the new instance.
 *
 * Safety contract (mirrors BackupManager): every restore takes an
 * automatic pre-restore snapshot first; a restore whose snapshot cannot
 * be written is ABORTED with an honest error — never an undo-less import.
 */
object BackupDialogs {

    private const val REQ_EXPORT_JSON = 4241
    private const val REQ_EXPORT_DB = 4242
    private const val REQ_IMPORT_JSON = 4243
    private const val REQ_IMPORT_LEGACY = 4244

    // ── the section dialog (opened from AccountDialogs) ──────────────────

    fun show(activity: AppCompatActivity) {
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val note = TextView(activity).apply {
            text = activity.getString(R.string.backup_section_note)
            textSize = 12f
            setPadding(0, 0, 0, pad / 2)
        }
        box.addView(note)

        fun actionBtn(label: String, onClick: () -> Unit) =
            android.widget.Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                background = null
                setOnClickListener { onClick() }
            }

        box.addView(actionBtn(activity.getString(R.string.backup_export_json)) { launchExportJson(activity) })
        box.addView(actionBtn(activity.getString(R.string.backup_export_db)) { launchExportDb(activity) })
        box.addView(actionBtn(activity.getString(R.string.backup_import_json)) { launchImportJson(activity) })
        box.addView(actionBtn(activity.getString(R.string.backup_import_legacy)) { launchImportLegacy(activity) })
        box.addView(actionBtn(activity.getString(R.string.backup_safety_list)) { showSafetyList(activity) })

        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_section_title)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ── SAF launchers ─────────────────────────────────────────────────────

    private fun launchExportJson(activity: AppCompatActivity) {
        val name = "MedPad-Backup-${fileNameTimestamp()}.json"
        activity.startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, name)
            },
            REQ_EXPORT_JSON,
        )
    }

    private fun launchExportDb(activity: AppCompatActivity) {
        val name = "MedPad-Database-${fileNameTimestamp()}.db"
        activity.startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, name)
            },
            REQ_EXPORT_DB,
        )
    }

    private fun launchImportJson(activity: AppCompatActivity) {
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // file managers disagree about JSON mime types — accept the
                // common three; the manifest check rejects wrong files anyway
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/octet-stream", "text/plain"))
            },
            REQ_IMPORT_JSON,
        )
    }

    private fun launchImportLegacy(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_legacy)
            .setMessage(R.string.backup_import_legacy_hint)
            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf("application/octet-stream", "application/x-sqlite3", "application/sqlite", "*/*"),
                        )
                    },
                    REQ_IMPORT_LEGACY,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── SAF results (forwarded by MainActivity.onActivityResult) ─────────

    @Suppress("DEPRECATION")
    fun handleResult(activity: AppCompatActivity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_JSON, REQ_EXPORT_DB -> runExport(activity, requestCode, uri)
            REQ_IMPORT_JSON -> confirmAndImport(activity, uri, legacy = false)
            REQ_IMPORT_LEGACY -> confirmAndImport(activity, uri, legacy = true)
        }
    }

    // ── export runner ─────────────────────────────────────────────────────

    private fun runExport(activity: AppCompatActivity, requestCode: Int, uri: android.net.Uri) {
        runWithProgress(activity) {
            try {
                when (requestCode) {
                    REQ_EXPORT_JSON -> {
                        val json = BackupManager.exportJson(AppDatabase.get(activity), appVersion(activity))
                        val text = json.toString(2)
                        withContext(Dispatchers.IO) {
                            activity.contentResolver.openOutputStream(uri)?.use {
                                it.write(text.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("cannot open output")
                        }
                        val tables = json.getJSONObject("entities").length()
                        val records = countRecords(json)
                        activity.getString(R.string.backup_export_done, records, tables)
                    }
                    else -> {
                        val bytes = withContext(Dispatchers.IO) {
                            val out = activity.contentResolver.openOutputStream(uri)
                                ?: throw IllegalStateException("cannot open output")
                            BackupManager.exportDbCopy(activity, AppDatabase.get(activity), out)
                        }
                        activity.getString(
                            R.string.backup_export_db_done,
                            android.text.format.Formatter.formatShortFileSize(activity, bytes),
                        )
                    }
                }
            } catch (e: Exception) {
                // honest, specific: the export did not produce a usable file
                // (a truncated output is safely rejected by the import's
                // JSON/manifest validation — nothing can half-restore)
                activity.getString(R.string.backup_export_failed) + "\n" +
                    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            }
        }
    }

    // ── import runner (confirm → snapshot → merge → summary) ─────────────

    private fun confirmAndImport(activity: AppCompatActivity, uri: android.net.Uri, legacy: Boolean) {
        val display = try {
            uri.lastPathSegment ?: uri.toString()
        } catch (_: Exception) {
            uri.toString()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(
                activity.getString(R.string.backup_import_confirm_body) + "\n\n" +
                    (if (legacy) activity.getString(R.string.backup_import_legacy_hint) + "\n\n" else "") +
                    display,
            )
            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                if (legacy) runLegacyImport(activity, uri) else runJsonImport(activity, uri)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runJsonImport(activity: AppCompatActivity, uri: android.net.Uri) {
        runWithProgress(activity) {
            val db = AppDatabase.get(activity)
            // safety first — abort rather than import without an undo point
            val snapshot = withContext(Dispatchers.IO) { BackupManager.createSafetyBackup(activity, db) }
                ?: throw BackupManager.BackupException(BackupManager.BackupException.CODE_SAFETY_FAILED, "safety backup failed")
            val text = withContext(Dispatchers.IO) {
                activity.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                    ?: throw BackupManager.BackupException(BackupManager.BackupException.CODE_CORRUPT, "cannot read")
            }
            val result = BackupManager.importJson(db, text)
            Pair(result, snapshot)
        }
    }

    private fun runLegacyImport(activity: AppCompatActivity, uri: android.net.Uri) {
        runWithProgress(activity) {
            val db = AppDatabase.get(activity)
            val snapshot = withContext(Dispatchers.IO) { BackupManager.createSafetyBackup(activity, db) }
                ?: throw BackupManager.BackupException(BackupManager.BackupException.CODE_SAFETY_FAILED, "safety backup failed")
            // copy the picked file to a private writable temp — Room MIGRATES
            // it, so never the user's only original
            val tmp = File(activity.cacheDir, "legacy-picked-${System.currentTimeMillis()}.db")
            try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw BackupManager.BackupException(BackupManager.BackupException.CODE_CORRUPT, "cannot read")
                }
                val result = BackupManager.importLegacyDb(activity, db, tmp, appVersion(activity))
                Pair(result, snapshot)
            } finally {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }
    }

    // ── safety snapshots browser ──────────────────────────────────────────

    private fun showSafetyList(activity: AppCompatActivity) {
        val snaps = BackupManager.listSafetyBackups(activity)
        if (snaps.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.backup_safety_title)
                .setMessage(R.string.backup_safety_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val labels = snaps.map { describeSnapshot(activity, it) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_safety_title)
            .setItems(labels) { _, which ->
                val file = snaps[which]
                AlertDialog.Builder(activity)
                    .setTitle(R.string.backup_import_confirm_title)
                    .setMessage(R.string.backup_safety_restore_confirm)
                    .setPositiveButton(R.string.continue_anyway) { _, _ -> runSafetyRestore(activity, file) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun runSafetyRestore(activity: AppCompatActivity, file: File) {
        runWithProgress(activity) {
            val db = AppDatabase.get(activity)
            // restoring a snapshot: a safety snapshot for THIS restore would
            // be redundant (the file we are applying IS the safety net)
            val result = BackupManager.importLegacyDb(activity, db, file, appVersion(activity))
            Pair(result, file)
        }
    }

    // ── shared plumbing ───────────────────────────────────────────────────

    /**
     * Runs [block] on IO inside a non-cancelable progress dialog, then shows
     * the success payload (String → toast) or the ImportResult (→ summary
     * dialog); every BackupManager.BackupException maps to its specific
     * Persian string, anything else to the honest generic one.
     */
    private fun runWithProgress(activity: AppCompatActivity, block: suspend () -> Any) {
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        box.addView(ProgressBar(activity))
        box.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.backup_working)
                setPadding(0, pad, 0, 0)
            },
        )
        val dlg = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val outcome = withContext(Dispatchers.IO) { block() }
                dlg.dismiss()
                when (outcome) {
                    is String -> Toast.makeText(activity, outcome, Toast.LENGTH_LONG).show()
                    is Pair<*, *> -> showSummary(activity, outcome.first as BackupManager.ImportResult)
                    else -> Toast.makeText(activity, outcome.toString(), Toast.LENGTH_LONG).show()
                }
            } catch (e: BackupManager.BackupException) {
                dlg.dismiss()
                Toast.makeText(activity, describeError(activity, e), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                dlg.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.backup_err_unexpected, e.javaClass.simpleName + ": " + (e.message ?: "")),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showSummary(activity: AppCompatActivity, result: BackupManager.ImportResult) {
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(
            TextView(activity).apply {
                text = activity.getString(
                    R.string.backup_import_done_totals,
                    result.totalAdded, result.totalUpdated, result.totalSkipped,
                )
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            },
        )
        if (result.totalAdded == 0 && result.totalUpdated == 0) {
            box.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.backup_import_nothing)
                    textSize = 13f
                    setPadding(0, dp(activity, 8), 0, 0)
                },
            )
        }
        val rows = TextView(activity).apply {
            textSize = 12f
            setPadding(0, dp(activity, 8), 0, 0)
            text = result.tables.entries.joinToString("\n") { (table, c) ->
                activity.getString(R.string.backup_import_done_row, table, c.added, c.updated, c.skipped)
            }
        }
        box.addView(rows)
        box.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.backup_import_safety_note)
                textSize = 11f
                setPadding(0, dp(activity, 8), 0, 0)
            },
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_done_title)
            .setView(box)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun describeError(activity: AppCompatActivity, e: BackupManager.BackupException): String =
        when (e.code) {
            BackupManager.BackupException.CODE_CORRUPT -> activity.getString(R.string.backup_err_corrupt)
            BackupManager.BackupException.CODE_NOT_MEDPAD -> activity.getString(R.string.backup_err_not_medpad, e.message?.substringAfter("app=") ?: "?")
            BackupManager.BackupException.CODE_FORMAT_FUTURE -> activity.getString(R.string.backup_err_format_future)
            BackupManager.BackupException.CODE_SCHEMA_FUTURE -> activity.getString(R.string.backup_err_schema_future)
            BackupManager.BackupException.CODE_NO_ENTITIES -> activity.getString(R.string.backup_err_no_entities)
            BackupManager.BackupException.CODE_LEGACY_TOO_OLD -> activity.getString(R.string.backup_err_legacy_too_old)
            BackupManager.BackupException.CODE_DB_OPEN_FAILED -> activity.getString(R.string.backup_err_db_open)
            BackupManager.BackupException.CODE_SAFETY_FAILED -> activity.getString(R.string.backup_err_safety)
            else -> activity.getString(R.string.backup_err_unexpected, e.message ?: "")
        }

    private fun describeSnapshot(activity: AppCompatActivity, f: File): String {
        val ms = f.name
            .removePrefix("medpad-pre-restore-")
            .removeSuffix(".db")
            .toLongOrNull() ?: 0L
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val j = com.medpad.tracker.ui.JalaliDate.fromGregorian(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
        val time = DateFormat.format("HH:mm", Date(ms))
        val size = android.text.format.Formatter.formatShortFileSize(activity, f.length())
        return "${com.medpad.tracker.ui.JalaliDate.formatFa(j)} — $time ($size)"
    }

    private fun appVersion(activity: AppCompatActivity): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun countRecords(json: org.json.JSONObject): Int {
        val entities = json.optJSONObject("entities") ?: return 0
        var n = 0
        val keys = entities.keys()
        while (keys.hasNext()) {
            val arr = entities.optJSONArray(keys.next()) ?: continue
            n += arr.length()
        }
        return n
    }

    private fun fileNameTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())

    private fun dp(activity: AppCompatActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
