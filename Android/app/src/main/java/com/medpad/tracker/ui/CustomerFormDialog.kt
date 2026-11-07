package com.medpad.tracker.ui

import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.medpad.tracker.R
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.Doctor
import com.medpad.tracker.sync.Customers360
import com.medpad.tracker.util.IranCities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Customer 360 (phase 1) — the shared customer create/edit form.
 *
 * ONE form serves both entry points (the customers list and the map's
 * long-press), with type-aware fields:
 *  * physician (type=doctor) — lastName / professional title / specialty
 *  * clinic (type=clinic)    — purchase manager
 *  * both — second phone, district, postal code, general notes
 *
 * OFFLINE-FIRST by construction: every submit goes through the
 * pending_customer_ops queue (Customers360) and is flushed immediately —
 * online it confirms within the dialog (same UX as before); offline it
 * stays queued with an honest «در صف همگام‌سازی» message and the temp row
 * is visible in the list immediately. localId idempotency makes the later
 * replay duplicate-safe (server: Customer.appLocalId).
 *
 * Phone validation (mission §6.3): Persian digits are normalized, a
 * non-empty phone must be 10–13 digits, otherwise the field complains in
 * Persian and nothing is sent.
 */
object CustomerFormDialog {

    /** normalize Persian/Arabic digits to ASCII for validation + storage */
    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** a valid phone: empty (ok) or 10..13 digits (after normalization). */
    fun validPhone(raw: String): Boolean {
        val n = normalizeDigits(raw).replace(Regex("[\\s()-]"), "")
        if (n.isEmpty()) return true
        if (!n.all { it.isDigit() }) return false
        return n.length in 10..13
    }

    fun show(
        activity: AppCompatActivity,
        existing: Doctor?,                 // null = create
        presetLat: Double? = null,         // create: map long-press position
        presetLng: Double? = null,
        onSaved: () -> Unit = {},
    ) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val scroll = android.widget.ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        scroll.addView(box)

        fun field(hintRes: Int, initial: String = "", ltr: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
            val layout = TextInputLayout(activity).apply { setHint(activity.getString(hintRes)) }
            val edit = TextInputEditText(activity).apply {
                setText(initial)
                if (ltr) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    textDirection = View.TEXT_DIRECTION_LTR
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }
            }
            layout.addView(edit)
            box.addView(layout)
            return layout to edit
        }

        val typeLabels = arrayOf(
            activity.getString(R.string.type_doctor),
            activity.getString(R.string.type_clinic),
            activity.getString(R.string.type_pharmacy),
            activity.getString(R.string.type_other),
        )
        val typeValues = arrayOf("doctor", "clinic", "pharmacy", "other")

        val spType = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.customer_type)
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 4)
        })
        box.addView(spType)

        val (nameLayout, edName) = field(R.string.customer_name, existing?.name ?: "")
        val (lastNameLayout, edLastName) = field(R.string.customer_lastname, existing?.lastName ?: "")
        val (titleLayout, edTitle) = field(R.string.customer_pro_title, existing?.title ?: "")
        val (specialtyLayout, edSpecialty) = field(R.string.customer_specialty, existing?.specialty ?: "")
        val (managerLayout, edManager) = field(R.string.customer_manager, existing?.managerName ?: "")
        val (phoneLayout, edPhone) = field(R.string.customer_phone, existing?.phone ?: "", ltr = true)
        val (phone2Layout, edPhone2) = field(R.string.customer_phone2, existing?.phone2 ?: "", ltr = true)
        val (addressLayout, edAddress) = field(R.string.customer_address, existing?.address ?: "")
        val (districtLayout, edDistrict) = field(R.string.customer_district, existing?.district ?: "")
        val (postalLayout, edPostal) = field(R.string.customer_postal, existing?.postalCode ?: "", ltr = true)
        val (notesLayout, edNotes) = field(R.string.customer_notes, existing?.notes ?: "")

        fun showCityPicker(act: AppCompatActivity, onPick: (String, String) -> Unit) {
            val entries = IranCities.load(act)
            if (entries.isEmpty()) {
                Toast.makeText(act, R.string.city_dataset_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val pickerView = LayoutInflater.from(act).inflate(R.layout.dialog_city_picker, null)
            val search = pickerView.findViewById<EditText>(R.id.edCitySearch)
            val list = pickerView.findViewById<ListView>(R.id.lvCities)
            var currentResults: List<IranCities.Entry> = emptyList()
            fun render(q: String) {
                currentResults = IranCities.search(act, q)
                val labels = ArrayList<String>(currentResults.size + 1)
                labels.add(act.getString(R.string.city_clear))
                currentResults.forEach { labels.add("${it.city} — ${it.province}") }
                list.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)
            }
            render("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = render(s?.toString() ?: "")
            })
            val picker = AlertDialog.Builder(act)
                .setTitle(R.string.city_picker_title)
                .setView(pickerView)
                .setNegativeButton(R.string.cancel, null)
                .show()
            list.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) {
                    onPick("", "") // explicit clear
                } else {
                    currentResults.getOrNull(position - 1)?.let { e ->
                        onPick(e.city, e.province)
                    }
                }
                picker.dismiss()
            }
        }

        // city picker (package 8 dataset) — read-only trigger field
        var pickedCity: Pair<String, String>? = existing?.let { d ->
            if (!d.city.isNullOrEmpty() && !d.province.isNullOrEmpty()) d.city to d.province else null
        }
        val cityField = TextInputLayout(activity).apply { setHint(activity.getString(R.string.customer_city)) }
        val edCity = TextInputEditText(activity).apply {
            keyListener = null // picker-only, no free text
            isFocusable = false
            isClickable = true
            setOnClickListener {
                showCityPicker(activity) { c, p ->
                    pickedCity = c to p
                    if (c.isBlank()) setText("") else setText("$c — $p")
                }
            }
            existing?.let { d ->
                if (!d.city.isNullOrEmpty()) {
                    setText(if (!d.province.isNullOrEmpty()) "${d.city} — ${d.province}" else d.city)
                }
            }
        }
        cityField.addView(edCity)
        box.addView(cityField)

        // type-aware visibility (physician ↔ clinic fields)
        fun renderTypeFields() {
            val t = typeValues[spType.selectedItemPosition]
            val isDoctor = t == "doctor"
            val isClinic = t == "clinic"
            lastNameLayout.visibility = if (isDoctor) View.VISIBLE else View.GONE
            titleLayout.visibility = if (isDoctor) View.VISIBLE else View.GONE
            specialtyLayout.visibility = if (isDoctor) View.VISIBLE else View.GONE
            managerLayout.visibility = if (isClinic) View.VISIBLE else View.GONE
        }
        spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = renderTypeFields()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spType.setSelection(
            typeValues.indexOf(existing?.type ?: "doctor").coerceAtLeast(0)
        )
        renderTypeFields()

        AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.add_customer else R.string.edit_customer)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        // validation (mission §6.3 — Persian, field-level)
                        val name = edName.text?.toString()?.trim().orEmpty()
                        if (name.isEmpty()) {
                            nameLayout.error = activity.getString(R.string.err_name_required)
                            return@setOnClickListener
                        }
                        nameLayout.error = null
                        val phone = normalizeDigits(edPhone.text?.toString()?.trim().orEmpty())
                        val phone2 = normalizeDigits(edPhone2.text?.toString()?.trim().orEmpty())
                        if (!validPhone(phone)) {
                            phoneLayout.error = activity.getString(R.string.err_phone_invalid)
                            return@setOnClickListener
                        }
                        phoneLayout.error = null
                        if (!validPhone(phone2)) {
                            phone2Layout.error = activity.getString(R.string.err_phone_invalid)
                            return@setOnClickListener
                        }
                        phone2Layout.error = null

                        val type = typeValues[spType.selectedItemPosition]
                        val cityPair = pickedCity?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
                        // create needs coordinates: preset (map long-press) or
                        // the city center as the honest labelled default
                        var lat = existing?.lat ?: presetLat ?: cityPair?.let {
                            IranCities.load(activity).firstOrNull { e -> e.city == it.first && e.province == it.second }?.lat
                        } ?: 35.72
                        var lng = existing?.lng ?: presetLng ?: cityPair?.let {
                            IranCities.load(activity).firstOrNull { e -> e.city == it.first && e.province == it.second }?.lng
                        } ?: 51.4

                        val form = ApiClient.CustomerForm(
                            name = name,
                            type = type,
                            address = edAddress.text?.toString()?.trim().orEmpty(),
                            phone = phone,
                            lat = lat,
                            lng = lng,
                            city = cityPair?.first,
                            province = cityPair?.second,
                            lastName = edLastName.text?.toString()?.trim().orEmpty(),
                            title = edTitle.text?.toString()?.trim().orEmpty(),
                            specialty = edSpecialty.text?.toString()?.trim().orEmpty(),
                            managerName = edManager.text?.toString()?.trim().orEmpty(),
                            phone2 = phone2,
                            district = edDistrict.text?.toString()?.trim().orEmpty(),
                            postalCode = normalizeDigits(edPostal.text?.toString()?.trim().orEmpty()),
                            notes = edNotes.text?.toString()?.trim().orEmpty(),
                        )

                        setCancelable(false)
                        getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        CoroutineScope(Dispatchers.Main).launch {
                            var ok = false
                            var queued = false
                            // owner fix — the real server id of the customer
                            // THIS dialog just created (flush confirmed it);
                            // the assignment request targets exactly it
                            var createdServerId: String? = null
                            withContext(Dispatchers.IO) {
                                try {
                                    if (existing == null) {
                                        val temp = Customers360.enqueueCreate(form)
                                        // the temp id is "local-<localId>" — the
                                        // flush maps that localId to the real id
                                        val localId = temp.id.removePrefix("local-")
                                        Customers360.createdIds.remove(localId) // drop a stale mapping
                                        Customers360.flush()
                                        createdServerId = Customers360.createdIds[localId]
                                    } else {
                                        Customers360.enqueueEdit(existing.id, form)
                                    }
                                    // immediate flush — online confirms instantly
                                    val after = Customers360.pendingCount()
                                    queued = after > 0
                                    ok = true
                                } catch (_: Exception) {
                                    ok = false
                                }
                            }
                            setCancelable(true)
                            getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            if (ok) {
                                dismiss()
                                onSaved()
                                if (queued) {
                                    Toast.makeText(
                                        activity,
                                        if (existing == null) R.string.customer_created_queued else R.string.customer_edit_queued,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else if (existing == null) {
                                    // mission-12/item-1 honest flow: online
                                    // create is registered but UNASSIGNED —
                                    // offer the assignment request
                                    val requestTargetId = createdServerId
                                    AlertDialog.Builder(activity)
                                        .setTitle(R.string.customer_saved_unassigned_title)
                                        .setMessage(R.string.customer_saved_unassigned_body)
                                        .setPositiveButton(R.string.customer_request_assignment) { _, _ ->
                                            if (requestTargetId == null) {
                                                // offline-queued create — the server id
                                                // does not exist yet; the request must
                                                // wait (honest message, no wrong guess)
                                                Toast.makeText(
                                                    activity,
                                                    R.string.customer_request_failed,
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                                return@setPositiveButton
                                            }
                                            CoroutineScope(Dispatchers.Main).launch {
                                                var sent = false
                                                var errMsg: String? = null
                                                try {
                                                    // owner fix — request assignment for
                                                    // EXACTLY the customer this dialog
                                                    // created (the old "newest non-local
                                                    // row" guess could target another
                                                    // customer and 409/404)
                                                    sent = withContext(Dispatchers.IO) {
                                                        ApiClient.createAssignmentRequest(requestTargetId, "")
                                                            .optBoolean("ok")
                                                    }
                                                } catch (e: ApiClient.HttpException) {
                                                    errMsg = e.message
                                                } catch (e: Exception) {
                                                    errMsg = e.javaClass.simpleName +
                                                        (e.message?.let { ": $it" } ?: "")
                                                }
                                                Toast.makeText(
                                                    activity,
                                                    when {
                                                        sent -> activity.getString(R.string.request_sent)
                                                        else -> activity.getString(
                                                            R.string.arq_send_error,
                                                            errMsg ?: activity.getString(R.string.customer_request_failed),
                                                        )
                                                    },
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                        .setNegativeButton(R.string.customer_no_request, null)
                                        .show()
                                } else {
                                    Toast.makeText(activity, R.string.customer_saved, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(activity, R.string.sync_error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                show()
            }
    }

}
