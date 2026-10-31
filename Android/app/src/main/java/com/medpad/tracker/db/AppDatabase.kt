package com.medpad.tracker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Doctor::class, PendingCheckin::class, PendingLocation::class, PlannedVisitEntity::class, OpEventEntity::class],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun doctorDao(): DoctorDao
    abstract fun pendingCheckinDao(): PendingCheckinDao
    abstract fun pendingLocationDao(): PendingLocationDao
    abstract fun plannedVisitDao(): PlannedVisitDao
    abstract fun opEventDao(): OpEventDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medpad.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                    .also { inst = it }
            }

        // v1.9.1: localId column added to pending_checkins
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_checkins ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_checkins_localId ON pending_checkins(localId)")
            }
        }

        // v1.9.1: attempts / lastError columns for queue diagnostics
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_checkins ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_checkins ADD COLUMN lastError TEXT")
            }
        }

        // G-6 (phase 1 / task 2): GPS accuracy for queued check-in fixes
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_checkins ADD COLUMN accuracy REAL")
            }
        }

        // phase 2 / task 2: server-planned visits cache. Purely ADDITIVE — a
        // new table, nothing existing is touched: the check-in offline queue
        // (pending_checkins), the doctors cache and the location points all
        // survive byte-for-byte. Plans are READ-ONLY for the app, so a failed
        // or interrupted sync can never lose check-in data.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `planned_visits` (" +
                        "`id` TEXT NOT NULL, " +
                        "`customerId` TEXT NOT NULL, " +
                        "`dayKey` TEXT NOT NULL, " +
                        "`timeLocal` TEXT NOT NULL, " +
                        "`scheduledAtMs` INTEGER NOT NULL, " +
                        "`durationMin` INTEGER NOT NULL, " +
                        "`priority` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`note` TEXT, " +
                        "`visitId` TEXT, " +
                        "`visitStartLocal` TEXT, " +
                        "`visitOpen` INTEGER NOT NULL, " +
                        "`overdue` INTEGER NOT NULL, " +
                        "`customerName` TEXT, " +
                        "`customerType` TEXT, " +
                        "`customerAddress` TEXT, " +
                        "`customerPhone` TEXT, " +
                        "`customerAssigned` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_visits_dayKey` ON `planned_visits` (`dayKey`)")
            }
        }

        // Package 1 (phase 2 / task 3): queue lifecycle status so the app can
        // tell LOCAL (pending) / IN-FLIGHT (sending) / FAILED apart. Purely
        // ADDITIVE column with a safe default — every existing queued item
        // (including ones currently stuck under the old retry-forever logic)
        // becomes 'pending' and gets one fresh, correctly-categorized attempt.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_checkins ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'")
            }
        }

        // Package 8 (phase 2 / task 5): customer city/province from the
        // offline Iran-cities dataset. Purely ADDITIVE nullable columns —
        // existing rows (and rows synced from an OLD server without the
        // fields) simply keep NULL; no data is touched.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE doctors ADD COLUMN province TEXT")
                db.execSQL("ALTER TABLE doctors ADD COLUMN city TEXT")
            }
        }

        // phase 3 (dev.5): pending_locations becomes an UPLOAD queue —
        // localId is the idempotency key (UUID; legacy rows get one at
        // upload time), accuracy the reported GPS accuracy. Additive and
        // nullable/defaulted: old rows keep their coordinates, get ""
        // localId and NULL accuracy — exactly what the entity expects
        // (no @ColumnInfo defaultValue → Room accepts the ALTER default).
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN accuracy REAL")
            }
        }

        // mission item 10 — the operational event upload queue. A brand-new
        // table; purely ADDITIVE (nothing existing is touched).
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `op_events` (" +
                        "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`localId` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`errorCode` TEXT, " +
                        "`message` TEXT, " +
                        "`relatedType` TEXT, " +
                        "`relatedId` TEXT, " +
                        "`attempt` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_op_events_createdAt` ON `op_events` (`createdAt`)")
            }
        }

        /** Test-only accessor (unit tests build a throwaway instance on a
         *  version-9 file and must install the SAME real migrations). */
        fun migrationsForTest(): Array<Migration> =
            arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
    }
}
