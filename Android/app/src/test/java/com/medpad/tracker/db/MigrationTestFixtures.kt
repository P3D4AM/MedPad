package com.medpad.tracker.db

import android.database.sqlite.SQLiteDatabase

/**
 * Customer 360 (phase 1) — shared fixture helper for the migration tests.
 *
 * Every test that rolls a built-current database back to a version BELOW 14
 * must also remove the physical v14 additions from `doctors` (the ten
 * Customer-360 columns) and drop the three new tables — exactly the shape a
 * pre-Customer-360 install has. Without this, the migration chain's final
 * MIGRATION_13_14 would hit "duplicate column name" on the columns the
 * fixture never removed.
 *
 * (Same pattern the earlier phases applied when they added columns: each
 * new migration updates the OLDER tests' fixtures to the historical truth.)
 */
object MigrationTestFixtures {

    /** the doctors DDL fragments Room generates for the post-v13 columns
     *  (Customer-360 v14 additions + the Holoo-integration v15 additions;
     *  NOTE: `active` carries NO DEFAULT clause in Room's output — the
     *  Kotlin default lives in the entity, not the DDL) */
    private val CUSTOMER_360_DOCTOR_COLUMNS = listOf(
        ", `lastName` TEXT",
        ", `title` TEXT",
        ", `specialty` TEXT",
        ", `managerName` TEXT",
        ", `phone2` TEXT",
        ", `district` TEXT",
        ", `postalCode` TEXT",
        ", `notes` TEXT",
        ", `active` INTEGER NOT NULL",
        ", `pendingLocalId` TEXT",
        ", `erpCode` TEXT",
        ", `erpMandeh` REAL",
        ", `erpCredit` REAL",
    )

    /** rebuild `doctors` without the Customer-360 columns; keeps data. */
    fun stripCustomer360Doctors(raw: SQLiteDatabase) {
        val sql = raw.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='doctors'", null,
        ).use { c -> c.moveToFirst(); c.getString(0) }
        var stripped = sql
        CUSTOMER_360_DOCTOR_COLUMNS.forEach { col -> stripped = stripped.replace(col, "") }
        check(stripped != sql) { "fixture bug: no Customer-360 column found to strip" }
        raw.execSQL("DROP TABLE IF EXISTS doctors_pre360")
        raw.execSQL(stripped.replace("CREATE TABLE `doctors`", "CREATE TABLE `doctors_pre360`"))
        raw.execSQL(
            "INSERT INTO doctors_pre360 (id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned) " +
                "SELECT id,name,type,address,phone,province,city,lat,lng,radius,updatedAt,assigned FROM doctors",
        )
        raw.execSQL("DROP TABLE doctors")
        raw.execSQL("ALTER TABLE doctors_pre360 RENAME TO doctors")
    }

    /** drop the three v14-only tables (they must not exist below v14). */
    fun dropCustomer360Tables(raw: SQLiteDatabase) {
        raw.execSQL("DROP TABLE IF EXISTS customer_links")
        raw.execSQL("DROP TABLE IF EXISTS customer_profiles")
        raw.execSQL("DROP TABLE IF EXISTS pending_customer_ops")
    }
}
