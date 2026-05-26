package com.fundocareer.app.core.jobalerts

import android.content.Context
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.flow.Flow

class JobAlertRepository private constructor(
    private val preferenceDao: JobAlertPreferenceDao,
    private val jobResultDao: JobResultDao,
    private val alertRunDao: JobAlertRunDao,
    private val emailLedgerDao: JobEmailLedgerDao,
    private val schedulerStateDao: DeviceSchedulerStateDao
) {

    companion object {
        @Volatile
        private var INSTANCE: JobAlertRepository? = null

        fun getInstance(context: Context): JobAlertRepository {
            return INSTANCE ?: synchronized(this) {
                val db = JobAlertDatabase.getDatabase(context)
                val instance = JobAlertRepository(
                    preferenceDao = db.jobAlertPreferenceDao(),
                    jobResultDao = db.jobResultDao(),
                    alertRunDao = db.jobAlertRunDao(),
                    emailLedgerDao = db.jobEmailLedgerDao(),
                    schedulerStateDao = db.deviceSchedulerStateDao()
                )
                INSTANCE = instance
                instance
            }
        }
    }

    // ================================================================
    // JobAlertPreference operations (per user isolation via userEmail)
    // ================================================================

    fun getPreferences(userEmail: String): Flow<List<JobAlertPreferenceEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getPreferences", mapOf("userEmail" to userEmail))
        return preferenceDao.getAllByUser(userEmail)
    }

    fun getActivePreferences(userEmail: String): Flow<List<JobAlertPreferenceEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getActivePreferences", mapOf("userEmail" to userEmail))
        return preferenceDao.getActiveByUser(userEmail)
    }

    suspend fun getPreferenceById(id: String): JobAlertPreferenceEntity? {
        FcLog.d(FcLog.TAG_SCHEDULER, "getPreferenceById", mapOf("id" to id))
        return preferenceDao.getById(id)
    }

    suspend fun savePreference(entity: JobAlertPreferenceEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "savePreference", mapOf("id" to entity.id, "userEmail" to entity.userEmail, "role" to entity.role))
        preferenceDao.insert(entity)
    }

    suspend fun updatePreference(entity: JobAlertPreferenceEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updatePreference", mapOf("id" to entity.id, "userEmail" to entity.userEmail))
        preferenceDao.update(entity)
    }

    suspend fun deletePreference(id: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deletePreference", mapOf("id" to id))
        preferenceDao.deleteById(id)
    }

    suspend fun deleteAllPreferencesForUser(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteAllPreferencesForUser", mapOf("userEmail" to userEmail))
        preferenceDao.deleteAllByUser(userEmail)
    }

    fun getPreferenceCount(userEmail: String): Flow<Int> {
        return preferenceDao.getCountByUser(userEmail)
    }

    suspend fun getActivePreferenceCount(userEmail: String): Int {
        FcLog.d(FcLog.TAG_SCHEDULER, "getActivePreferenceCount", mapOf("userEmail" to userEmail))
        return preferenceDao.getActiveCountByUserNow(userEmail)
    }

    suspend fun getActivePreferenceNow(userEmail: String): JobAlertPreferenceEntity? {
        FcLog.d(FcLog.TAG_SCHEDULER, "getActivePreferenceNow", mapOf("userEmail" to userEmail))
        return preferenceDao.getActiveByUserNow(userEmail)
    }

    suspend fun getAnyActivePreferenceNow(): JobAlertPreferenceEntity? {
        FcLog.d(FcLog.TAG_SCHEDULER, "getAnyActivePreferenceNow")
        return preferenceDao.getAnyActiveNow()
    }

    suspend fun getAllActiveEnabledPreferences(): List<JobAlertPreferenceEntity> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getAllActiveEnabledPreferences")
        return preferenceDao.getAllActiveEnabledNow()
    }

    suspend fun markSchedulerEnabled(preferenceId: String, enabled: Boolean) {
        FcLog.d(FcLog.TAG_SCHEDULER, "markSchedulerEnabled", mapOf("preferenceId" to preferenceId, "enabled" to enabled))
        preferenceDao.markSchedulerEnabled(preferenceId, enabled)
    }

    suspend fun markStoppedByUser(preferenceId: String, stopped: Boolean) {
        FcLog.d(FcLog.TAG_SCHEDULER, "markStoppedByUser", mapOf("preferenceId" to preferenceId, "stopped" to stopped))
        preferenceDao.markStoppedByUser(preferenceId, stopped)
    }

    suspend fun updateNextScheduledRunAt(preferenceId: String, nextRunAt: Long?) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateNextScheduledRunAt", mapOf("preferenceId" to preferenceId, "nextRunAt" to nextRunAt))
        preferenceDao.updateNextScheduledRunAt(preferenceId, nextRunAt)
    }

    suspend fun updateLastAttemptRunAt(preferenceId: String, timestamp: Long) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateLastAttemptRunAt", mapOf("preferenceId" to preferenceId, "timestamp" to timestamp))
        preferenceDao.updateLastAttemptRunAt(preferenceId, timestamp)
    }

    suspend fun updateLastSuccessfulRunAt(preferenceId: String, timestamp: Long) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateLastSuccessfulRunAt", mapOf("preferenceId" to preferenceId, "timestamp" to timestamp))
        preferenceDao.updateLastSuccessfulRunAt(preferenceId, timestamp)
    }

    suspend fun updateLastSuccessfulEmailAt(preferenceId: String, timestamp: Long) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateLastSuccessfulEmailAt", mapOf("preferenceId" to preferenceId, "timestamp" to timestamp))
        preferenceDao.updateLastSuccessfulEmailAt(preferenceId, timestamp)
    }

    suspend fun markPendingDueRun(preferenceId: String, dueAt: Long?) {
        FcLog.d(FcLog.TAG_SCHEDULER, "markPendingDueRun", mapOf("preferenceId" to preferenceId, "dueAt" to dueAt))
        preferenceDao.markPendingDueRun(preferenceId, dueAt)
    }

    suspend fun clearPendingDueRun(preferenceId: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "clearPendingDueRun", mapOf("preferenceId" to preferenceId))
        preferenceDao.clearPendingDueRun(preferenceId)
    }

    suspend fun markSetupConfirmationSent(preferenceId: String, sentAt: Long = System.currentTimeMillis()) {
        FcLog.i(FcLog.TAG_SCHEDULER, "markSetupConfirmationSent", mapOf("preferenceId" to preferenceId))
        preferenceDao.markSetupConfirmationSent(preferenceId, sentAt)
    }

    suspend fun markSetupConfirmationFailed(preferenceId: String, error: String?, errorCode: String?) {
        FcLog.w(FcLog.TAG_SCHEDULER, "markSetupConfirmationFailed", mapOf("preferenceId" to preferenceId, "errorCode" to errorCode, "error" to error))
        preferenceDao.markSetupConfirmationFailed(preferenceId, error, errorCode)
    }

    suspend fun pauseAllDueToLogout(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "pauseAllDueToLogout", mapOf("userEmail" to userEmail))
        preferenceDao.pauseAllDueToLogout(userEmail)
    }

    suspend fun resumePausedDueToLogout(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "resumePausedDueToLogout", mapOf("userEmail" to userEmail))
        preferenceDao.resumePausedDueToLogout(userEmail, active = true)
    }

    // ================================================================
    // JobResult operations (per user isolation via userEmail)
    // ================================================================

    fun getJobResults(userEmail: String): Flow<List<JobResultEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getJobResults", mapOf("userEmail" to userEmail))
        return jobResultDao.getByUser(userEmail)
    }

    fun getJobResultsByPreference(preferenceId: String): Flow<List<JobResultEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getJobResultsByPreference", mapOf("preferenceId" to preferenceId))
        return jobResultDao.getByPreference(preferenceId)
    }

    suspend fun getJobResultsByRun(runId: String): List<JobResultEntity> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getJobResultsByRun", mapOf("runId" to runId))
        return jobResultDao.getByRun(runId)
    }

    suspend fun getJobResultByFingerprint(fingerprint: String): JobResultEntity? {
        FcLog.d(FcLog.TAG_SCHEDULER, "getJobResultByFingerprint", mapOf("fingerprint" to fingerprint))
        return jobResultDao.getByFingerprint(fingerprint)
    }

    suspend fun insertJobResult(entity: JobResultEntity): Long {
        FcLog.d(FcLog.TAG_SCHEDULER, "insertJobResult", mapOf("id" to entity.id, "title" to entity.jobTitle))
        return jobResultDao.insert(entity)
    }

    suspend fun insertJobResults(entities: List<JobResultEntity>): List<Long> {
        FcLog.d(FcLog.TAG_SCHEDULER, "insertJobResults", mapOf("count" to entities.size))
        return jobResultDao.insertAll(entities)
    }

    suspend fun deleteJobResult(id: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteJobResult", mapOf("id" to id))
        jobResultDao.deleteById(id)
    }

    suspend fun deleteAllJobResultsForUser(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteAllJobResultsForUser", mapOf("userEmail" to userEmail))
        jobResultDao.deleteAllByUser(userEmail)
    }

    fun getJobResultCount(userEmail: String): Flow<Int> {
        return jobResultDao.getCountByUser(userEmail)
    }

    suspend fun getNewJobCountSince(preferenceId: String, since: Long): Int {
        return jobResultDao.getNewCountSince(preferenceId, since)
    }

    suspend fun deleteJobResultsOlderThan(before: Long) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteJobResultsOlderThan", mapOf("before" to before))
        jobResultDao.deleteOlderThan(before)
    }

    // ================================================================
    // JobAlertRun operations (per user isolation via userEmail)
    // ================================================================

    fun getAlertRuns(userEmail: String): Flow<List<JobAlertRunEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getAlertRuns", mapOf("userEmail" to userEmail))
        return alertRunDao.getByUser(userEmail)
    }

    fun getRecentRunsByPreference(preferenceId: String, limit: Int = 20): Flow<List<JobAlertRunEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getRecentRunsByPreference", mapOf("preferenceId" to preferenceId, "limit" to limit))
        return alertRunDao.getRecentByPreference(preferenceId, limit)
    }

    suspend fun getLastRun(preferenceId: String): JobAlertRunEntity? {
        return alertRunDao.getLastRun(preferenceId)
    }

    suspend fun getLatestRunByUser(userEmail: String): JobAlertRunEntity? {
        return alertRunDao.getLatestByUser(userEmail)
    }

    suspend fun insertAlertRun(entity: JobAlertRunEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "insertAlertRun", mapOf("id" to entity.id, "preferenceId" to entity.preferenceId, "status" to entity.status))
        alertRunDao.insert(entity)
    }

    suspend fun updateAlertRun(entity: JobAlertRunEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateAlertRun", mapOf("id" to entity.id, "status" to entity.status))
        alertRunDao.update(entity)
    }

    suspend fun deleteAllRunsForUser(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteAllRunsForUser", mapOf("userEmail" to userEmail))
        alertRunDao.deleteAllByUser(userEmail)
    }

    // ================================================================
    // JobEmailLedger operations (per user isolation via userEmail)
    // ================================================================

    fun getEmailLedger(userEmail: String): Flow<List<JobEmailLedgerEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getEmailLedger", mapOf("userEmail" to userEmail))
        return emailLedgerDao.getByUser(userEmail)
    }

    fun getEmailLedgerByPreference(preferenceId: String): Flow<List<JobEmailLedgerEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getEmailLedgerByPreference", mapOf("preferenceId" to preferenceId))
        return emailLedgerDao.getByPreference(preferenceId)
    }

    suspend fun getLastEmailSent(preferenceId: String): JobEmailLedgerEntity? {
        return emailLedgerDao.getLastSentByPreference(preferenceId)
    }

    suspend fun insertEmailLedger(entity: JobEmailLedgerEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "insertEmailLedger", mapOf("id" to entity.id, "preferenceId" to entity.preferenceId, "status" to entity.status))
        emailLedgerDao.insert(entity)
    }

    suspend fun updateEmailStatus(id: String, status: String, errorMessage: String? = null, messageId: String? = null) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateEmailStatus", mapOf("id" to id, "status" to status))
        emailLedgerDao.updateStatus(id, status, errorMessage, messageId)
    }

    fun getEmailCount(userEmail: String): Flow<Int> {
        return emailLedgerDao.getCountByUser(userEmail)
    }

    suspend fun deleteAllEmailLedgerForUser(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteAllEmailLedgerForUser", mapOf("userEmail" to userEmail))
        emailLedgerDao.deleteAllByUser(userEmail)
    }

    // ================================================================
    // DeviceSchedulerState operations (per user isolation via userEmail)
    // ================================================================

    suspend fun getSchedulerState(userEmail: String): DeviceSchedulerStateEntity? {
        FcLog.d(FcLog.TAG_SCHEDULER, "getSchedulerState", mapOf("userEmail" to userEmail))
        return schedulerStateDao.getByUser(userEmail)
    }

    fun observeSchedulerState(userEmail: String): Flow<DeviceSchedulerStateEntity?> {
        FcLog.d(FcLog.TAG_SCHEDULER, "observeSchedulerState", mapOf("userEmail" to userEmail))
        return schedulerStateDao.observeByUser(userEmail)
    }

    suspend fun saveSchedulerState(entity: DeviceSchedulerStateEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "saveSchedulerState", mapOf("userEmail" to entity.userEmail, "enabled" to entity.schedulerEnabled))
        schedulerStateDao.insert(entity)
    }

    suspend fun updateSchedulerState(entity: DeviceSchedulerStateEntity) {
        FcLog.d(FcLog.TAG_SCHEDULER, "updateSchedulerState", mapOf("userEmail" to entity.userEmail, "enabled" to entity.schedulerEnabled))
        schedulerStateDao.update(entity)
    }

    fun getAllEnabledSchedulerStates(): Flow<List<DeviceSchedulerStateEntity>> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getAllEnabledSchedulerStates")
        return schedulerStateDao.getAllEnabled()
    }

    suspend fun getAllEnabledSchedulerStatesNow(): List<DeviceSchedulerStateEntity> {
        FcLog.d(FcLog.TAG_SCHEDULER, "getAllEnabledSchedulerStatesNow")
        return schedulerStateDao.getAllEnabledNow()
    }

    suspend fun deleteSchedulerState(userEmail: String) {
        FcLog.d(FcLog.TAG_SCHEDULER, "deleteSchedulerState", mapOf("userEmail" to userEmail))
        schedulerStateDao.deleteByUser(userEmail)
    }

    suspend fun isSchedulerPausedDueToLogout(userEmail: String): Boolean {
        return schedulerStateDao.getPausedByUser(userEmail) != null
    }

    suspend fun markSchedulerPausedDueToLogout(userEmail: String) {
        FcLog.i(FcLog.TAG_SCHEDULER, "markSchedulerPausedDueToLogout", mapOf("userEmail" to userEmail))
        schedulerStateDao.markPausedDueToLogout(userEmail)
    }

    suspend fun markSchedulerResumed(userEmail: String) {
        FcLog.i(FcLog.TAG_SCHEDULER, "markSchedulerResumed", mapOf("userEmail" to userEmail))
        schedulerStateDao.markResumed(userEmail)
    }

    // ================================================================
    // Full user cleanup (on logout)
    // ================================================================

    suspend fun deleteAllUserData(userEmail: String) {
        FcLog.i(FcLog.TAG_SCHEDULER, "deleteAllUserData", mapOf("userEmail" to userEmail))
        preferenceDao.deleteAllByUser(userEmail)
        jobResultDao.deleteAllByUser(userEmail)
        alertRunDao.deleteAllByUser(userEmail)
        emailLedgerDao.deleteAllByUser(userEmail)
        schedulerStateDao.deleteByUser(userEmail)
        FcLog.i(FcLog.TAG_SCHEDULER, "deleteAllUserData complete", mapOf("userEmail" to userEmail))
    }
}
