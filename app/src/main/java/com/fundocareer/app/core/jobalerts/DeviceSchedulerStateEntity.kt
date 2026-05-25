package com.fundocareer.app.core.jobalerts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "device_scheduler_state",
    indices = [Index(value = ["userEmail"], unique = true)]
)
data class DeviceSchedulerStateEntity(
    @PrimaryKey val id: String,
    val userEmail: String,
    val schedulerEnabled: Boolean = false,
    val lastRunAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val nextScheduledRunAt: Long? = null,
    val isRunning: Boolean = false,
    val pausedDueToLogout: Boolean = false,
    val workName: String = JobAlertDefaults.DEFAULT_WORK_NAME,
    val scheduledHour: Int? = null,
    val scheduledMinute: Int? = null,
    val timeZoneId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DeviceSchedulerStateDao {

    @Query("SELECT * FROM device_scheduler_state WHERE userEmail = :userEmail LIMIT 1")
    suspend fun getByUser(userEmail: String): DeviceSchedulerStateEntity?

    @Query("SELECT * FROM device_scheduler_state WHERE userEmail = :userEmail LIMIT 1")
    fun observeByUser(userEmail: String): Flow<DeviceSchedulerStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeviceSchedulerStateEntity)

    @Update
    suspend fun update(entity: DeviceSchedulerStateEntity)

    @Query("SELECT * FROM device_scheduler_state WHERE schedulerEnabled = 1")
    fun getAllEnabled(): Flow<List<DeviceSchedulerStateEntity>>

    @Query("SELECT * FROM device_scheduler_state WHERE schedulerEnabled = 1")
    suspend fun getAllEnabledNow(): List<DeviceSchedulerStateEntity>

    @Query("SELECT * FROM device_scheduler_state WHERE userEmail = :userEmail AND pausedDueToLogout = 1 LIMIT 1")
    suspend fun getPausedByUser(userEmail: String): DeviceSchedulerStateEntity?

    @Query("UPDATE device_scheduler_state SET pausedDueToLogout = 1, schedulerEnabled = 0, updatedAt = :now WHERE userEmail = :userEmail")
    suspend fun markPausedDueToLogout(userEmail: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE device_scheduler_state SET pausedDueToLogout = 0, schedulerEnabled = 1, updatedAt = :now WHERE userEmail = :userEmail")
    suspend fun markResumed(userEmail: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM device_scheduler_state WHERE userEmail = :userEmail")
    suspend fun deleteByUser(userEmail: String)
}
