package com.medpad.tracker.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medpad.tracker.R
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.sync.Customers360
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Customer 360 (phase 1) — the customers list screen.
 *
 * Shows every customer in the local cache: ASSIGNED customers (the LWW
 * mirror) plus the rep's own OFFLINE-CREATED ones (temp rows, honest
 * «در انتظار همگام‌سازی» badge). Search covers person name, clinic name,
 * phones, specialty and city; filters separate physicians from clinics and
 * active from inactive. Tapping a row opens the 360 profile; the header's
 * + opens the shared create form (offline-capable).
 *
 * The screen also surfaces the customer-op queue state (waiting / failed
 * with the server's Persian error) — same honesty rules as the check-in
 * queue on the home screen.
 */
class CustomersActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_CREATE = "medpad.extra.OPEN_CREATE"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvQueueBanner: TextView
    private lateinit var adapter: CustomersAdapter

    private var all: List<Doctor> = emptyList()
    private var query = ""
    private var fType = "all"     // all | doctor | clinic | pharmacy | other
    private var fActive = "all"   // all | active | inactive

    // filter chip pair: (key, label, tint color)
    private data class Chip(val key: String, val label: String, val tint: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customers)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnAdd).setOnClickListener {
            CustomerFormDialog.show(this, null)
        }

        recycler = findViewById(R.id.recycler)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvQueueBanner = findViewById(R.id.tvQueueBanner)
        adapter = CustomersAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        (findViewById<android.view.View>(R.id.edSearch) as? android.widget.EditText)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        buildChips()

        // opened from the home card's long-press / direct create intent
        if (intent.getBooleanExtra(EXTRA_OPEN_CREATE, false)) {
            CustomerFormDialog.show(this, null)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        // resume = a flush opportunity (same rule as the home screen)
        scope.launch(Dispatchers.IO) {
            try { Customers360.flush() } catch (_: Exception) { }
            withContext(Dispatchers.Main) { reload() }
        }
    }

    private fun reload() {
        scope.launch {
            val (rows, waiting, failed) = withContext(Dispatchers.IO) {
                val db = AppDatabase.get(this@CustomersActivity)
                Triple(db.doctorDao().all(), Customers360.pendingCount(), Customers360.failedItems().size)
            }
            all = rows
            when {
                failed > 0 -> {
                    tvQueueBanner.text = getString(R.string.customers_queue_failed, failed.toString())
                    tvQueueBanner.setTextColor(ContextCompat.getColor(this@CustomersActivity, R.color.status_err))
                    tvQueueBanner.visibility = View.VISIBLE
                }
                waiting > 0 -> {
                    tvQueueBanner.text = getString(R.string.customers_queue_waiting, waiting.toString())
                    tvQueueBanner.setTextColor(ContextCompat.getColor(this@CustomersActivity, R.color.status_warn))
                    tvQueueBanner.visibility = View.VISIBLE
                }
                else -> tvQueueBanner.visibility = View.GONE
            }
            applyFilter()
        }
    }

    /** type + status filter chips — a horizontal row of small toggle buttons. */
    private fun buildChips() {
        val box = findViewById<android.widget.LinearLayout>(R.id.boxChips)
        box.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()

        fun chip(label: String, selected: Boolean, tint: Int, onClick: () -> Unit): Button {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minHeight = 0
                minWidth = 0
                setPadding(pad, pad / 2, pad, pad / 2)
                gravity = Gravity.CENTER
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.marginEnd = pad / 2
                layoutParams = lp
                if (selected) {
                    setTextColor(0xFFFFFFFF.toInt())
                    background.setTint(tint)
                } else {
                    setTextColor(ContextCompat.getColor(this@CustomersActivity, R.color.gray_500))
                    background.setTint(ContextCompat.getColor(this@CustomersActivity, R.color.gray_200))
                }
                setOnClickListener { onClick() }
            }
            return b
        }

        val types = listOf(
            "all" to getString(R.string.filter_all),
            "doctor" to getString(R.string.type_doctor),
            "clinic" to getString(R.string.type_clinic),
            "pharmacy" to getString(R.string.type_pharmacy),
            "other" to getString(R.string.type_other),
        )
        types.forEach { (k, label) ->
            box.addView(chip(label, fType == k, 0xFF2563EB.toInt()) {
                fType = k; buildChips(); applyFilter()
            })
        }
        box.addView(chip(getString(R.string.filter_active_only), fActive == "active", 0xFF059669.toInt()) {
            fActive = if (fActive == "active") "all" else "active"; buildChips(); applyFilter()
        })
    }

    private fun applyFilter() {
        val q = query.replace(Regex("\\s+"), " ")
        val list = all.filter { d ->
            if (fType != "all" && d.type != fType) return@filter false
            if (fActive == "active" && !d.active) return@filter false
            if (q.isEmpty()) return@filter true
            val hay = listOfNotNull(
                d.name, d.lastName, d.phone, d.phone2, d.address, d.city,
                d.district, d.specialty, d.title,
            ).joinToString(" ")
            hay.contains(q)
        }
        adapter.items = list
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── the list adapter ────────────────────────────────────
    inner class CustomersAdapter : RecyclerView.Adapter<CustomersAdapter.VH>() {
        var items: List<Doctor> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvBadgeType: TextView = v.findViewById(R.id.tvBadgeType)
            val tvBadgeState: TextView = v.findViewById(R.id.tvBadgeState)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvSub: TextView = v.findViewById(R.id.tvSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = items[pos]
            h.tvBadgeType.text = when (d.type) {
                "doctor" -> getString(R.string.type_doctor)
                "clinic" -> getString(R.string.type_clinic)
                "pharmacy" -> getString(R.string.type_pharmacy)
                else -> getString(R.string.type_other)
            }
            // state badge: offline-pending / inactive
            when {
                d.pendingLocalId != null -> {
                    h.tvBadgeState.text = getString(R.string.customer_pending_sync)
                    h.tvBadgeState.setTextColor(ContextCompat.getColor(this@CustomersActivity, R.color.status_warn))
                    h.tvBadgeState.setBackgroundResource(R.drawable.bg_badge_amber)
                    h.tvBadgeState.visibility = View.VISIBLE
                }
                !d.active -> {
                    h.tvBadgeState.text = getString(R.string.customer_inactive)
                    h.tvBadgeState.setTextColor(ContextCompat.getColor(this@CustomersActivity, R.color.status_err))
                    h.tvBadgeState.setBackgroundResource(R.drawable.bg_badge_red)
                    h.tvBadgeState.visibility = View.VISIBLE
                }
                else -> h.tvBadgeState.visibility = View.GONE
            }
            h.tvName.text = d.fullName
            val sub = mutableListOf<String>()
            if (!d.specialty.isNullOrEmpty()) sub.add(d.specialty)
            if (!d.city.isNullOrEmpty()) sub.add(d.city)
            if (!d.phone.isNullOrEmpty()) sub.add(d.phone)
            h.tvSub.text = sub.joinToString(" · ")
            h.itemView.setOnClickListener {
                // 360 profile — assigned OR own offline-created rows only;
                // unassigned server rows are not in the local cache anyway
                startActivity(
                    Intent(this@CustomersActivity, CustomerProfileActivity::class.java)
                        .putExtra(CustomerProfileActivity.EXTRA_CUSTOMER_ID, d.id)
                )
            }
            h.itemView.setOnLongClickListener {
                CustomerFormDialog.show(this@CustomersActivity, d) { reload() }
                true
            }
        }
    }
}
