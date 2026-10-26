package com.medpad.tracker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Doctor::class, PendingCheckin::class, PendingLocation::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun doctorDao(): DoctorDao
    abstract fun pendingCheckinDao(): PendingCheckinDao
    abstract fun pendingLocationDao(): PendingLocationDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medpad.db",
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
    }
}
