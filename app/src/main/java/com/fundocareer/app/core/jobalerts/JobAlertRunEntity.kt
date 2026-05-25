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
    tableName = "job_alert_runs",
    indices = [
        Index("userEmail"),
        Index("preferenceId")
    ]
)
data class JobAlertRunEntity(
    @PrimaryKey val id: String,
    val userEmail: String,
    val preferenceId: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val jobsFound: Int = 0,
    val jobsNew: Int = 0,
    val jobsSkipped: Int = 0,
    val errorMessage: String? = null,
    val triggeredBy: String
)

@Dao
interface JobAlertRunDao {

    @Query("SELECT * FROM job_alert_runs WHERE userEmail = :userEmail ORDER BY startedAt DESC")
    fun getByUser(userEmail: String): Flow<List<JobAlertRunEntity>>

    @Query("SELECT * FROM job_alert_runs WHERE preferenceId = :preferenceId ORDER BY startedAt DESC LIMIT :limit")
    fun getRecentByPreference(preferenceId: String, limit: Int = 20): Flow<List<JobAlertRunEntity>>

    @Query("SELECT * FROM job_alert_runs WHERE preferenceId = :preferenceId ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLastRun(preferenceId: String): JobAlertRunEntity?

    @Query("SELECT * FROM job_alert_runs WHERE userEmail = :userEmail ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestByUser(userEmail: String): JobAlertRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: JobAlertRunEntity)

    @Update
    suspend fun update(entity: JobAlertRunEntity)

    @Query("DELETE FROM job_alert_runs WHERE userEmail = :userEmail")
    suspend fun deleteAllByUser(userEmail: String)

    @Query("DELETE FROM job_alert_runs WHERE preferenceId = :preferenceId")
    suspend fun deleteByPreference(preferenceId: String)
}
