package com.medpad.tracker.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * phase 2 / §1 (daily app capabilities) — notification TAP-THROUGH router.
 *
 * The phase-zero audit found the gap: AppNotifier built notifications with
 * setAutoCancel(true) but NO contentIntent — tapping a notification opened
 * NOTHING. The router under test fixes the destination side:
 *
 *  - kind → route mapping (plan → PLANS; assignment/proximity → MAP;
 *    info/unknown/null → HOME — the safe default; an unknown kind must
 *    never crash or dead-end);
 *  - the PendingIntent targets the guarded hub (MainActivity), never a
 *    leaf screen, and carries route + customerId extras that survive the
 *    intent roundtrip (routeFrom);
 *  - the pending-route persistence: a tap that arrives without a session
 *    (cold start / expired) is SAVED, consumed exactly ONCE after the
 *    login detour, and a stale pending never hijacks a later normal open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTapRouterTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // clear any pending route left by an earlier test
        NotificationTapRouter.takePending(app)
    }

    // ── kind → route mapping ──────────────────────────────────────

    @Test
    fun `kind mapping - plan goes to the plans screen`() {
        assertEquals(NotificationTapRouter.ROUTE_PLANS, NotificationTapRouter.routeForKind("plan"))
    }

    @Test
    fun `kind mapping - customer-centric kinds go to the map`() {
        assertEquals(NotificationTapRouter.ROUTE_MAP, NotificationTapRouter.routeForKind("assignment"))
        assertEquals(NotificationTapRouter.ROUTE_MAP, NotificationTapRouter.routeForKind("proximity"))
        assertEquals(NotificationTapRouter.ROUTE_MAP, NotificationTapRouter.routeForKind("request"))
    }

    @Test
    fun `kind mapping - info null and unknown kinds default to the safe home route`() {
        assertEquals(NotificationTapRouter.ROUTE_HOME, NotificationTapRouter.routeForKind("info"))
        assertEquals(NotificationTapRouter.ROUTE_HOME, NotificationTapRouter.routeForKind(null))
        assertEquals(NotificationTapRouter.ROUTE_HOME, NotificationTapRouter.routeForKind("who-knows"))
        assertEquals(NotificationTapRouter.ROUTE_HOME, NotificationTapRouter.routeForKind(""))
    }

    // ── PendingIntent build → parse roundtrip ─────────────────────

    @Test
    fun `pending intent targets MainActivity and the extras survive the roundtrip`() {
        val pi = NotificationTapRouter.pendingIntent(app, "assignment", "cus-42")
        assertNotNull(pi)

        // Robolectric: recover the wrapped intent from the PendingIntent
        val wrapped: Intent = Shadows.shadowOf(pi).savedIntent
        assertEquals(MainActivity::class.java.name, wrapped.component?.className)

        val route = NotificationTapRouter.routeFrom(wrapped)
        assertNotNull(route)
        assertEquals(NotificationTapRouter.ROUTE_MAP, route!!.route)
        assertEquals("cus-42", route.customerId)
    }

    @Test
    fun `pending intent without a customer carries no customer extra`() {
        val pi = NotificationTapRouter.pendingIntent(app, "plan", null)
        val wrapped = Shadows.shadowOf(pi).savedIntent
        val route = NotificationTapRouter.routeFrom(wrapped)
        assertNotNull(route)
        assertEquals(NotificationTapRouter.ROUTE_PLANS, route!!.route)
        assertNull(route.customerId)
    }

    @Test
    fun `empty customer id is treated as absent`() {
        val pi = NotificationTapRouter.pendingIntent(app, "proximity", "")
        val wrapped = Shadows.shadowOf(pi).savedIntent
        val route = NotificationTapRouter.routeFrom(wrapped)
        assertNotNull(route)
        assertEquals(NotificationTapRouter.ROUTE_MAP, route!!.route)
        assertNull(route.customerId)
    }

    // ── routeFrom: what is NOT a tap route ────────────────────────

    @Test
    fun `a plain launcher intent is not a tap route`() {
        assertNull(NotificationTapRouter.routeFrom(Intent("android.intent.action.MAIN")))
    }

    @Test
    fun `a forged unknown route value is rejected not forwarded`() {
        val i = Intent().putExtra(NotificationTapRouter.EXTRA_ROUTE, "evil-screen")
        assertNull(NotificationTapRouter.routeFrom(i))
    }

    // ── pending-route persistence (the login detour) ──────────────

    @Test
    fun `pending route survives a simulated restart and is consumed exactly once`() {
        // cold start / expired session: the tap route is persisted…
        NotificationTapRouter.savePending(app, NotificationTapRouter.Route(NotificationTapRouter.ROUTE_PLANS))

        // …and read back ONCE after the re-login (new process → same prefs)
        val taken = NotificationTapRouter.takePending(app)
        assertNotNull(taken)
        assertEquals(NotificationTapRouter.ROUTE_PLANS, taken!!.route)

        // single consumption: a later normal open must NOT be hijacked
        assertNull(NotificationTapRouter.takePending(app))
    }

    @Test
    fun `pending route with customer id roundtrips and clears in one take`() {
        NotificationTapRouter.savePending(
            app, NotificationTapRouter.Route(NotificationTapRouter.ROUTE_MAP, "cus-7"),
        )
        val taken = NotificationTapRouter.takePending(app)
        assertNotNull(taken)
        assertEquals(NotificationTapRouter.ROUTE_MAP, taken!!.route)
        assertEquals("cus-7", taken.customerId)
        assertNull(NotificationTapRouter.takePending(app))
    }

    @Test
    fun `the most recent tap wins - a second save overwrites the first`() {
        NotificationTapRouter.savePending(app, NotificationTapRouter.Route(NotificationTapRouter.ROUTE_PLANS))
        NotificationTapRouter.savePending(
            app, NotificationTapRouter.Route(NotificationTapRouter.ROUTE_MAP, "cus-9"),
        )
        val taken = NotificationTapRouter.takePending(app)
        assertEquals(NotificationTapRouter.ROUTE_MAP, taken?.route)
        assertEquals("cus-9", taken?.customerId)
    }

    @Test
    fun `a corrupted pending value is dropped safely`() {
        app.getSharedPreferences("medpad_taproute", android.content.Context.MODE_PRIVATE)
            .edit().putString("route", "not-a-real-route").apply()
        assertNull(NotificationTapRouter.takePending(app))
    }

    // ── mission item 3: ENTITY-level destinations ────────────────

    @Test
    fun `entity plan routes to the plans screen carrying the plan id`() {
        val r = NotificationTapRouter.routeForEntity("plan", null, "plan", "p-77")
        assertEquals(NotificationTapRouter.ROUTE_PLANS, r.route)
        assertEquals("plan", r.entityType)
        assertEquals("p-77", r.entityId)
    }

    @Test
    fun `entity request routes to the plans screen carrying the request id`() {
        val r = NotificationTapRouter.routeForEntity("assignment", null, "request", "ar-9")
        assertEquals(NotificationTapRouter.ROUTE_PLANS, r.route)
        assertEquals("request", r.entityType)
        assertEquals("ar-9", r.entityId)
    }

    @Test
    fun `entity customer routes to the map focused on that customer`() {
        val r = NotificationTapRouter.routeForEntity("assignment", null, "customer", "cus-4")
        assertEquals(NotificationTapRouter.ROUTE_MAP, r.route)
        assertEquals("cus-4", r.customerId)
        assertEquals("cus-4", r.entityId)
    }

    @Test
    fun `no entity degrades to the kind mapping with legacy customer id`() {
        val r = NotificationTapRouter.routeForEntity("assignment", "cus-4", null, null)
        assertEquals(NotificationTapRouter.ROUTE_MAP, r.route)
        assertEquals("cus-4", r.customerId)
        assertNull(r.entityType)
        // kind-only plan (historical row) → plans screen without a record id
        val p = NotificationTapRouter.routeForEntity("plan", null, null, null)
        assertEquals(NotificationTapRouter.ROUTE_PLANS, p.route)
        assertNull(p.entityId)
    }

    @Test
    fun `unknown entity type degrades to the kind mapping - never forwarded`() {
        val r = NotificationTapRouter.routeForEntity("info", null, "brand-new-type", "x")
        assertEquals(NotificationTapRouter.ROUTE_HOME, r.route)
        assertNull(r.entityType)
    }

    @Test
    fun `entity payload survives the pendingIntent roundtrip`() {
        val pi = NotificationTapRouter.pendingIntent(app, "plan", null, "plan", "p-77")
        val wrapped: Intent = Shadows.shadowOf(pi).savedIntent
        val route = NotificationTapRouter.routeFrom(wrapped)!!
        assertEquals(NotificationTapRouter.ROUTE_PLANS, route.route)
        assertEquals("plan", route.entityType)
        assertEquals("p-77", route.entityId)
    }

    @Test
    fun `entity payload survives the pending persistence (login detour)`() {
        NotificationTapRouter.savePending(
            app,
            NotificationTapRouter.routeForEntity("plan", null, "plan", "p-5"),
        )
        val taken = NotificationTapRouter.takePending(app)!!
        assertEquals(NotificationTapRouter.ROUTE_PLANS, taken.route)
        assertEquals("plan", taken.entityType)
        assertEquals("p-5", taken.entityId)
        assertNull(NotificationTapRouter.takePending(app))
    }

    @Test
    fun `a forged entity type in an intent is stripped not forwarded`() {
        val i = Intent()
            .putExtra(NotificationTapRouter.EXTRA_ROUTE, NotificationTapRouter.ROUTE_PLANS)
            .putExtra(NotificationTapRouter.EXTRA_ENTITY_TYPE, "evil")
            .putExtra(NotificationTapRouter.EXTRA_ENTITY_ID, "x")
        val route = NotificationTapRouter.routeFrom(i)!!
        assertEquals(NotificationTapRouter.ROUTE_PLANS, route.route)
        assertNull(route.entityType)
        assertNull(route.entityId)
    }

    @Test
    fun `route signature distinguishes records on the same screen`() {
        val a = NotificationTapRouter.routeForEntity("plan", null, "plan", "p-1")
        val b = NotificationTapRouter.routeForEntity("plan", null, "plan", "p-2")
        org.junit.Assert.assertNotEquals(a.signature, b.signature)
        assertEquals(a.signature, a.copy().signature)
    }
}
