package com.medpad.tracker.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * phase 2 / §1 — notification TAP-THROUGH routing.
 * mission item 3 (Oct 31) — ENTITY-level destinations.
 *
 * Before phase 2/§1 every notification was built without a contentIntent:
 * tapping it did NOTHING. That fix routed by KIND to a SCREEN:
 *
 *   assignment → MAP   (the assigned customer)
 *   proximity  → MAP   (the nearby customer)
 *   plan       → PLANS (the visit plan screen)
 *   info       → HOME  (safe default)
 *
 * Mission item 3 goes one level deeper: the payload now carries
 * entityType/entityId naming the exact RECORD, and the tap opens THAT
 * record —
 *
 *   customer → MAP centred on that customer's marker (info window open)
 *   plan     → PLANS on that plan's day, the row highlighted and its
 *              details dialog opened
 *   request  → PLANS with the «درخواست‌های من» dialog open (the rejected
 *              assignment request the notification is about)
 *
 * A payload WITHOUT entity fields (historical rows, old panels) degrades
 * to the kind mapping above — never a crash, never a dead end. The intent
 * ALWAYS targets MainActivity — the guarded hub — never a leaf screen:
 *   1. not logged in (cold start after logout / expired session) → the
 *      requested route is PERSISTED (savePending, entity ids included)
 *      and consumed after the re-login detour;
 *   2. the referenced record may be gone (deleted / unassigned / another
 *      day's plan) → an honest toast, the destination screen still opens;
 *   3. a repeated intent (double-tap on the same notification, or the
 *      single-top redelivery) is collapsed by the caller's signature
 *      guard — the destination opens once.
 *
 * PendingIntent flags: FLAG_IMMUTABLE (API 31+ requirement) +
 * FLAG_UPDATE_CURRENT (same-signature notifications refresh the extras
 * instead of piling up stale ones). requestCode is derived from the
 * signature so different destinations keep distinct intents.
 */
object NotificationTapRouter {

    const val ROUTE_HOME = "home"
    const val ROUTE_PLANS = "plans"
    const val ROUTE_MAP = "map"

    // entity types understood by the app (unknown server values degrade to
    // the kind mapping — the server may grow new types before the app does)
    const val ENTITY_CUSTOMER = "customer"
    const val ENTITY_PLAN = "plan"
    const val ENTITY_REQUEST = "request"

    const val EXTRA_ROUTE = "medpad.extra.ROUTE"
    const val EXTRA_CUSTOMER_ID = "medpad.extra.CUSTOMER_ID"
    const val EXTRA_ENTITY_TYPE = "medpad.extra.ENTITY_TYPE"
    const val EXTRA_ENTITY_ID = "medpad.extra.ENTITY_ID"

    /** A resolved tap destination. entityType/entityId name the exact
     *  record (mission item 3); customerId is kept for the map screen
     *  (customer entities land there too). */
    data class Route(
        val route: String,
        val customerId: String? = null,
        val entityType: String? = null,
        val entityId: String? = null,
    ) {
        /** Stable identity of the destination — used to collapse duplicate
         *  deliveries of the same tap within a short window. */
        val signature: String get() = "$route|$customerId|$entityType|$entityId"
    }

    /** kind → destination. Unknown/null kinds default to HOME — the safe
     *  screen; the tap must always land somewhere sensible. */
    fun routeForKind(kind: String?): String = when (kind) {
        "plan" -> ROUTE_PLANS
        "assignment", "proximity", "request" -> ROUTE_MAP // customer-centric kinds
        else -> ROUTE_HOME // info + anything unknown
    }

    /** mission item 3 — entity-aware destination. An entity payload wins
     *  over the kind; without one the kind mapping decides (the legacy
     *  customerId still focuses the map for customer-centric kinds). */
    fun routeForEntity(kind: String?, customerId: String? = null, entityType: String? = null, entityId: String? = null): Route {
        val id = entityId?.takeIf { it.isNotEmpty() }
        val legacyCus = customerId?.takeIf { it.isNotEmpty() }
        return when (entityType) {
            ENTITY_PLAN -> Route(ROUTE_PLANS, entityType = ENTITY_PLAN, entityId = id)
            ENTITY_REQUEST -> Route(ROUTE_PLANS, entityType = ENTITY_REQUEST, entityId = id)
            ENTITY_CUSTOMER -> Route(
                ROUTE_MAP,
                customerId = id ?: legacyCus,
                entityType = ENTITY_CUSTOMER,
                entityId = id ?: legacyCus,
            )
            else -> {
                // no (recognized) entity — the kind mapping, with the
                // legacy customerId when the server still sends it
                val route = routeForKind(kind)
                Route(route, customerId = if (route == ROUTE_MAP) (id ?: legacyCus) else null)
            }
        }
    }

    /** The contentIntent every notification carries. */
    fun pendingIntent(
        context: Context,
        kind: String?,
        customerId: String? = null,
        entityType: String? = null,
        entityId: String? = null,
    ): PendingIntent {
        val route = routeForEntity(kind, customerId, entityType, entityId)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ROUTE, route.route)
            if (!route.customerId.isNullOrEmpty()) putExtra(EXTRA_CUSTOMER_ID, route.customerId)
            if (!route.entityType.isNullOrEmpty()) putExtra(EXTRA_ENTITY_TYPE, route.entityType)
            if (!route.entityId.isNullOrEmpty()) putExtra(EXTRA_ENTITY_ID, route.entityId)
        }
        return PendingIntent.getActivity(
            context,
            // signature-derived request code: distinct destinations keep
            // distinct PendingIntents; repeats of one destination refresh
            // in place (FLAG_UPDATE_CURRENT)
            (route.signature + ":" + (kind ?: "info")).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Parse a launch intent back into a Route (null when it carries no
     *  medpad route extra — e.g. a plain launcher open). Unknown route
     *  values (forged) are rejected, not forwarded. */
    fun routeFrom(intent: Intent): Route? {
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return null
        if (route != ROUTE_HOME && route != ROUTE_PLANS && route != ROUTE_MAP) return null
        val customerId = intent.getStringExtra(EXTRA_CUSTOMER_ID)?.takeIf { it.isNotEmpty() }
        val entityType = intent.getStringExtra(EXTRA_ENTITY_TYPE)?.takeIf { it.isNotEmpty() }
        val entityId = intent.getStringExtra(EXTRA_ENTITY_ID)?.takeIf { it.isNotEmpty() }
        // entity-type forgery hardening: only known types are forwarded
        if (entityType != null &&
            entityType != ENTITY_CUSTOMER && entityType != ENTITY_PLAN && entityType != ENTITY_REQUEST
        ) {
            return Route(route, customerId)
        }
        return Route(route, customerId, entityType, entityId)
    }

    // ── pending-route persistence (survives the login detour + cold start) ──

    /** Persist the route the user asked for but could not yet receive
     *  (login required). Overwrites any earlier pending route — the most
     *  RECENT tap is what the user expects to land on. Entity ids are
     *  persisted too so the post-login destination is still exact. */
    fun savePending(context: Context, route: Route) {
        context.getSharedPreferences("medpad_taproute", Context.MODE_PRIVATE)
            .edit()
            .putString("route", route.route)
            .putString("customerId", route.customerId ?: "")
            .putString("entityType", route.entityType ?: "")
            .putString("entityId", route.entityId ?: "")
            .apply()
    }

    /** Read AND CLEAR the pending route (single consumption — a stale route
     *  must not hijack a later normal open). */
    fun takePending(context: Context): Route? {
        val sp = context.getSharedPreferences("medpad_taproute", Context.MODE_PRIVATE)
        val route = sp.getString("route", null) ?: return null
        val customerId = sp.getString("customerId", null)?.takeIf { it.isNotEmpty() }
        val entityType = sp.getString("entityType", null)?.takeIf { it.isNotEmpty() }
        val entityId = sp.getString("entityId", null)?.takeIf { it.isNotEmpty() }
        sp.edit().clear().apply()
        if (route != ROUTE_HOME && route != ROUTE_PLANS && route != ROUTE_MAP) return null
        if (entityType != null &&
            entityType != ENTITY_CUSTOMER && entityType != ENTITY_PLAN && entityType != ENTITY_REQUEST
        ) {
            return Route(route, customerId)
        }
        return Route(route, customerId, entityType, entityId)
    }
}
