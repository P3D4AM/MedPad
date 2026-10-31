package com.medpad.tracker.sync

import org.json.JSONObject

/**
 * Mission item 7 (Oct 31) — the ONE-OPEN-CHECK-IN gate.
 *
 * A rep may start a new check-in ONLY when it is certain no open check-in
 * exists: the server is the source of truth (it enforces the rule with a
 * partial unique index + a transaction), and the app must not create a
 * second, CONTRADICTORY open record through the offline queue. The rules:
 *
 *  1. the local queue holds an UNSENT check-in (pending/sending) → its
 *     open-state is unresolved → BLOCK (the item is still going to land).
 *  2. the server is unreachable (offline) and (1) is clear → the server
 *     state is UNKNOWN → BLOCK. The mission is explicit: «ثبت درخواست
 *     ویزیت جدید تا تأیید سرور معلق بماند. دو رکورد باز متناقض ایجاد
 *     نکن.» — a plan-less check-in is exactly the case where the device
 *     cannot know.
 *  3. the server answers: an open visit → BLOCK with the visit's identity
 *     (the UI shows what/when/planned and offers checkout). No open visit
 *     → ALLOW.
 *
 * Pure logic (no Android types) so the policy is unit-testable; the UI
 * (MainActivity) renders the decision.
 */
object CheckinGate {

    data class OpenVisit(
        val id: String?,
        val customerId: String?,
        val customerName: String?,
        val startAtMs: Long?, // epoch ms (null when unparsable)
        val planned: Boolean,
    )

    sealed class Decision {
        object Allow : Decision()
        data class BlockUnsent(val count: Int) : Decision()
        object BlockOffline : Decision()
        data class BlockOpenVisit(val visit: OpenVisit) : Decision()
    }

    /** Parse the server's GET /api/app/checkout answer (openVisits array).
     *  Tolerant of old panels (missing fields → nulls) — a missing planned
     *  flag reads as plan-less, never a crash. */
    fun parseOpenVisits(resp: JSONObject?): List<OpenVisit> {
        if (resp == null || !resp.optBoolean("ok", false)) return emptyList()
        val arr = resp.optJSONArray("openVisits") ?: return emptyList()
        val out = ArrayList<OpenVisit>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val startAt = try {
                java.time.Instant.parse(v.optString("startAt")).toEpochMilli()
            } catch (_: Exception) {
                null
            }
            out.add(
                OpenVisit(
                    id = v.optString("id", "").takeIf { it.isNotEmpty() },
                    customerId = if (v.has("customerId") && !v.isNull("customerId")) v.optString("customerId") else null,
                    customerName = if (v.has("customerName") && !v.isNull("customerName")) v.optString("customerName") else null,
                    startAtMs = startAt,
                    planned = v.optBoolean("planned", false),
                ),
            )
        }
        return out
    }

    /** The gate. [serverResp] null = the server could not be reached. */
    fun decide(localPending: Int, serverResp: JSONObject?): Decision {
        if (localPending > 0) return Decision.BlockUnsent(localPending)
        if (serverResp == null) return Decision.BlockOffline
        val open = parseOpenVisits(serverResp)
        if (serverResp.optBoolean("ok", false)) {
            return open.firstOrNull()?.let { Decision.BlockOpenVisit(it) } ?: Decision.Allow
        }
        // an error answer (e.g. 401) is as unknowable as being offline
        return Decision.BlockOffline
    }

    /** Elapsed display for an open visit: whole hours + minutes, Persian
     *  friendly (numbers rendered by the caller with fa digits if desired). */
    fun elapsedLabel(nowMs: Long, startAtMs: Long?): String? {
        if (startAtMs == null || startAtMs <= 0 || nowMs < startAtMs) return null
        val totalMin = ((nowMs - startAtMs) / 60_000).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h:$m" else m.toString()
    }
}
