package com.medpad.tracker.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.ui.JalaliDate.J
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * «گزارش‌های من» — v1.9.0: rep sees own history with a Jalali calendar filter
 * and can replay own voice notes (voice downloaded via token-protected URL).
 */
class ReportsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var allReports: List<JSONObject> = emptyList()
    private var filterDay: J? = null
    private var player: MediaPlayer? = null
    private var playingId: String? = null

    // calendar state
    private var calYear: Int = 0
    private var calMonth: Int = 0

    private lateinit var recycler: RecyclerView
    private lateinit var calendarGrid: RecyclerView
    private lateinit var calendarBox: View
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
        tvMonthName = findViewById(R.id.tvMonthName)
        tvEmpty = findViewById(R.id.tvEmpty)

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
        tvMonthName.text = "${JalaliDate.MONTH_NAMES[calMonth - 1]} ${calYear}"
        val days = JalaliDate.monthLength(calYear, calMonth)
        val cells = (1..days).map { d ->
            val g = JalaliDate.toGregorian(calYear, calMonth, d)
            val gj = J(calYear, calMonth, d)
            val selected = filterDay?.let { it.year == calYear && it.month == calMonth && it.day == d } == true
            DayCell(d, selected, g)
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

    private fun playVoice(reportId: String, voiceUrl: String, onFail: () -> Unit) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val resp = ApiClient.httpCall(voiceUrl)
                    val dir = File(cacheDir, "voice").apply { mkdirs() }
                    val f = File(dir, reportId + ".m4a")
                    FileOutputStream(f).use { it.write(resp) }
                    f
                } catch (_: Exception) {
                    null
                }
            }
            if (file == null || !file.exists()) {
                Toast.makeText(this@ReportsActivity, R.string.voice_play_failed, Toast.LENGTH_SHORT).show()
                onFail()
                return@launch
            }
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { release() }
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                Toast.makeText(this@ReportsActivity, R.string.voice_play_failed, Toast.LENGTH_SHORT).show()
                onFail()
            }
        }
    }

    class DayCell(val day: Int, val selected: Boolean, val g: IntArray)

    // ── reports list ────────────────────────────────────────
    inner class ReportsAdapter : RecyclerView.Adapter<ReportsAdapter.VH>() {
        var items: List<JSONObject> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvItemTitle)
            val tvNote: TextView = v.findViewById(R.id.tvItemNote)
            val tvTime: TextView = v.findViewById(R.id.tvItemTime)
            val btnPlay: Button = v.findViewById(R.id.btnItemPlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_report, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            val kind = r.optString("kind", "checkin")
            val cus = r.optJSONObject("customer")?.optString("name") ?: ""
            h.tvTitle.text = if (kind == "checkin") "چک‌این — $cus" else "چک‌اوت — $cus"
            h.tvNote.text = r.optString("note", "")
            h.tvTime.text = faTime(r.optString("createdAt"))
            val hasVoice = r.optString("voiceUrl", "").isNotEmpty()
            h.btnPlay.visibility = if (hasVoice) View.VISIBLE else View.GONE
            h.btnPlay.setOnClickListener {
                val id = r.optString("id")
                playingId = id
                playVoice(id, r.optString("voiceUrl")) { playingId = null }
            }
        }
    }

    // ── jalali calendar grid ────────────────────────────────
    inner class CalAdapter : RecyclerView.Adapter<CalAdapter.VH>() {
        var cells: List<DayCell> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val btn: Button = v.findViewById(R.id.btnDay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false))

        override fun getItemCount() = cells.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = cells[pos]
            h.btn.text = c.day.toString()
            h.btn.setBackgroundResource(if (c.selected) R.drawable.day_selected else R.drawable.day_normal)
            h.btn.setTextColor(if (c.selected) 0xFFFFFFFF.toInt() else 0xFF1C1917.toInt())
            h.btn.setOnClickListener {
                filterDay = J(calYear, calMonth, c.day)
                calendarBox.visibility = View.GONE
                renderCalendar()
                applyFilter()
            }
        }
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
        player?.release()
        super.onDestroy()
    }
}
