package com.medpad.tracker.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.ui.JalaliDate.J
import com.medpad.tracker.util.JsonSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

/**
 * «گزارش‌های من» — v1.9.0: rep sees own history with a Jalali calendar filter
 * and can replay own voice notes (voice downloaded via token-protected URL).
 * mission 12 / item 6 (Nov 2026): the bare play button is replaced by the
 * VoiceMiniPlayerHub — play/pause/resume/stop, speed 1×/1.5×/2×, seek bar,
 * played/total, resume-from-breakpoint, loading + error states, and ONE
 * player row PER recording (mission item 9) with no simultaneous playback.
 */
class ReportsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var allReports: List<JSONObject> = emptyList()
    private var filterDay: J? = null

    // mission 12 / item 6 — the single-flight mini-player hub (ONE
    // MediaPlayer for the whole screen; per-file resume memory)
    private lateinit var players: VoiceMiniPlayerHub

    // calendar state
    private var calYear: Int = 0
    private var calMonth: Int = 0

    private lateinit var recycler: RecyclerView
    private lateinit var calendarGrid: RecyclerView
    private lateinit var calendarBox: View
    private lateinit var calWeekHeader: LinearLayout
    private lateinit var tvMonthName: TextView
    private lateinit var tvEmpty: TextView
    private val adapter = ReportsAdapter()
    private val calAdapter = CalAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        recycler = findViewById(R.id.recycler)
        calendarGrid = findViewById(R.id.calendarGrid)
        calendarBox = findViewById(R.id.calendarBox)
        calWeekHeader = findViewById(R.id.calWeekHeader)
        tvMonthName = findViewById(R.id.tvMonthName)
        tvEmpty = findViewById(R.id.tvEmpty)
        players = VoiceMiniPlayerHub(this, scope)

        // mission 12 / item 7 — full-RTL calendar: the app does not declare
        // supportsRtl, so the grid and its header are flipped explicitly:
        // column 0 (rightmost) is شنبه, the week reads right→left exactly
        // like a Persian paper calendar.
        calendarGrid.layoutDirection = View.LAYOUT_DIRECTION_RTL
        calWeekHeader.layoutDirection = View.LAYOUT_DIRECTION_RTL
        JalaliDate.WEEK_DAYS.forEach { name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFF57534E.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            calWeekHeader.addView(tv)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        calendarGrid.layoutManager = GridLayoutManager(this, 7)
        calendarGrid.adapter = calAdapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAll).setOnClickListener {
            filterDay = null
            calendarBox.visibility = if (calendarBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            applyFilter()
        }

        val cal = Calendar.getInstance()
        val j = JalaliDate.fromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        calYear = j.year
        calMonth = j.month

        findViewById<ImageButton>(R.id.btnPrevMonth).setOnClickListener {
            calMonth--; if (calMonth == 0) { calMonth = 12; calYear-- }
            renderCalendar()
        }
        findViewById<ImageButton>(R.id.btnNextMonth).setOnClickListener {
            calMonth++; if (calMonth == 13) { calMonth = 1; calYear++ }
            renderCalendar()
        }

        load()
    }

    private fun load() {
        scope.launch {
            allReports = withContext(Dispatchers.IO) {
                try {
                    val resp = ApiClient.reports()
                    val arr = resp.optJSONArray("reports")
                    (0 until (arr?.length() ?: 0)).map { i -> arr!!.getJSONObject(i) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            renderCalendar()
            applyFilter()
        }
    }

    private fun renderCalendar() {
        tvMonthName.text = "${JalaliDate.MONTH_NAMES[calMonth - 1]} ${JalaliDate.faDigits(calYear)}"
        val days = JalaliDate.monthLength(calYear, calMonth)
        // mission 12 / item 7 — leading blanks so day ۱ lands under its own
        // weekday column (شنبه is column 0 = rightmost in the RTL grid)
        val firstDow = JalaliDate.weekDayIndex(calYear, calMonth, 1)
        val cells = ArrayList<DayCell?>(firstDow + days)
        repeat(firstDow) { cells.add(null) }
        // the Jalali «today» for the TODAY marker (device clock, Tehran day)
        val nowCal = Calendar.getInstance().apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran") }
        val today = JalaliDate.fromGregorian(
            nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH) + 1, nowCal.get(Calendar.DAY_OF_MONTH),
        )
        // days that carry at least one report (the dot badge)
        val reportDays = HashSet<Int>()
        for (r in allReports) {
            val j = parseDate(r)
            if (j.year == calYear && j.month == calMonth) reportDays.add(j.day)
        }
        for (d in 1..days) {
            val selected = filterDay?.let { it.year == calYear && it.month == calMonth && it.day == d } == true
            val isToday = today.year == calYear && today.month == calMonth && today.day == d
            cells.add(DayCell(d, selected, isToday, reportDays.contains(d)))
        }
        calAdapter.cells = cells
        calAdapter.notifyDataSetChanged()
    }

    private fun applyFilter() {
        val list = allReports.filter { r ->
            val f = filterDay ?: return@filter true
            val created = parseDate(r)
            created.year == f.year && created.month == f.month && created.day == f.day
        }
        adapter.items = list
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun parseDate(r: JSONObject): J {
        val iso = r.optString("createdAt")
        return try {
            val cal = Calendar.getInstance().apply { timeInMillis = java.time.Instant.parse(iso).toEpochMilli() }
            // Tehran day boundary
            val tz = java.util.TimeZone.getTimeZone("Asia/Tehran")
            cal.timeZone = tz
            JalaliDate.fromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        } catch (_: Exception) {
            J(0, 1, 1)
        }
    }

    /** mission 12 / item 7 — a calendar cell: null = leading blank,
     *  otherwise day number + selected/today/has-report markers. */
    class DayCell(val day: Int, val selected: Boolean, val today: Boolean, val hasReport: Boolean)

    // ── reports list ────────────────────────────────────────
    inner class ReportsAdapter : RecyclerView.Adapter<ReportsAdapter.VH>() {
        var items: List<JSONObject> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvItemTitle)
            val tvNote: TextView = v.findViewById(R.id.tvItemNote)
            val tvTime: TextView = v.findViewById(R.id.tvItemTime)
            val boxVoices: LinearLayout = v.findViewById(R.id.boxVoices)
            val btnEdit: Button = v.findViewById(R.id.btnItemEdit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            val kind = r.optString("kind", "checkin")
            val cus = if (r.has("customer") && !r.isNull("customer"))
                r.optJSONObject("customer")?.optString("name") ?: "" else ""
            h.tvTitle.text = if (kind == "checkin") "چک‌این — $cus" else "چک‌اوت — $cus"
            // mission item 1: a missing/JSON-null note (or the legacy "null"
            // artifact) renders as NO note section — never the raw text
            // "null" (JsonSafe). The stored value is untouched server-side.
            val note = JsonSafe.text(r, "note")
            h.tvNote.text = note
            h.tvNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
            h.tvTime.text = faTime(r.optString("createdAt"))
            // mission 12 / items 6+9 — ONE mini-player row PER recording:
            // the server's voiceFiles array (id + url each) when present;
            // the legacy single voiceUrl as a one-row fallback for old
            // panels / pre-migration events. A report with no playable
            // audio shows NO player at all.
            h.boxVoices.removeAllViews()
            val reportId = r.optString("id")
            val files = ArrayList<Pair<String, String>>() // file id → url
            val vfArr = r.optJSONArray("voiceFiles")
            if (vfArr != null) {
                for (i in 0 until vfArr.length()) {
                    val vf = vfArr.optJSONObject(i) ?: continue
                    val fid = vf.optString("id")
                    val furl = JsonSafe.playableUrl(vf, "url") ?: continue
                    if (fid.isNotEmpty() && fid != "null") files.add(fid to furl)
                }
            }
            if (files.isEmpty()) {
                // legacy fallback — single voiceUrl keyed by the report id
                JsonSafe.playableUrl(r, "voiceUrl")?.let { files.add(reportId to it) }
            }
            if (files.isNotEmpty()) {
                h.boxVoices.visibility = View.VISIBLE
                files.forEachIndexed { i, (fid, furl) ->
                    val row = LinearLayout(this@ReportsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    row.addView(players.labelView(getString(R.string.player_file_label, JalaliDate.faDigits(i + 1))))
                    row.addView(players.buildRow(fid, furl))
                    h.boxVoices.addView(row)
                }
            } else {
                h.boxVoices.visibility = View.GONE
            }
            // phase 2 / §2 (dev.4) — every row is the rep's OWN report
            // (the API returns own history only), so the edit affordance is
            // unconditional; the server still enforces ownership on save.
            h.btnEdit.setOnClickListener { showEditReport(r) }
        }
    }

    // ── jalali calendar grid ────────────────────────────────
    inner class CalAdapter : RecyclerView.Adapter<CalAdapter.VH>() {
        var cells: List<DayCell?> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val btn: Button = v.findViewById(R.id.btnDay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false))

        override fun getItemCount() = cells.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = cells[pos]
            if (c == null) {
                // leading blank — occupies the weekday column, not clickable
                h.btn.text = ""
                h.btn.background = null
                h.btn.isEnabled = false
                h.btn.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                return
            }
            h.btn.isEnabled = true
            // mission 12 / item 7 — Persian digits («۱۴» not "14")
            h.btn.text = JalaliDate.faDigits(c.day)
            h.btn.background = when {
                c.selected -> android.content.res.ResourcesCompat.getDrawable(
                    resources, R.drawable.day_selected, null,
                )
                c.today -> android.content.res.ResourcesCompat.getDrawable(
                    resources, R.drawable.day_today, null,
                )
                else -> android.content.res.ResourcesCompat.getDrawable(
                    resources, R.drawable.day_normal, null,
                )
            }
            h.btn.setTextColor(
                if (c.selected) 0xFFFFFFFF.toInt() else 0xFF1C1917.toInt(),
            )
            // mission 12 / item 7 — the dot badge marks days that carry at
            // least one report (hidden on the selected day: the filled
            // circle is already the strongest marker)
            h.btn.setCompoundDrawablesWithIntrinsicBounds(
                0, 0, 0, if (c.hasReport && !c.selected) R.drawable.dot_report_day else 0,
            )
            h.btn.setOnClickListener {
                filterDay = J(calYear, calMonth, c.day)
                calendarBox.visibility = View.GONE
                renderCalendar()
                applyFilter()
            }
        }
    }

    /** phase 2 / §2 (dev.4) — edit the text of an own report. Client
     *  validation mirrors the server route (cap 5000 after trim; a blank
     *  text is legal and clears the note — the previous text is preserved
     *  in the server's append-only version history). The server is the
     *  source of truth: an edited-away report answers 404, a dead session
     *  401 — both get an honest Persian message, never a crash. */
    private fun showEditReport(r: JSONObject) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            // raw server value — an edit dialog must never mask or mutate
            // what is actually stored (JsonSafe.text is for DISPLAY only)
            setText(JsonSafe.rawText(r, "note"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP
        }
        val hint = TextView(this).apply {
            text = getString(R.string.edit_report_hint)
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        val errBox = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            setTextColor(0xFFB91C1C.toInt())
            visibility = View.GONE
        }
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        box.addView(input); box.addView(hint); box.addView(errBox); box.addView(progress)

        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.edit_report_title)
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            val saveBtn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            saveBtn.setOnClickListener {
                errBox.visibility = View.GONE
                val note = input.text.toString()
                if (Validators.reportNoteTooLong(note)) {
                    errBox.text = getString(R.string.err_report_note_too_long)
                    errBox.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                saveBtn.isEnabled = false
                progress.visibility = View.VISIBLE
                scope.launch {
                    try {
                        val resp = withContext(Dispatchers.IO) {
                            ApiClient.reportEdit(r.optString("id"), note)
                        }
                        if (resp.optBoolean("changed")) {
                            // mirror the server: stored value is trimmed
                            // (a blank text clears the note server-side)
                            r.put("note", note.trim())
                            applyFilter()
                            Toast.makeText(this@ReportsActivity, R.string.report_edited, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@ReportsActivity, R.string.report_edit_no_change, Toast.LENGTH_SHORT).show()
                        }
                        dlg.dismiss()
                    } catch (e: Exception) {
                        saveBtn.isEnabled = true
                        progress.visibility = View.GONE
                        errBox.text = when {
                            e is ApiClient.HttpException && e.code == 401 ->
                                getString(R.string.session_expired)
                            e is ApiClient.HttpException && e.code == 404 ->
                                getString(R.string.err_report_not_found)
                            e is ApiClient.HttpException ->
                                e.message ?: getString(R.string.report_edit_failed)
                            else -> getString(R.string.report_edit_failed)
                        }
                        errBox.visibility = View.VISIBLE
                    }
                }
            }
        }
        dlg.show()
    }

    private fun faTime(iso: String): String = try {
        val cal = Calendar.getInstance().apply {
            timeInMillis = java.time.Instant.parse(iso).toEpochMilli()
            timeZone = java.util.TimeZone.getTimeZone("Asia/Tehran")
        }
        "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    } catch (_: Exception) {
        ""
    }

    override fun onDestroy() {
        // mission 12 / item 6 — release the single MediaPlayer + ticker
        players.release()
        super.onDestroy()
    }
}
