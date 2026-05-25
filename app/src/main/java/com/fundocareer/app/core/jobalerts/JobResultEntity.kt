package com.fundocareer.app.core.jobalerts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "job_results",
    indices = [
        Index("userEmail"),
        Index("preferenceId"),
        Index(value = ["fingerprint"], unique = true)
    ]
)
data class JobResultEntity(
    @PrimaryKey val id: String,
    val userEmail: String,
    val preferenceId: String,
    val jobTitle: String,
    val company: String,
    val location: String,
    val description: String,
    val url: String? = null,
    val salary: String? = null,
    val postedDate: String? = null,
    val source: String,
    val fingerprint: String? = null,
    val runId: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val applied: Boolean = false,
    val appliedAt: Long? = null
)

@Dao
interface JobResultDao {

    @Query("SELECT * FROM job_results WHERE userEmail = :userEmail ORDER BY fetchedAt DESC")
    fun getByUser(userEmail: String): Flow<List<JobResultEntity>>

    @Query("SELECT * FROM job_results WHERE preferenceId = :preferenceId ORDER BY fetchedAt DESC")
    fun getByPreference(preferenceId: String): Flow<List<JobResultEntity>>

    @Query("SELECT * FROM job_results WHERE runId = :runId")
    suspend fun getByRun(runId: String): List<JobResultEntity>

    @Query("SELECT * FROM job_results WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): JobResultEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: JobResultEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<JobResultEntity>): List<Long>

    @Query("DELETE FROM job_results WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM job_results WHERE userEmail = :userEmail")
    suspend fun deleteAllByUser(userEmail: String)

    @Query("SELECT COUNT(*) FROM job_results WHERE userEmail = :userEmail")
    fun getCountByUser(userEmail: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM job_results WHERE preferenceId = :preferenceId AND fetchedAt >= :since")
    suspend fun getNewCountSince(preferenceId: String, since: Long): Int

    @Query("DELETE FROM job_results WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
