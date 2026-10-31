package com.medpad.tracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.PlannedVisitEntity
import com.medpad.tracker.location.TrackingService
import com.medpad.tracker.Session
import com.medpad.tracker.sync.CheckinSync
import com.medpad.tracker.sync.PlansSync
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * «برنامهٔ بازدیدها» — phase 2 / task 2.
 *
 * Shows the employee's own server-planned visits, grouped by SERVER-local
 * day (dayKey) with sections for remaining / done / needs-reschedule /
 * cancelled. Plans are READ-ONLY: tapping one shows details and, when the
 * customer is currently assigned, opens the EXISTING check-in flow with
 * the customer preselected. The plan's status is only ever updated from
 * the server (check-in linkage) — never by a local write.
 *
 * Offline behaviour: the Room cache of the last successful sync stays
 * fully browsable; the sync line shows the last-sync time (or "offline").
 * A plans fetch failure never touches the check-in offline queue.
 */
class PlansActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var recycler: RecyclerView
    private lateinit var boxState: View
    private lateinit var progress: View
    private lateinit var tvState: TextView
    private lateinit var btnRetry: Button
    private lateinit var tvDay: TextView
    private lateinit var tvTodayChip: TextView
    private lateinit var tvSyncInfo: TextView
    private lateinit var boxSummary: ViewGroup
    private val adapter = PlansAdapter()

    /** The selected server-local day (YYYY-MM-DD). */
    private var dayKey: String = PlansSync.deviceDayKey(0)

    companion object {
        /** mission item 3 — open THIS exact plan (notification tap): jump to
         *  its day, highlight the row and open its details dialog. */
        const val EXTRA_PLAN_ID = "medpad.extra.PLAN_ID"
        /** mission item 3 — open the «درخواست‌های من» dialog (a rejected
         *  assignment-request notification). */
        const val EXTRA_OPEN_REQUESTS = "medpad.extra.OPEN_REQUESTS"
    }

    // mission item 3 — consumed once, after the first successful render
    private var pendingFocusPlanId: String? = null
    private var pendingOpenRequests = false

    /** the plan row currently highlighted (notification tap focus) */
    private var highlightedPlanId: String? = null

    /** true while a sync is in flight (drives the loading state). */
    private var syncing = false

    private var authDialogShown = false

    // mic permission (check-in flow may be started from here)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicPlan?.let { plan -> if (granted) showCheckinFor(plan) }
        pendingMicPlan = null
    }
    private var pendingMicPlan: PlannedVisitEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plans)

        recycler = findViewById(R.id.recycler)
        boxState = findViewById(R.id.boxState)
        progress = findViewById(R.id.progress)
        tvState = findViewById(R.id.tvState)
        btnRetry = findViewById(R.id.btnRetry)
        tvDay = findViewById(R.id.tvDay)
        tvTodayChip = findViewById(R.id.tvTodayChip)
        tvSyncInfo = findViewById(R.id.tvSyncInfo)
        boxSummary = findViewById(R.id.boxSummary)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRequests).setOnClickListener { showMyRequests() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { sync(true) }
        findViewById<ImageButton>(R.id.btnPrevDay).setOnClickListener { shiftDay(-1) }
        findViewById<ImageButton>(R.id.btnNextDay).setOnClickListener { shiftDay(+1) }
        btnRetry.setOnClickListener { sync(true) }

        // anchor on the server's today when we have one (device clock may skew)
        val serverToday = PlansSync.serverToday()
        if (serverToday.isNotEmpty()) dayKey = serverToday

        // mission item 3 — notification tap-through extras (consumed after
        // the first successful render, when the cache is warm)
        pendingFocusPlanId = intent.getStringExtra(EXTRA_PLAN_ID)?.takeIf { it.isNotEmpty() }
        pendingOpenRequests = intent.getBooleanExtra(EXTRA_OPEN_REQUESTS, false)

        sync(false)
    }

    override fun onResume() {
        super.onResume()
        // coming back from a check-in → refresh statuses from the server
        if (!syncing) sync(false)
        render()
    }

    private fun shiftDay(delta: Int) {
        val next = PlansSync.dayKeyPlus(dayKey, delta) ?: return
        dayKey = next
        render()
    }

    // ── sync + render ───────────────────────────────────────

    private fun sync(manual: Boolean) {
        if (syncing) return
        syncing = true
        showLoading()
        scope.launch {
            when (withContext(Dispatchers.IO) { PlansSync.sync() }) {
                is PlansSync.Result.Success -> { /* rendered below */ }
                is PlansSync.Result.AuthExpired -> showAuthExpired()
                is PlansSync.Result.Error -> {
                    if (manual) {
                        showError()
                    } else {
                        // silent background attempt — keep showing the cache
                        // (the cache IS the offline answer, not an error state)
                        render()
                        renderSyncLine()
                    }
                }
            }
            syncing = false
            render()
            renderSyncLine()
            consumePendingFocus()
        }
    }

    /** mission item 3 — consume the notification-tap extras exactly ONCE,
     *  after the first render (plan cache warm). A plan that is not in the
     *  cache (older than the synced window / cancelled long ago) gets an
     *  honest toast and the screen stays on today — never a crash. */
    private fun consumePendingFocus() {
        pendingFocusPlanId?.let { pid ->
            pendingFocusPlanId = null
            scope.launch {
                val plan = withContext(Dispatchers.IO) {
                    try {
                        com.medpad.tracker.db.AppDatabase
                            .get(com.medpad.tracker.sync.TrackerContext.app)
                            .plannedVisitDao().byId(pid)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (plan == null) {
                    Toast.makeText(this@PlansActivity, R.string.notif_tap_plan_gone, Toast.LENGTH_LONG).show()
                    return@launch
                }
                // jump to the plan's day and focus its row
                dayKey = plan.dayKey
                highlightedPlanId = plan.id
                render()
                focusPlanRow(plan)
            }
        }
        if (pendingOpenRequests) {
            pendingOpenRequests = false
            showMyRequests()
        }
    }

    /** scroll the plan's row into view and open its details dialog — the
     *  notification was ABOUT this plan, so the tap lands ON it. */
    private fun focusPlanRow(plan: PlannedVisitEntity) {
        val pos = adapter.items.indexOfFirst {
            it is Row.Plan && it.plan.id == plan.id
        }
        if (pos >= 0) recycler.scrollToPosition(pos)
        // small delay so the scroll settles before the dialog covers it
        recycler.postDelayed({ showDetails(plan) }, 350)
    }

    private fun render() {
        scope.launch {
            val plans = withContext(Dispatchers.IO) {
                // read through the same singleton the sync writes to
                com.medpad.tracker.db.AppDatabase
                    .get(com.medpad.tracker.sync.TrackerContext.app)
                    .plannedVisitDao().byDay(dayKey)
            }
            renderDay(plans)
        }
    }

    private fun renderDay(plans: List<PlannedVisitEntity>) {
        renderDayLabel()

        val remaining = plans.filter { it.status == "scheduled" }
        val done = plans.filter { it.status == "done" }
        val needs = plans.filter { it.status == "needs_reschedule" }
        val cancelled = plans.filter { it.status == "cancelled" }
        val overdueCount = remaining.count { it.overdue }

        // ── summary chips ──
        boxSummary.removeAllViews()
        fun chip(label: String, count: Int, bg: Int, fg: Int) {
            if (count <= 0) return
            val tv = TextView(this)
            tv.text = "$label $count"
            tv.textSize = 12f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.setTextColor(fg)
            tv.setBackgroundResource(bg)
            val pad = (resources.displayMetrics.density * 10).toInt()
            tv.setPadding(pad, pad / 3, pad, pad / 3)
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                ViewGroup.MarginLayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = pad / 2
            boxSummary.addView(tv, lp)
        }
        chip(getString(R.string.plan_section_done), done.size, R.drawable.bg_badge_green, 0xFF166534.toInt())
        chip(getString(R.string.plan_section_remaining), remaining.size, R.drawable.bg_badge_amber, 0xFF92400E.toInt())
        if (overdueCount > 0) chip(getString(R.string.plan_overdue), overdueCount, R.drawable.bg_badge_red, 0xFF991B1B.toInt())
        chip(getString(R.string.plan_section_needs_reschedule), needs.size, R.drawable.bg_badge_blue, 0xFF1E40AF.toInt())
        chip(getString(R.string.plan_section_cancelled), cancelled.size, R.drawable.bg_badge_gray, 0xFF374151.toInt())

        // ── list with section headers (actionable first) ──
        val items = ArrayList<Row>()
        if (remaining.isNotEmpty()) {
            items.add(Row.Header(getString(R.string.plan_section_remaining), remaining.size))
            remaining.forEach { items.add(Row.Plan(it)) }
        }
        if (done.isNotEmpty()) {
            items.add(Row.Header(getString(R.string.plan_section_done), done.size))
            done.forEach { items.add(Row.Plan(it)) }
        }
        if (needs.isNotEmpty()) {
            items.add(Row.Header(getString(R.string.plan_section_needs_reschedule), needs.size))
            needs.forEach { items.add(Row.Plan(it)) }
        }
        if (cancelled.isNotEmpty()) {
            items.add(Row.Header(getString(R.string.plan_section_cancelled), cancelled.size))
            cancelled.forEach { items.add(Row.Plan(it)) }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()

        // ── state box ──
        if (items.isEmpty()) {
            showEmpty()
        } else {
            boxState.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun renderDayLabel() {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(dayKey)
        if (m == null) {
            tvDay.text = dayKey
            tvTodayChip.visibility = View.GONE
            return
        }
        val (y, mo, d) = m.destructured
        val cal = GregorianCalendar(y.toInt(), mo.toInt() - 1, d.toInt()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val j = JalaliDate.fromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val weekday = JalaliDate.WEEK_DAYS[(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7]
        tvDay.text = "$weekday ${JalaliDate.formatFa(j)}"
        val today = PlansSync.serverToday().ifEmpty { PlansSync.deviceDayKey(0) }
        tvTodayChip.visibility = if (dayKey == today) View.VISIBLE else View.GONE
    }

    private fun renderSyncLine() {
        val last = PlansSync.lastSyncMs()
        tvSyncInfo.text = if (last == 0L) {
            getString(R.string.plan_never_synced)
        } else {
            val cal = Calendar.getInstance()
            cal.timeInMillis = last
            val t = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            getString(R.string.plan_last_sync, t)
        }
    }

    // ── state helpers ───────────────────────────────────────

    private fun showLoading() {
        boxState.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvState.text = getString(R.string.plan_loading)
        btnRetry.visibility = View.GONE
        recycler.visibility = View.GONE
    }

    private fun showEmpty() {
        boxState.visibility = View.VISIBLE
        progress.visibility = View.GONE
        tvState.text = getString(R.string.plan_empty)
        btnRetry.visibility = View.VISIBLE
        recycler.visibility = View.GONE
    }

    private fun showError() {
        boxState.visibility = View.VISIBLE
        progress.visibility = View.GONE
        tvState.text = getString(R.string.plan_load_error)
        btnRetry.visibility = View.VISIBLE
        recycler.visibility = View.GONE
    }

    private fun showAuthExpired() {
        if (authDialogShown) return
        authDialogShown = true
        AlertDialog.Builder(this)
            .setMessage(R.string.session_expired)
            .setCancelable(false)
            .setPositiveButton(R.string.login_again) { _, _ ->
                TrackingService.stop(this)
                Session.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .show()
    }

    // ── plan details dialog ─────────────────────────────────

    private fun showDetails(plan: PlannedVisitEntity) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plan, null)

        view.findViewById<TextView>(R.id.tvTime).text =
            "${plan.timeLocal} — ${dayKeyLabelShort()}"

        val tvCustomer = view.findViewById<TextView>(R.id.tvCustomer)
        val tvType = view.findViewById<TextView>(R.id.tvCustomerType)
        val tvMasked = view.findViewById<TextView>(R.id.tvMasked)
        val tvAddress = view.findViewById<TextView>(R.id.tvAddress)
        val tvPhone = view.findViewById<TextView>(R.id.tvPhone)
        val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
        val tvVisit = view.findViewById<TextView>(R.id.tvVisit)
        val tvNote = view.findViewById<TextView>(R.id.tvNote)
        val btnStart = view.findViewById<Button>(R.id.btnStart)
        val btnRequestAssign = view.findViewById<Button>(R.id.btnRequestAssign)

        if (plan.customerAssigned) {
            tvCustomer.text = plan.customerName ?: ""
            tvType.text = when (plan.customerType) {
                "doctor" -> getString(R.string.plan_type_doctor)
                "pharmacy" -> getString(R.string.plan_type_pharmacy)
                "clinic" -> getString(R.string.plan_type_clinic)
                else -> getString(R.string.plan_type_other)
            }
            plan.customerAddress?.let {
                tvAddress.text = it
                tvAddress.visibility = View.VISIBLE
            }
            plan.customerPhone?.let { phone ->
                tvPhone.text = "☎ $phone"
                tvPhone.visibility = View.VISIBLE
                tvPhone.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", ""))))
                    } catch (_: Exception) { /* no dialer — ignore */ }
                }
            }
        } else {
            // masked by the server (no longer assigned) — never fake a name
            tvCustomer.text = getString(R.string.plan_customer_masked_name)
            tvType.visibility = View.GONE
            tvMasked.text = getString(R.string.plan_customer_masked)
            tvMasked.visibility = View.VISIBLE
            // package 6 — the rep can ASK the admin for this customer
            btnRequestAssign.visibility = View.VISIBLE
            btnRequestAssign.setOnClickListener { showRequestAssign(plan) }
        }

        val priorityLabel = when (plan.priority) {
            "high" -> getString(R.string.plan_priority_high)
            "low" -> getString(R.string.plan_priority_low)
            else -> getString(R.string.plan_priority_normal)
        }
        tvMeta.text = getString(R.string.plan_meta_line, plan.durationMin.toString(), priorityLabel)

        if (plan.status == "done") {
            val v = plan.visitStartLocal?.let { getString(R.string.plan_visit_at, it) }
                ?: getString(R.string.plan_status_hint_done)
            tvVisit.text = if (plan.visitOpen) getString(R.string.plan_visit_open_now) else v
            tvVisit.visibility = View.VISIBLE
        }
        plan.note?.let {
            tvNote.text = it
            tvNote.visibility = View.VISIBLE
        }

        // check-in CTA only for an OPEN plan with a currently-assigned customer
        val canCheckin = plan.customerAssigned && (plan.status == "scheduled" || plan.status == "needs_reschedule")
        if (canCheckin) {
            btnStart.text = getString(R.string.plan_start_visit)
            btnStart.visibility = View.VISIBLE
            btnStart.setOnClickListener { startCheckinFlow(plan) }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.plan_details_title)
            .setView(view)
            .setPositiveButton(R.string.plan_close, null)
            .create()
        dialog.show()
    }

    private fun dayKeyLabelShort(): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(dayKey) ?: return dayKey
        val (y, mo, d) = m.destructured
        val j = JalaliDate.fromGregorian(y.toInt(), mo.toInt(), d.toInt())
        return JalaliDate.formatFa(j)
    }

    private fun startCheckinFlow(plan: PlannedVisitEntity) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showCheckinFor(plan)
        } else {
            pendingMicPlan = plan
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showCheckinFor(plan: PlannedVisitEntity) {
        val dialog = CheckinDialogFragment(
            preselectCustomerId = plan.customerId,
            onDone = {
                // the check-in was queued/sent — the plan status itself comes
                // from the server; refresh to pick up the linkage
                scope.launch {
                    val queued = withContext(Dispatchers.IO) { CheckinSync.pendingCount() }
                    if (queued > 0) {
                        Toast.makeText(this@PlansActivity, R.string.plan_checkin_queued_note, Toast.LENGTH_LONG).show()
                    }
                }
                sync(true)
            },
        )
        dialog.show(supportFragmentManager, "checkin_plan")
    }

    // ── assignment requests (package 6) ─────────────────────

    /**
     * Ask the admin for a MASKED customer seen in one of my plans. The
     * customer id comes from the server's own plan payload — the app never
     * sees (and can never request by) a name it isn't entitled to. Needs
     * the server (a request is a server-side workflow — no offline queue
     * here by design; the error paths say so honestly).
     */
    private fun showRequestAssign(plan: PlannedVisitEntity) {
        val input = EditText(this).apply {
            hint = getString(R.string.arq_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        var sending = false
        AlertDialog.Builder(this)
            .setTitle(R.string.arq_request_assign)
            .setMessage(getString(R.string.arq_offline_hint))
            .setView(wrap)
            .setPositiveButton(R.string.arq_submit) { d, _ ->
                if (sending) return@setPositiveButton
                sending = true
                val note = input.text?.toString()?.trim().orEmpty()
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            ApiClient.createAssignmentRequest(plan.customerId, note)
                        }
                        Toast.makeText(this@PlansActivity, R.string.arq_sent, Toast.LENGTH_LONG).show()
                        d.dismiss()
                    } catch (e: ApiClient.HttpException) {
                        sending = false
                        if (e.code == 401) {
                            d.dismiss()
                            showAuthExpired()
                        } else {
                            // the server's Persian error is actionable
                            // (duplicate pending / already assigned / …)
                            Toast.makeText(this@PlansActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Exception) {
                        sending = false
                        Toast.makeText(this@PlansActivity, R.string.arq_load_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.plan_close, null)
            .show()
    }

    /** My assignment requests with their outcomes (pending/approved/rejected). */
    private fun showMyRequests() {
        scope.launch {
            val json = try {
                withContext(Dispatchers.IO) { ApiClient.assignmentRequests() }
            } catch (e: ApiClient.HttpException) {
                if (e.code == 401) { showAuthExpired(); return@launch }
                Toast.makeText(this@PlansActivity, R.string.arq_load_error, Toast.LENGTH_LONG).show()
                return@launch
            } catch (_: Exception) {
                Toast.makeText(this@PlansActivity, R.string.arq_load_error, Toast.LENGTH_LONG).show()
                return@launch
            }
            val reqs = json.optJSONArray("requests") ?: run {
                Toast.makeText(this@PlansActivity, R.string.arq_load_error, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (reqs.length() == 0) {
                AlertDialog.Builder(this@PlansActivity)
                    .setTitle(R.string.arq_requests_title)
                    .setMessage(R.string.arq_none)
                    .setPositiveButton(R.string.plan_close, null)
                    .show()
                return@launch
            }
            val lines = ArrayList<String>()
            for (i in 0 until reqs.length()) {
                val r: JSONObject = reqs.optJSONObject(i) ?: continue
                val status = when (r.optString("status")) {
                    "approved" -> getString(R.string.arq_status_approved)
                    "rejected" -> getString(R.string.arq_status_rejected)
                    else -> getString(R.string.arq_status_pending)
                }
                val name = if (r.optBoolean("assigned")) r.optString("customerName") else getString(R.string.arq_customer_label)
                val sb = StringBuilder("«$name» — $status — ${isoToFaDate(r.optString("createdAt"))}")
                r.optString("note").takeIf { it.isNotEmpty() }?.let { sb.append("\n").append(getString(R.string.arq_note_sent, it)) }
                r.optString("adminNote").takeIf { it.isNotEmpty() }?.let { sb.append("\n").append(getString(R.string.arq_admin_reply, it)) }
                lines.add(sb.toString())
            }
            AlertDialog.Builder(this@PlansActivity)
                .setTitle(R.string.arq_requests_title)
                .setItems(lines.toTypedArray(), null)
                .setPositiveButton(R.string.plan_close, null)
                .show()
        }
    }

    private fun isoToFaDate(iso: String): String {
        return try {
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return iso
            val (y, mo, d) = m.destructured
            JalaliDate.formatFa(JalaliDate.fromGregorian(y.toInt(), mo.toInt(), d.toInt()))
        } catch (_: Exception) { iso }
    }

    // ── list rows ───────────────────────────────────────────

    private sealed class Row {
        data class Header(val title: String, val count: Int) : Row()
        data class Plan(val plan: PlannedVisitEntity) : Row()
    }

    private inner class PlansAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Row> = emptyList()

        override fun getItemViewType(position: Int) =
            if (items[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_plan_header, parent, false)
                HeaderVH(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_plan, parent, false)
                PlanVH(v)
            }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row)
                is Row.Plan -> (holder as PlanVH).bind(row.plan)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvSection: TextView = v.findViewById(R.id.tvSection)
            private val tvCount: TextView = v.findViewById(R.id.tvCount)
            fun bind(row: Row.Header) {
                tvSection.text = row.title
                tvCount.text = row.count.toString()
            }
        }

        inner class PlanVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvTime: TextView = v.findViewById(R.id.tvTime)
            private val tvDuration: TextView = v.findViewById(R.id.tvDuration)
            private val tvPriority: TextView = v.findViewById(R.id.tvPriority)
            private val tvStatus: TextView = v.findViewById(R.id.tvStatus)
            private val tvCustomer: TextView = v.findViewById(R.id.tvCustomer)
            private val tvVisit: TextView = v.findViewById(R.id.tvVisit)
            private val tvNote: TextView = v.findViewById(R.id.tvNote)
            private val card: View = itemView

            fun bind(plan: PlannedVisitEntity) {
                tvTime.text = plan.timeLocal
                tvDuration.text = getString(R.string.plan_duration_min, plan.durationMin.toString())

                // mission item 3 — the notification-tap focus highlight: an
                // amber STROKE (keeps the card's own background intact)
                (card as? com.google.android.material.card.MaterialCardView)?.let { cv ->
                    if (highlightedPlanId == plan.id) {
                        cv.strokeWidth = (resources.displayMetrics.density * 2).toInt()
                        cv.setStrokeColor(0xFFF59E0B.toInt())
                    } else {
                        cv.strokeWidth = 0
                    }
                }

                // priority badge (high/low only — normal needs no noise)
                when (plan.priority) {
                    "high" -> {
                        tvPriority.text = getString(R.string.plan_priority_high)
                        tvPriority.setBackgroundResource(R.drawable.bg_badge_red)
                        tvPriority.setTextColor(0xFF991B1B.toInt())
                        tvPriority.visibility = View.VISIBLE
                    }
                    "low" -> {
                        tvPriority.text = getString(R.string.plan_priority_low)
                        tvPriority.setBackgroundResource(R.drawable.bg_badge_stone)
                        tvPriority.setTextColor(0xFF57534E.toInt())
                        tvPriority.visibility = View.VISIBLE
                    }
                    else -> tvPriority.visibility = View.GONE
                }

                // status badge + overdue
                when {
                    plan.status == "done" -> {
                        tvStatus.text = getString(R.string.plan_status_done)
                        tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
                        tvStatus.setTextColor(0xFF166534.toInt())
                    }
                    plan.status == "cancelled" -> {
                        tvStatus.text = getString(R.string.plan_status_cancelled)
                        tvStatus.setBackgroundResource(R.drawable.bg_badge_gray)
                        tvStatus.setTextColor(0xFF374151.toInt())
                    }
                    plan.status == "needs_reschedule" -> {
                        tvStatus.text = getString(R.string.plan_status_needs_reschedule)
                        tvStatus.setBackgroundResource(R.drawable.bg_badge_blue)
                        tvStatus.setTextColor(0xFF1E40AF.toInt())
                    }
                    plan.overdue -> {
                        tvStatus.text = getString(R.string.plan_overdue)
                        tvStatus.setBackgroundResource(R.drawable.bg_badge_red)
                        tvStatus.setTextColor(0xFF991B1B.toInt())
                    }
                    else -> {
                        tvStatus.text = getString(R.string.plan_status_scheduled)
                        tvStatus.setBackgroundResource(R.drawable.bg_badge_amber)
                        tvStatus.setTextColor(0xFF92400E.toInt())
                    }
                }

                tvCustomer.text = if (plan.customerAssigned) {
                    plan.customerName ?: ""
                } else {
                    getString(R.string.plan_customer_masked_name)
                }

                if (plan.status == "done") {
                    tvVisit.text = if (plan.visitOpen) {
                        getString(R.string.plan_visit_open_now)
                    } else {
                        plan.visitStartLocal?.let { getString(R.string.plan_visit_at, it) } ?: ""
                    }
                    tvVisit.visibility = if (tvVisit.text.isNotEmpty()) View.VISIBLE else View.GONE
                } else {
                    tvVisit.visibility = View.GONE
                }

                if (!plan.note.isNullOrEmpty()) {
                    tvNote.text = plan.note
                    tvNote.visibility = View.VISIBLE
                } else {
                    tvNote.visibility = View.GONE
                }

                // red stroke on the card for overdue open plans
                val cardView = card as? com.google.android.material.card.MaterialCardView
                cardView?.strokeColor = if (plan.status == "scheduled" && plan.overdue) {
                    0xFFFCA5A5.toInt()
                } else {
                    android.graphics.Color.TRANSPARENT
                }

                card.setOnClickListener { showDetails(plan) }
            }
        }
    }
}
