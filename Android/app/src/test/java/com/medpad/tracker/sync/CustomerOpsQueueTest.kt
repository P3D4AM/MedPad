package com.medpad.tracker.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.CUSTOP_STATUS_FAILED
import com.medpad.tracker.db.CUSTOP_STATUS_PENDING
import com.medpad.tracker.db.PendingCustomerOp
import com.medpad.tracker.ui.CustomerFormDialog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * Customer 360 (phase 1) — the OFFLINE customer queue's local semantics
 * (no network involved): enqueue create → temp row visible + op queued;
 * edit of a row whose create is still pending MERGES into that op (one op,
 * not two); edit of a synced row queues its own op with the LWW base.
 *
 * The network half of the flush is exercised by the panel's audit suite
 * (server contracts) — here the queue machinery itself is what's under
 * test, using the same durability rules as ServerQueueTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomerOpsQueueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { f -> f.set(null, null) }
    }

    private fun freshDb(): AppDatabase {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // point the singleton at our instance so Customers360 sees it
        (AppDatabase::class.java.getDeclaredField("inst").apply { isAccessible = true })
            .let { it.set(null, db) }
        com.medpad.tracker.sync.TrackerContext.init(app)
        return db
    }

    private val form = ApiClient.CustomerForm(
        name = "دکتر سمیری", type = "doctor",
        address = "تهران", phone = "09121112233",
        lat = 35.7, lng = 51.4,
        city = "تهران", province = "تهران",
        specialty = "پوست",
    )

    @Test
    fun `enqueue create inserts temp row and queues one op`() = runBlocking {
        freshDb()
        val temp = Customers360.enqueueCreate(form)
        assertTrue("temp row id is namespaced", temp.id.startsWith("local-"))
        assertNotNull(temp.pendingLocalId)
        assertFalse("offline create is NOT assigned (mission-12/item-1)", temp.assigned)
        assertEquals("دکتر سمیری", temp.name)
        assertEquals("پوست", temp.specialty)

        assertEquals(1, db.pendingCustomerOpDao().pendingCount())
        val op = db.pendingCustomerOpDao().pending().first()
        assertEquals("create", op.op)
        assertEquals(temp.pendingLocalId, op.localId)
        // the payload carries the idempotency key the server replays on
        assertEquals(temp.pendingLocalId, JSONObject(op.payload).optString("localId"))
        // the temp row is retrievable through the pendingLocalId mapping
        assertEquals(temp.id, db.doctorDao().byPendingLocalId(temp.pendingLocalId!!)!!.id)
    }

    @Test
    fun `editing a pending create merges into that single op`() = runBlocking {
        freshDb()
        val temp = Customers360.enqueueCreate(form)
        // offline edit of the STILL-PENDING create
        val edited = form.copy(name = "دکتر سمیری ویرایش", specialty = "قلب")
        assertTrue(Customers360.enqueueEdit(temp.id, edited))

        // still exactly ONE op — the create absorbed the edit
        assertEquals(1, db.pendingCustomerOpDao().pendingCount())
        val payload = JSONObject(db.pendingCustomerOpDao().pending().first().payload)
        assertEquals("دکتر سمیری ویرایش", payload.optString("name"))
        assertEquals("قلب", payload.optString("specialty"))
        // the local row shows the edit optimistically
        assertEquals("دکتر سمیری ویرایش", db.doctorDao().byId(temp.id)!!.name)
    }

    @Test
    fun `editing a synced row queues a separate op with LWW base`() = runBlocking {
        freshDb()
        val base = System.currentTimeMillis()
        db.doctorDao().upsert(
            com.medpad.tracker.db.Doctor(
                id = "srv-1", name = "کلینیک مهر", type = "clinic",
                lat = 35.0, lng = 51.0, assigned = true, updatedAt = base,
            ),
        )
        assertTrue(Customers360.enqueueEdit("srv-1", form.copy(type = "clinic")))
        val op = db.pendingCustomerOpDao().pending().first()
        assertEquals("edit", op.op)
        assertEquals("srv-1", op.customerId)
        // LWW base travels in the payload (the server compares updatedAt)
        assertEquals(
            java.time.Instant.ofEpochMilli(base).toString(),
            JSONObject(op.payload).optString("clientUpdatedAt"),
        )
    }

    @Test
    fun `failed ops stay readable with the server error and can requeue`() = runBlocking {
        freshDb()
        db.pendingCustomerOpDao().insert(
            PendingCustomerOp(
                localId = "op-x", op = "create", payload = "{}",
                status = CUSTOP_STATUS_FAILED, lastError = "مشتری «X» از قبل ثبت شده است",
                attempts = 1,
            ),
        )
        val failed = db.pendingCustomerOpDao().failed()
        assertEquals(1, failed.size)
        assertEquals("مشتری «X» از قبل ثبت شده است", failed[0].lastError)
        assertEquals(0, db.pendingCustomerOpDao().pendingCount())
        db.pendingCustomerOpDao().requeueFailed()
        assertEquals(1, db.pendingCustomerOpDao().pendingCount())
    }

    @Test
    fun `crash recovery returns sending items to pending`() = runBlocking {
        freshDb()
        db.pendingCustomerOpDao().insert(
            PendingCustomerOp(localId = "op-s", op = "link", payload = "{}", status = "sending"),
        )
        assertEquals(1, db.pendingCustomerOpDao().resetSending())
        assertEquals(1, db.pendingCustomerOpDao().pendingCount())
    }

    // ── pure validation (CustomerFormDialog) ────────────────

    @Test
    fun `phone validation accepts persian digits and rejects garbage`() {
        assertTrue(CustomerFormDialog.validPhone(""))
        assertTrue(CustomerFormDialog.validPhone("09121234567"))
        assertTrue(CustomerFormDialog.validPhone("۰۹۱۲۱۲۳۴۵۶۷")) // Persian digits
        assertTrue(CustomerFormDialog.validPhone("0912 123 4567"))
        assertTrue(CustomerFormDialog.validPhone("+989123456789".replace("+", "")))
        assertFalse(CustomerFormDialog.validPhone("12345")) // too short
        assertFalse(CustomerFormDialog.validPhone("abcd"))   // not digits
        assertFalse(CustomerFormDialog.validPhone("09121234567890123")) // too long
        assertEquals("09121234567", CustomerFormDialog.normalizeDigits("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test
    fun `clearAll wipes queue and caches`() = runBlocking {
        freshDb()
        Customers360.enqueueCreate(form)
        db.customerProfileDao().upsert(
            com.medpad.tracker.db.CustomerProfileCache(customerId = "c1", payload = "{}"),
        )
        db.customerLinkDao().upsertAll(
            listOf(
                com.medpad.tracker.db.CustomerLinkEntity(
                    id = "l", clinicId = "a", physicianId = "b",
                ),
            ),
        )
        Customers360.clearAll(app)
        assertEquals(0, db.pendingCustomerOpDao().pendingCount())
        assertNull(db.customerProfileDao().byId("c1"))
        assertEquals(0, db.customerLinkDao().byClinic("a").size)
    }
}
