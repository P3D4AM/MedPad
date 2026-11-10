package com.medpad.tracker.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.sync.Customers360
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Customer 360 (phase 1) — the customer dossier screen (app side).
 *
 * Data: GET /api/app/customers/[id]/profile (server-enforced: assigned to
 * this rep OR one of the rep's own offline-created rows — the server is the
 * gatekeeper, changing the id in the intent reveals nothing). The response
 * is cached in Room, so the profile opens OFFLINE with the last synced
 * state + an honest «آخرین به‌روزرسانی» banner.
 *
 * Sections (mission §6.2):
 *   اطلاعات پایه — profile fields + contact + address + location
 *   ارتباطات     — physicians of this clinic / clinics of this physician
 *   خریدها       — REAL purchase summary + list (empty state until Holoo)
 *   تاریخچه      — visit history: time, rep, duration, plan badge, notes,
 *                  per-file voice mini-players (the existing mechanism)
 *   یادداشت‌ها   — the customer's general notes
 */
class CustomerProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CUSTOMER_ID = "medpad.extra.CUSTOMER_ID"
        private const val TAB_INFO = "info"
        private const val TAB_LINKS = "links"
        private const val TAB_PURCHASES = "purchases"
        private const val TAB_VISITS = "visits"
        private const val TAB_NOTES = "notes"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var customerId: String? = null
    private var profile: JSONObject? = null
    private var cached = false
    private var currentTab = TAB_INFO

    private lateinit var boxTabs: LinearLayout
    private lateinit var boxContent: LinearLayout
    private lateinit var progress: android.widget.ProgressBar

    /** mission-12/item-6 hub — ONE active player at a time, resume memory. */
    private val players by lazy {
        VoiceMiniPlayerHub(this, CoroutineScope(Dispatchers.Main))
    }

    override fun onDestroy() {
        players.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_profile)

        customerId = intent.getStringExtra(EXTRA_CUSTOMER_ID)
        if (customerId == null) {
            finish()
            return
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMap).setOnClickListener { openOnMap() }
        boxTabs = findViewById(R.id.boxTabs)
        boxContent = findViewById(R.id.boxContent)
        progress = findViewById(R.id.progress)

        load()
    }

    private fun openOnMap() {
        val id = customerId ?: return
        startActivity(
            Intent(this, DoctorsMapActivity::class.java)
                .putExtra(DoctorsMapActivity.EXTRA_FOCUS_CUSTOMER_ID, id)
        )
    }

    private fun load() {
        val id = customerId ?: return
        progress.visibility = View.VISIBLE
        boxContent.removeAllViews()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Customers360.loadProfile(id)
                } catch (_: Exception) {
                    null
                }
            }
            progress.visibility = View.GONE
            if (result == null) {
                boxContent.addView(emptyState(getString(R.string.profile_load_failed)))
                return@launch
            }
            val (resp, fromCache) = result
            profile = resp
            cached = fromCache
            renderHeader()
            renderTabs()
            renderTab(currentTab)
        }
    }

    // ── header + summary ────────────────────────────────────
    private fun renderHeader() {
        val p = profile ?: return
        val c = p.optJSONObject("customer") ?: return
        val name = c.optString("name")
        val lastName = c.optString("lastName", "")
        val fullName = if (lastName.isNotEmpty() && lastName != "null") "$name $lastName" else name

        findViewById<TextView>(R.id.tvAvatar).text = fullName.take(2)
        findViewById<TextView>(R.id.tvName).text = fullName

        val type = c.optString("type", "doctor")
        val badges = mutableListOf<String>()
        badges.add(
            when (type) {
                "doctor" -> getString(R.string.type_doctor)
                "clinic" -> getString(R.string.type_clinic)
                "pharmacy" -> getString(R.string.type_pharmacy)
                else -> getString(R.string.type_other)
            }
        )
        if (!c.optBoolean("active", true)) badges.add(getString(R.string.customer_inactive))
        if (c.optBoolean("pending", false) || idStartsWithLocal()) badges.add(getString(R.string.customer_pending_sync))
        findViewById<TextView>(R.id.tvBadges).text = badges.joinToString(" · ")

        val contact = mutableListOf<String>()
        c.optString("title", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(it) }
        c.optString("specialty", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(it) }
        c.optString("phone", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(it) }
        c.optString("phone2", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(it) }
        c.optString("managerName", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(getString(R.string.customer_manager) + ": " + it) }
        listOf(c.optString("city", null), c.optString("district", null))
            .filter { !it.isNullOrEmpty() && it != "null" }
            .takeIf { it.isNotEmpty() }
            ?.let { contact.add(it.joinToString("، ")) }
        c.optString("address", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { contact.add(it) }
        findViewById<TextView>(R.id.tvContact).text = contact.joinToString("\n")

        // summary: total purchase · order count · last visit
        val sum = p.optJSONObject("purchaseSummary")
        val visits = p.optJSONArray("visits")
        val lastVisit = visits?.optJSONObject(0)?.optString("startAt")
        findViewById<TextView>(R.id.tvSum1).text =
            if ((sum?.optInt("orderCount") ?: 0) > 0) fa(sum!!.optDouble("total")) else "—"
        findViewById<TextView>(R.id.tvSum1Label).text = getString(R.string.sum_total_purchase)
        findViewById<TextView>(R.id.tvSum2).text = fa(sum?.optInt("orderCount") ?: 0)
        findViewById<TextView>(R.id.tvSum2Label).text = getString(R.string.sum_order_count)
        findViewById<TextView>(R.id.tvSum3).text =
            if (!lastVisit.isNullOrEmpty() && lastVisit != "null")
                parseIsoJalali(lastVisit)?.let { JalaliDate.formatFa(it) } ?: "—" else "—"
        findViewById<TextView>(R.id.tvSum3Label).text = getString(R.string.sum_last_visit)

        findViewById<TextView>(R.id.tvCached).apply {
            if (cached) {
                text = getString(R.string.profile_cached_note)
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }
    }

    private fun idStartsWithLocal(): Boolean = customerId?.startsWith("local-") == true

    // ── tabs ────────────────────────────────────────────────
    private fun renderTabs() {
        boxTabs.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        val tabs = listOf(
            TAB_INFO to getString(R.string.tab_info),
            TAB_LINKS to getString(R.string.tab_links),
            TAB_PURCHASES to getString(R.string.tab_purchases),
            TAB_VISITS to getString(R.string.tab_visits),
            TAB_NOTES to getString(R.string.tab_notes),
        )
        tabs.forEach { (key, label) ->
            boxTabs.addView(
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 12f
                    minHeight = 0
                    setPadding(pad, pad / 2, pad, pad / 2)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.marginEnd = pad / 2
                    layoutParams = lp
                    if (currentTab == key) {
                        setTextColor(0xFFFFFFFF.toInt())
                        background.setTint(0xFF2563EB.toInt())
                    } else {
                        setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
                        background.setTint(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_200))
                    }
                    setOnClickListener {
                        currentTab = key
                        renderTabs()
                        renderTab(key)
                    }
                },
            )
        }
    }

    private fun renderTab(tab: String) {
        boxContent.removeAllViews()
        when (tab) {
            TAB_INFO -> renderInfo()
            TAB_LINKS -> renderLinks()
            TAB_PURCHASES -> renderPurchases()
            TAB_VISITS -> renderVisits()
            TAB_NOTES -> renderNotes()
        }
    }

    // ── tab: basic info ─────────────────────────────────────
    private fun renderInfo() {
        val c = profile?.optJSONObject("customer") ?: return
        val rows = listOf(
            R.string.customer_name to c.optString("name"),
            R.string.customer_lastname to c.optString("lastName", ""),
            R.string.customer_type to typeFa(c.optString("type", "doctor")),
            R.string.customer_pro_title to c.optString("title", ""),
            R.string.customer_specialty to c.optString("specialty", ""),
            R.string.customer_manager to c.optString("managerName", ""),
            R.string.customer_phone to c.optString("phone", ""),
            R.string.customer_phone2 to c.optString("phone2", ""),
            R.string.customer_province to c.optString("province", ""),
            R.string.customer_city to c.optString("city", ""),
            R.string.customer_district to c.optString("district", ""),
            R.string.customer_postal to c.optString("postalCode", ""),
            R.string.customer_address to c.optString("address", ""),
        ).filter { (_, v) -> v.isNotEmpty() && v != "null" }
        if (rows.isEmpty()) {
            boxContent.addView(emptyState(getString(R.string.profile_no_info)))
            return
        }
        rows.forEach { (labelRes, value) -> boxContent.addView(fieldRow(getString(labelRes), value)) }
    }

    // ── tab: links ──────────────────────────────────────────
    private fun renderLinks() {
        val p = profile ?: return
        val links = p.optJSONObject("links") ?: return
        val c = p.optJSONObject("customer") ?: return
        val isClinic = c.optString("type") == "clinic"
        val arr = links.optJSONArray(if (isClinic) "physicians" else "clinics")
        if (arr == null || arr.length() == 0) {
            boxContent.addView(
                emptyState(
                    getString(
                        if (isClinic) R.string.links_no_physicians else R.string.links_no_clinics
                    )
                ),
            )
            return
        }
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val cp = l.optJSONObject("counterpart") ?: continue
            if (!cp.optBoolean("visible", true)) {
                // masked counterpart (not assigned to this rep) — count only
                boxContent.addView(fieldRow(getString(R.string.links_masked), "—"))
                continue
            }
            val name = cp.optString("name")
            val sub = mutableListOf<String>()
            if (!cp.optString("city").isNullOrEmpty() && cp.optString("city") != "null") sub.add(cp.optString("city"))
            l.optString("note", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { sub.add(it) }
            boxContent.addView(cardRow(name, sub.joinToString(" · ")) {
                startActivity(
                    Intent(this, CustomerProfileActivity::class.java)
                        .putExtra(EXTRA_CUSTOMER_ID, cp.optString("id"))
                )
            })
        }
    }

    // ── tab: purchases (REAL data or honest empty state) ────
    private fun renderPurchases() {
        val p = profile ?: return
        val sum = p.optJSONObject("purchaseSummary")
        val purchases = p.optJSONArray("purchases")
        val count = purchases?.length() ?: 0

        // provenance note — mission §4.3 (no fake sales, ever)
        p.optString("purchaseSourceNote").takeIf { it.isNotEmpty() && it != "null" }?.let {
            boxContent.addView(noteBox(it))
        }

        // ── Holoo integration (phase 1): the accounting mirror — balance,
        // credit and the latest payments, straight from the cached profile
        // (offline-friendly). Explicit empty state when not linked. ──
        val erp = p.optJSONObject("erpAccount")
        if (erp != null) {
            val sub = mutableListOf(
                "${getString(R.string.holoo_mandeh)}: ${fa(erp.optDouble("mandeh", 0.0))}",
                "${getString(R.string.holoo_credit)}: ${fa(erp.optDouble("credit", 0.0))}",
            )
            erp.optString("code", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                sub.add("${getString(R.string.holoo_code)}: $it")
            }
            val pays = p.optJSONArray("erpPayments")
            if (pays != null && pays.length() > 0) {
                val shown = minOf(pays.length(), 5)
                sub.add(getString(R.string.holoo_recent_payments))
                for (i in 0 until shown) {
                    val pay = pays.optJSONObject(i) ?: continue
                    val d = parseIsoJalali(pay.optString("date"))?.let { JalaliDate.formatFa(it) } ?: ""
                    sub.add("  · $d — ${fa(pay.optDouble("price", 0.0))}")
                }
            }
            if (!erp.optBoolean("active", true)) sub.add(getString(R.string.holoo_inactive))
            boxContent.addView(cardRow(getString(R.string.holoo_financial_title), sub.joinToString("\n")))
        } else {
            boxContent.addView(noteBox(getString(R.string.holoo_not_linked)))
        }

        if (count == 0) {
            boxContent.addView(emptyState(getString(R.string.purchases_empty)))
            return
        }

        // summary strip
        sum?.let {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                R.string.sum_total_purchase to it.optDouble("total"),
                R.string.sum_last30 to it.optDouble("last30"),
                R.string.sum_last90 to it.optDouble("last90"),
                R.string.sum_last365 to it.optDouble("last365"),
            ).forEach { (res, v) ->
                strip.addView(
                    TextView(this).apply {
                        text = "${fa(v)}\n${getString(res)}"
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_700))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
            }
            boxContent.addView(strip)
        }

        for (i in 0 until count) {
            val pu = purchases!!.optJSONObject(i) ?: continue
            val date = parseIsoJalali(pu.optString("date"))?.let { JalaliDate.formatFa(it) } ?: ""
            val header = buildString {
                append(date)
                pu.optString("invoiceNo", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { append(" · ${getString(R.string.purchase_invoice)} $it") }
                append(" · ${if (pu.optString("source") == "holoo") getString(R.string.purchase_source_holoo) else getString(R.string.purchase_source_manual)}")
            }
            val net = run {
                var total = 0.0
                val items = pu.optJSONArray("items")
                for (j in 0 until (items?.length() ?: 0)) {
                    total += items!!.optJSONObject(j)?.optDouble("total", 0.0) ?: 0.0
                }
                total - pu.optDouble("discount", 0.0)
            }
            val sub = mutableListOf("${getString(R.string.purchase_total)}: ${fa(net)}")
            pu.optString("notes", null)?.takeIf { it.isNotEmpty() && it != "null" }?.let { sub.add(it) }
            boxContent.addView(cardRow(header, sub.joinToString("\n")))
        }
    }

    // ── tab: visit history ──────────────────────────────────
    private fun renderVisits() {
        val p = profile ?: return
        val visits = p.optJSONArray("visits")
        if (visits == null || visits.length() == 0) {
            boxContent.addView(emptyState(getString(R.string.visits_empty)))
            return
        }
        for (i in 0 until visits.length()) {
            val v = visits.optJSONObject(i) ?: continue
            val startMs = parseIsoMs(v.optString("startAt"))
            val start = parseIsoJalali(v.optString("startAt"))
            val endMs = v.optString("endAt", null)
                ?.takeIf { it.isNotEmpty() && it != "null" }?.let { parseIsoMs(it) } ?: 0L

            val title = buildString {
                start?.let { append(JalaliDate.formatFa(it)) }
                append(" · ${v.optJSONObject("employee")?.optString("name") ?: ""}")
                if (v.optBoolean("open")) append(" · ${getString(R.string.visit_open)}")
                else if (endMs > startMs && startMs > 0) {
                    val mins = ((endMs - startMs) / 60000).toInt()
                    append(" · ${getString(R.string.visit_duration, fa(mins))}")
                }
                if (v.optJSONObject("plannedVisit") != null) append(" · ${getString(R.string.visit_planned)}")
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_700))
            })

            // notes: check-in + check-out (with «ویرایش‌شده» markers)
            listOf("checkin", "checkout").forEach { k ->
                val ev = v.optJSONObject(k) ?: return@forEach
                val note = ev.optString("note", "")
                if (note.isNotEmpty() && note != "null") {
                    val edited = (ev.optJSONObject("_count")?.optInt("noteVersions") ?: 0) > 0
                    card.addView(TextView(this).apply {
                        text = if (edited) "$note (${getString(R.string.note_edited)})" else note
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
                        setPadding(0, 4, 0, 0)
                    })
                }
                // per-file voice players — the EXISTING token-auth mechanism
                val files = ev.optJSONArray("voiceFiles")
                for (j in 0 until (files?.length() ?: 0)) {
                    val f = files!!.optJSONObject(j) ?: continue
                    val path = f.optString("path")
                    if (path.isEmpty() || path == "null") continue
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 4, 0, 0)
                    }
                    row.addView(players.buildRow(f.optString("id"), ApiClient.mediaUrl(path)))
                    card.addView(row)
                }
            }
            boxContent.addView(card)
        }
    }

    // ── tab: notes ──────────────────────────────────────────
    private fun renderNotes() {
        val c = profile?.optJSONObject("customer") ?: return
        val notes = c.optString("notes", "")
        if (notes.isEmpty() || notes == "null") {
            boxContent.addView(emptyState(getString(R.string.notes_empty)))
            return
        }
        boxContent.addView(
            TextView(this).apply {
                text = notes
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_700))
            },
        )
    }

    // ── small view helpers ──────────────────────────────────
    private fun typeFa(t: String): String = when (t) {
        "doctor" -> getString(R.string.type_doctor)
        "clinic" -> getString(R.string.type_clinic)
        "pharmacy" -> getString(R.string.type_pharmacy)
        else -> getString(R.string.type_other)
    }

    private fun faDigitsStr(s: String): String =
        s.map { c -> if (c in '0'..'9') ('۰' + (c - '0')) else c }.joinToString("")
    private fun fa(d: Double): String = faDigitsStr(String.format(Locale.US, "%,.0f", d))
    private fun fa(n: Int): String = faDigitsStr(n.toString())

    /** ISO instant → Jalali date on the Tehran clock (same rule as Reports). */
    private fun parseIsoJalali(iso: String): JalaliDate.J? = try {
        val cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Tehran")
        ).apply { timeInMillis = java.time.Instant.parse(iso).toEpochMilli() }
        JalaliDate.fromGregorian(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    } catch (_: Exception) {
        null
    }

    /** ISO instant → epoch ms (0 when unparseable). */
    private fun parseIsoMs(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun fieldRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_700))
        })
        return row
    }

    private fun cardRow(title: String, sub: String, onClick: (() -> Unit)? = null): View {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            setCardBackgroundColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.card_bg))
            radius = 14 * resources.displayMetrics.density
            strokeWidth = 1
            setStrokeColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_200))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = pad / 2 }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad - 4, pad, pad - 4)
        }
        inner.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_700))
        })
        if (sub.isNotEmpty()) {
            inner.addView(TextView(this).apply {
                text = sub
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
                setPadding(0, 3, 0, 0)
            })
        }
        card.addView(inner)
        return card
    }

    private fun emptyState(message: String): View =
        TextView(this).apply {
            text = message
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 48)
            setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
        }

    private fun noteBox(message: String): View =
        TextView(this).apply {
            text = message
            textSize = 11f
            setPadding(12, 10, 12, 10)
            setTextColor(ContextCompat.getColor(this@CustomerProfileActivity, R.color.gray_500))
            background = ContextCompat.getDrawable(this@CustomerProfileActivity, R.drawable.bg_badge_gray)
        }
}
