package com.medpad.tracker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Doctor::class, PendingCheckin::class, PendingLocation::class, PlannedVisitEntity::class],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun doctorDao(): DoctorDao
    abstract fun pendingCheckinDao(): PendingCheckinDao
    abstract fun pendingLocationDao(): PendingLocationDao
    abstract fun plannedVisitDao(): PlannedVisitDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medpad.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
    }
}
