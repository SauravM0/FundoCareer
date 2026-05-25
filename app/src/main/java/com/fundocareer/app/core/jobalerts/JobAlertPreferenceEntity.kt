package com.fundocareer.app.core.jobalerts

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "job_alert_preferences",
    indices = [Index("userEmail")]
)
data class JobAlertPreferenceEntity(
    @PrimaryKey val id: String,
    val userEmail: String,
    val role: String,
    val location: String,
    val experience: String,
    val remote: Boolean? = null,
    val salaryMin: Long? = null,
    val salaryMax: Long? = null,
    val skills: String? = null,
    val company: String? = null,
    val datePosted: String? = null,
    /** @deprecated Old interval scheduler compatibility. Do not use for new scheduling flow. */
    @Deprecated("Use scheduledHour/scheduledMinute instead")
    val intervalValue: Long = JobAlertDefaults.DEFAULT_INTERVAL_VALUE,
    /** @deprecated Old interval scheduler compatibility. Do not use for new scheduling flow. */
    @Deprecated("Use scheduledHour/scheduledMinute instead")
    val intervalUnit: String = JobAlertDefaults.DEFAULT_INTERVAL_UNIT,
    val reportFormat: String = JobAlertDefaults.DEFAULT_REPORT_FORMAT,
    val intervalMinutes: Long = JobAlertDefaults.DEFAULT_INTERVAL_MINUTES,
    val scheduledHour: Int? = 9,
    val scheduledMinute: Int? = 0,
    val timeZoneId: String? = null,
    val stoppedByUser: Boolean = false,
    val active: Boolean = true,
    val pausedDueToLogout: Boolean = false,
    val autoResumeEnabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastEmailSentAt: Long? = null,
    val lastError: String? = null,
    val lastAttemptRunAt: Long? = null,
    val lastSuccessfulRunAt: Long? = null,
    val schedulerEnabled: Boolean = false,
    val nextScheduledRunAt: Long? = null,
    val pendingDueRunAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface JobAlertPreferenceDao {

    @Query("SELECT * FROM job_alert_preferences WHERE userEmail = :userEmail ORDER BY updatedAt DESC")
    fun getAllByUser(userEmail: String): Flow<List<JobAlertPreferenceEntity>>

    @Query("SELECT * FROM job_alert_preferences WHERE userEmail = :userEmail AND active = 1 ORDER BY updatedAt DESC")
    fun getActiveByUser(userEmail: String): Flow<List<JobAlertPreferenceEntity>>

    @Query("SELECT * FROM job_alert_preferences WHERE id = :id")
    suspend fun getById(id: String): JobAlertPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: JobAlertPreferenceEntity)

    @Update
    suspend fun update(entity: JobAlertPreferenceEntity)

    @Delete
    suspend fun delete(entity: JobAlertPreferenceEntity)

    @Query("DELETE FROM job_alert_preferences WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM job_alert_preferences WHERE userEmail = :userEmail")
    suspend fun deleteAllByUser(userEmail: String)

    @Query("SELECT COUNT(*) FROM job_alert_preferences WHERE userEmail = :userEmail")
    fun getCountByUser(userEmail: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM job_alert_preferences WHERE userEmail = :userEmail AND active = 1")
    suspend fun getActiveCountByUserNow(userEmail: String): Int

    @Query("SELECT * FROM job_alert_preferences WHERE userEmail = :userEmail AND pausedDueToLogout = 1")
    suspend fun getPausedDueToLogout(userEmail: String): List<JobAlertPreferenceEntity>

    @Query("UPDATE job_alert_preferences SET active = :active, pausedDueToLogout = 0, updatedAt = :now WHERE userEmail = :userEmail AND pausedDueToLogout = 1")
    suspend fun resumePausedDueToLogout(userEmail: String, active: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET pausedDueToLogout = 1, active = 0, updatedAt = :now WHERE userEmail = :userEmail AND active = 1")
    suspend fun pauseAllDueToLogout(userEmail: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM job_alert_preferences WHERE userEmail = :userEmail AND active = 1 AND stoppedByUser = 0 AND schedulerEnabled = 1 LIMIT 1")
    suspend fun getActiveByUserNow(userEmail: String): JobAlertPreferenceEntity?

    @Query("SELECT * FROM job_alert_preferences WHERE active = 1 AND stoppedByUser = 0 AND schedulerEnabled = 1 LIMIT 1")
    suspend fun getAnyActiveNow(): JobAlertPreferenceEntity?

    @Query("SELECT * FROM job_alert_preferences WHERE active = 1 AND schedulerEnabled = 1 AND stoppedByUser = 0")
    suspend fun getAllActiveEnabledNow(): List<JobAlertPreferenceEntity>

    @Query("UPDATE job_alert_preferences SET schedulerEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun markSchedulerEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET stoppedByUser = :stopped, updatedAt = :now WHERE id = :id")
    suspend fun markStoppedByUser(id: String, stopped: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET nextScheduledRunAt = :nextRunAt, updatedAt = :now WHERE id = :id")
    suspend fun updateNextScheduledRunAt(id: String, nextRunAt: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET lastAttemptRunAt = :timestamp, updatedAt = :now WHERE id = :id")
    suspend fun updateLastAttemptRunAt(id: String, timestamp: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET lastSuccessfulRunAt = :timestamp, updatedAt = :now WHERE id = :id")
    suspend fun updateLastSuccessfulRunAt(id: String, timestamp: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET lastEmailSentAt = :timestamp, updatedAt = :now WHERE id = :id")
    suspend fun updateLastSuccessfulEmailAt(id: String, timestamp: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET pendingDueRunAt = :dueAt, updatedAt = :now WHERE id = :id")
    suspend fun markPendingDueRun(id: String, dueAt: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE job_alert_preferences SET pendingDueRunAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun clearPendingDueRun(id: String, now: Long = System.currentTimeMillis())
}
