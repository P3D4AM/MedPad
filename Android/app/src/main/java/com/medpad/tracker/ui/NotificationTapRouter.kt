package com.medpad.tracker.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * phase 2 / §1 (daily app capabilities) — notification TAP-THROUGH routing.
 *
 * Before this, every notification was built without a contentIntent: tapping
 * it did NOTHING. Now each notification carries a PendingIntent that routes
 * by its kind to the related screen:
 *
 *   assignment → MAP   (the customer just assigned — the map is where the
 *                       rep sees their assigned customers)
 *   proximity  → MAP   (the nearby customer)
 *   plan       → PLANS (the visit plan screen)
 *   info       → HOME  (and any UNKNOWN kind — safe default, never a crash)
 *
 * The intent ALWAYS targets MainActivity — the guarded hub — never a leaf
 * screen directly. MainActivity then:
 *   1. checks the login state. Not logged in (cold start after logout /
 *      expired session)? → the requested route is PERSISTED
 *      (savePending) and the user lands on LoginActivity; after a
 *      successful re-login MainActivity consumes the pending route
 *      (takePending) and forwards to the destination — so the tap
 *      destination opens even after the login detour (requirement §1:
 *      "بازشدن مقصد … پس از ورود مجدد نیز آزمایش شود").
 *   2. checks the referenced customer (map routes): if the id is unknown
 *      locally (deleted / unassigned) the map still opens, with an honest
 *      toast — never a crash, never a dead end (requirement §1: "اگر …
 *      رکورد حذف شده باشد، رفتار قابل فهم و امن باشد").
 *
 * PendingIntent flags: FLAG_IMMUTABLE (API 31+ requirement) +
 * FLAG_UPDATE_CURRENT (same-kind notifications refresh the extras instead
 * of piling up stale ones). requestCode is derived from the KIND so
 * different kinds keep distinct intents while repeats of one kind update
 * in place.
 */
object NotificationTapRouter {

    const val ROUTE_HOME = "home"
    const val ROUTE_PLANS = "plans"
    const val ROUTE_MAP = "map"

    const val EXTRA_ROUTE = "medpad.extra.ROUTE"
    const val EXTRA_CUSTOMER_ID = "medpad.extra.CUSTOMER_ID"

    data class Route(val route: String, val customerId: String? = null)

    /** kind → destination. Unknown/null kinds default to HOME — the safe
     *  screen; the tap must always land somewhere sensible. */
    fun routeForKind(kind: String?): String = when (kind) {
        "plan" -> ROUTE_PLANS
        "assignment", "proximity", "request" -> ROUTE_MAP // customer-centric kinds
        else -> ROUTE_HOME // info + anything unknown
    }

    /** The contentIntent every notification carries. */
    fun pendingIntent(context: Context, kind: String?, customerId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ROUTE, routeForKind(kind))
            if (!customerId.isNullOrEmpty()) putExtra(EXTRA_CUSTOMER_ID, customerId)
        }
        return PendingIntent.getActivity(
            context,
            (kind ?: "info").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Parse a launch intent back into a Route (null when it carries no
     *  medpad route extra — e.g. a plain launcher open). */
    fun routeFrom(intent: Intent): Route? {
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return null
        if (route != ROUTE_HOME && route != ROUTE_PLANS && route != ROUTE_MAP) return null
        val customerId = intent.getStringExtra(EXTRA_CUSTOMER_ID)
        return Route(route, customerId?.takeIf { it.isNotEmpty() })
    }

    // ── pending-route persistence (survives the login detour + cold start) ──

    /** Persist the route the user asked for but could not yet receive
     *  (login required). Overwrites any earlier pending route — the most
     *  RECENT tap is what the user expects to land on. */
    fun savePending(context: Context, route: Route) {
        context.getSharedPreferences("medpad_taproute", Context.MODE_PRIVATE)
            .edit()
            .putString("route", route.route)
            .putString("customerId", route.customerId ?: "")
            .apply()
    }

    /** Read AND CLEAR the pending route (single consumption — a stale route
     *  must not hijack a later normal open). */
    fun takePending(context: Context): Route? {
        val sp = context.getSharedPreferences("medpad_taproute", Context.MODE_PRIVATE)
        val route = sp.getString("route", null) ?: return null
        val customerId = sp.getString("customerId", null)?.takeIf { it.isNotEmpty() }
        sp.edit().clear().apply()
        if (route != ROUTE_HOME && route != ROUTE_PLANS && route != ROUTE_MAP) return null
        return Route(route, customerId)
    }
}
