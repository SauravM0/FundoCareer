package com.fundocareer.app.core.jobalerts

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        JobAlertPreferenceEntity::class,
        JobResultEntity::class,
        JobAlertRunEntity::class,
        JobEmailLedgerEntity::class,
        DeviceSchedulerStateEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class JobAlertDatabase : RoomDatabase() {

    abstract fun jobAlertPreferenceDao(): JobAlertPreferenceDao
    abstract fun jobResultDao(): JobResultDao
    abstract fun jobAlertRunDao(): JobAlertRunDao
    abstract fun jobEmailLedgerDao(): JobEmailLedgerDao
    abstract fun deviceSchedulerStateDao(): DeviceSchedulerStateDao

    companion object {
        @Volatile
        private var INSTANCE: JobAlertDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN scheduledHour INTEGER")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN scheduledMinute INTEGER")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN timeZoneId TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN stoppedByUser INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN scheduledHour INTEGER")
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN scheduledMinute INTEGER")
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN timeZoneId TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN schedulerEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN nextScheduledRunAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN intervalMinutes INTEGER NOT NULL DEFAULT 60")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN setupConfirmationSent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN setupConfirmationSentAt INTEGER")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN setupConfirmationError TEXT")
                db.execSQL("ALTER TABLE job_alert_preferences ADD COLUMN setupConfirmationErrorCode TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN isThisDeviceActive INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN activeDeviceName TEXT")
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN activeDeviceLastSeen TEXT")
                db.execSQL("ALTER TABLE device_scheduler_state ADD COLUMN takeoverRequired INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): JobAlertDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JobAlertDatabase::class.java,
                    "fundocareer_job_alerts.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
