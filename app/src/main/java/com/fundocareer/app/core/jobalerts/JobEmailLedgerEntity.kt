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
    tableName = "job_email_ledger",
    indices = [
        Index("userEmail"),
        Index("preferenceId"),
        Index("runId")
    ]
)
data class JobEmailLedgerEntity(
    @PrimaryKey val id: String,
    val userEmail: String,
    val preferenceId: String,
    val runId: String? = null,
    val sentAt: Long,
    val recipientEmail: String,
    val subject: String,
    val jobCount: Int = 0,
    val status: String,
    val errorMessage: String? = null,
    val messageId: String? = null,
    val reportFormat: String
)

@Dao
interface JobEmailLedgerDao {

    @Query("SELECT * FROM job_email_ledger WHERE userEmail = :userEmail ORDER BY sentAt DESC")
    fun getByUser(userEmail: String): Flow<List<JobEmailLedgerEntity>>

    @Query("SELECT * FROM job_email_ledger WHERE preferenceId = :preferenceId ORDER BY sentAt DESC")
    fun getByPreference(preferenceId: String): Flow<List<JobEmailLedgerEntity>>

    @Query("SELECT * FROM job_email_ledger WHERE preferenceId = :preferenceId ORDER BY sentAt DESC LIMIT 1")
    suspend fun getLastSentByPreference(preferenceId: String): JobEmailLedgerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: JobEmailLedgerEntity)

    @Query("UPDATE job_email_ledger SET status = :status, errorMessage = :errorMessage, messageId = :messageId WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String? = null, messageId: String? = null)

    @Query("SELECT COUNT(*) FROM job_email_ledger WHERE userEmail = :userEmail")
    fun getCountByUser(userEmail: String): Flow<Int>

    @Query("DELETE FROM job_email_ledger WHERE userEmail = :userEmail")
    suspend fun deleteAllByUser(userEmail: String)
}
