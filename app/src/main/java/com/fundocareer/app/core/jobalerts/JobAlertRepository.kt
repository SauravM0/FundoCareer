package com.fundocareer.app.core.jobalerts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

class JobAlertRepository private constructor(
    private val preferenceDao: JobAlertPreferenceDao,
    private val jobResultDao: JobResultDao,
    private val alertRunDao: JobAlertRunDao,
    private val emailLedgerDao: JobEmailLedgerDao,
    private val schedulerStateDao: DeviceSchedulerStateDao
) {

    companion object {
        private const val TAG = "JobAlertRepository"

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
        Log.d(TAG, "getPreferences: userEmail=$userEmail")
        return preferenceDao.getAllByUser(userEmail)
    }

    fun getActivePreferences(userEmail: String): Flow<List<JobAlertPreferenceEntity>> {
        Log.d(TAG, "getActivePreferences: userEmail=$userEmail")
        return preferenceDao.getActiveByUser(userEmail)
    }

    suspend fun getPreferenceById(id: String): JobAlertPreferenceEntity? {
        Log.d(TAG, "getPreferenceById: id=$id")
        return preferenceDao.getById(id)
    }

    suspend fun savePreference(entity: JobAlertPreferenceEntity) {
        Log.d(TAG, "savePreference: id=${entity.id}, userEmail=${entity.userEmail}, role=${entity.role}")
        preferenceDao.insert(entity)
    }

    suspend fun updatePreference(entity: JobAlertPreferenceEntity) {
        Log.d(TAG, "updatePreference: id=${entity.id}, userEmail=${entity.userEmail}")
        preferenceDao.update(entity)
    }

    suspend fun deletePreference(id: String) {
        Log.d(TAG, "deletePreference: id=$id")
        preferenceDao.deleteById(id)
    }

    suspend fun deleteAllPreferencesForUser(userEmail: String) {
        Log.d(TAG, "deleteAllPreferencesForUser: userEmail=$userEmail")
        preferenceDao.deleteAllByUser(userEmail)
    }

    fun getPreferenceCount(userEmail: String): Flow<Int> {
        return preferenceDao.getCountByUser(userEmail)
    }

    suspend fun getActivePreferenceCount(userEmail: String): Int {
        Log.d(TAG, "getActivePreferenceCount: userEmail=$userEmail")
        return preferenceDao.getActiveCountByUserNow(userEmail)
    }

    suspend fun getActivePreferenceNow(userEmail: String): JobAlertPreferenceEntity? {
        Log.d(TAG, "getActivePreferenceNow: userEmail=$userEmail")
        return preferenceDao.getActiveByUserNow(userEmail)
    }

    suspend fun getAnyActivePreferenceNow(): JobAlertPreferenceEntity? {
        Log.d(TAG, "getAnyActivePreferenceNow")
        return preferenceDao.getAnyActiveNow()
    }

    suspend fun getAllActiveEnabledPreferences(): List<JobAlertPreferenceEntity> {
        Log.d(TAG, "getAllActiveEnabledPreferences")
        return preferenceDao.getAllActiveEnabledNow()
    }

    suspend fun markSchedulerEnabled(preferenceId: String, enabled: Boolean) {
        Log.d(TAG, "markSchedulerEnabled: preferenceId=$preferenceId, enabled=$enabled")
        preferenceDao.markSchedulerEnabled(preferenceId, enabled)
    }

    suspend fun markStoppedByUser(preferenceId: String, stopped: Boolean) {
        Log.d(TAG, "markStoppedByUser: preferenceId=$preferenceId, stopped=$stopped")
        preferenceDao.markStoppedByUser(preferenceId, stopped)
    }

    suspend fun updateNextScheduledRunAt(preferenceId: String, nextRunAt: Long?) {
        Log.d(TAG, "updateNextScheduledRunAt: preferenceId=$preferenceId, nextRunAt=$nextRunAt")
        preferenceDao.updateNextScheduledRunAt(preferenceId, nextRunAt)
    }

    suspend fun updateLastAttemptRunAt(preferenceId: String, timestamp: Long) {
        Log.d(TAG, "updateLastAttemptRunAt: preferenceId=$preferenceId, timestamp=$timestamp")
        preferenceDao.updateLastAttemptRunAt(preferenceId, timestamp)
    }

    suspend fun updateLastSuccessfulRunAt(preferenceId: String, timestamp: Long) {
        Log.d(TAG, "updateLastSuccessfulRunAt: preferenceId=$preferenceId, timestamp=$timestamp")
        preferenceDao.updateLastSuccessfulRunAt(preferenceId, timestamp)
    }

    suspend fun updateLastSuccessfulEmailAt(preferenceId: String, timestamp: Long) {
        Log.d(TAG, "updateLastSuccessfulEmailAt: preferenceId=$preferenceId, timestamp=$timestamp")
        preferenceDao.updateLastSuccessfulEmailAt(preferenceId, timestamp)
    }

    suspend fun markPendingDueRun(preferenceId: String, dueAt: Long?) {
        Log.d(TAG, "markPendingDueRun: preferenceId=$preferenceId, dueAt=$dueAt")
        preferenceDao.markPendingDueRun(preferenceId, dueAt)
    }

    suspend fun clearPendingDueRun(preferenceId: String) {
        Log.d(TAG, "clearPendingDueRun: preferenceId=$preferenceId")
        preferenceDao.clearPendingDueRun(preferenceId)
    }

    suspend fun pauseAllDueToLogout(userEmail: String) {
        Log.d(TAG, "pauseAllDueToLogout: userEmail=$userEmail")
        preferenceDao.pauseAllDueToLogout(userEmail)
    }

    suspend fun resumePausedDueToLogout(userEmail: String) {
        Log.d(TAG, "resumePausedDueToLogout: userEmail=$userEmail")
        preferenceDao.resumePausedDueToLogout(userEmail, active = true)
    }

    // ================================================================
    // JobResult operations (per user isolation via userEmail)
    // ================================================================

    fun getJobResults(userEmail: String): Flow<List<JobResultEntity>> {
        Log.d(TAG, "getJobResults: userEmail=$userEmail")
        return jobResultDao.getByUser(userEmail)
    }

    fun getJobResultsByPreference(preferenceId: String): Flow<List<JobResultEntity>> {
        Log.d(TAG, "getJobResultsByPreference: preferenceId=$preferenceId")
        return jobResultDao.getByPreference(preferenceId)
    }

    suspend fun getJobResultsByRun(runId: String): List<JobResultEntity> {
        Log.d(TAG, "getJobResultsByRun: runId=$runId")
        return jobResultDao.getByRun(runId)
    }

    suspend fun getJobResultByFingerprint(fingerprint: String): JobResultEntity? {
        Log.d(TAG, "getJobResultByFingerprint: fingerprint=$fingerprint")
        return jobResultDao.getByFingerprint(fingerprint)
    }

    suspend fun insertJobResult(entity: JobResultEntity): Long {
        Log.d(TAG, "insertJobResult: id=${entity.id}, title=${entity.jobTitle}")
        return jobResultDao.insert(entity)
    }

    suspend fun insertJobResults(entities: List<JobResultEntity>): List<Long> {
        Log.d(TAG, "insertJobResults: count=${entities.size}")
        return jobResultDao.insertAll(entities)
    }

    suspend fun deleteJobResult(id: String) {
        Log.d(TAG, "deleteJobResult: id=$id")
        jobResultDao.deleteById(id)
    }

    suspend fun deleteAllJobResultsForUser(userEmail: String) {
        Log.d(TAG, "deleteAllJobResultsForUser: userEmail=$userEmail")
        jobResultDao.deleteAllByUser(userEmail)
    }

    fun getJobResultCount(userEmail: String): Flow<Int> {
        return jobResultDao.getCountByUser(userEmail)
    }

    suspend fun getNewJobCountSince(preferenceId: String, since: Long): Int {
        return jobResultDao.getNewCountSince(preferenceId, since)
    }

    suspend fun deleteJobResultsOlderThan(before: Long) {
        Log.d(TAG, "deleteJobResultsOlderThan: before=$before")
        jobResultDao.deleteOlderThan(before)
    }

    // ================================================================
    // JobAlertRun operations (per user isolation via userEmail)
    // ================================================================

    fun getAlertRuns(userEmail: String): Flow<List<JobAlertRunEntity>> {
        Log.d(TAG, "getAlertRuns: userEmail=$userEmail")
        return alertRunDao.getByUser(userEmail)
    }

    fun getRecentRunsByPreference(preferenceId: String, limit: Int = 20): Flow<List<JobAlertRunEntity>> {
        Log.d(TAG, "getRecentRunsByPreference: preferenceId=$preferenceId, limit=$limit")
        return alertRunDao.getRecentByPreference(preferenceId, limit)
    }

    suspend fun getLastRun(preferenceId: String): JobAlertRunEntity? {
        return alertRunDao.getLastRun(preferenceId)
    }

    suspend fun getLatestRunByUser(userEmail: String): JobAlertRunEntity? {
        return alertRunDao.getLatestByUser(userEmail)
    }

    suspend fun insertAlertRun(entity: JobAlertRunEntity) {
        Log.d(TAG, "insertAlertRun: id=${entity.id}, preferenceId=${entity.preferenceId}, status=${entity.status}")
        alertRunDao.insert(entity)
    }

    suspend fun updateAlertRun(entity: JobAlertRunEntity) {
        Log.d(TAG, "updateAlertRun: id=${entity.id}, status=${entity.status}")
        alertRunDao.update(entity)
    }

    suspend fun deleteAllRunsForUser(userEmail: String) {
        Log.d(TAG, "deleteAllRunsForUser: userEmail=$userEmail")
        alertRunDao.deleteAllByUser(userEmail)
    }

    // ================================================================
    // JobEmailLedger operations (per user isolation via userEmail)
    // ================================================================

    fun getEmailLedger(userEmail: String): Flow<List<JobEmailLedgerEntity>> {
        Log.d(TAG, "getEmailLedger: userEmail=$userEmail")
        return emailLedgerDao.getByUser(userEmail)
    }

    fun getEmailLedgerByPreference(preferenceId: String): Flow<List<JobEmailLedgerEntity>> {
        Log.d(TAG, "getEmailLedgerByPreference: preferenceId=$preferenceId")
        return emailLedgerDao.getByPreference(preferenceId)
    }

    suspend fun getLastEmailSent(preferenceId: String): JobEmailLedgerEntity? {
        return emailLedgerDao.getLastSentByPreference(preferenceId)
    }

    suspend fun insertEmailLedger(entity: JobEmailLedgerEntity) {
        Log.d(TAG, "insertEmailLedger: id=${entity.id}, preferenceId=${entity.preferenceId}, status=${entity.status}")
        emailLedgerDao.insert(entity)
    }

    suspend fun updateEmailStatus(id: String, status: String, errorMessage: String? = null, messageId: String? = null) {
        Log.d(TAG, "updateEmailStatus: id=$id, status=$status")
        emailLedgerDao.updateStatus(id, status, errorMessage, messageId)
    }

    fun getEmailCount(userEmail: String): Flow<Int> {
        return emailLedgerDao.getCountByUser(userEmail)
    }

    suspend fun deleteAllEmailLedgerForUser(userEmail: String) {
        Log.d(TAG, "deleteAllEmailLedgerForUser: userEmail=$userEmail")
        emailLedgerDao.deleteAllByUser(userEmail)
    }

    // ================================================================
    // DeviceSchedulerState operations (per user isolation via userEmail)
    // ================================================================

    suspend fun getSchedulerState(userEmail: String): DeviceSchedulerStateEntity? {
        Log.d(TAG, "getSchedulerState: userEmail=$userEmail")
        return schedulerStateDao.getByUser(userEmail)
    }

    fun observeSchedulerState(userEmail: String): Flow<DeviceSchedulerStateEntity?> {
        Log.d(TAG, "observeSchedulerState: userEmail=$userEmail")
        return schedulerStateDao.observeByUser(userEmail)
    }

    suspend fun saveSchedulerState(entity: DeviceSchedulerStateEntity) {
        Log.d(TAG, "saveSchedulerState: userEmail=${entity.userEmail}, enabled=${entity.schedulerEnabled}")
        schedulerStateDao.insert(entity)
    }

    suspend fun updateSchedulerState(entity: DeviceSchedulerStateEntity) {
        Log.d(TAG, "updateSchedulerState: userEmail=${entity.userEmail}, enabled=${entity.schedulerEnabled}")
        schedulerStateDao.update(entity)
    }

    fun getAllEnabledSchedulerStates(): Flow<List<DeviceSchedulerStateEntity>> {
        Log.d(TAG, "getAllEnabledSchedulerStates")
        return schedulerStateDao.getAllEnabled()
    }

    suspend fun getAllEnabledSchedulerStatesNow(): List<DeviceSchedulerStateEntity> {
        Log.d(TAG, "getAllEnabledSchedulerStatesNow")
        return schedulerStateDao.getAllEnabledNow()
    }

    suspend fun deleteSchedulerState(userEmail: String) {
        Log.d(TAG, "deleteSchedulerState: userEmail=$userEmail")
        schedulerStateDao.deleteByUser(userEmail)
    }

    suspend fun isSchedulerPausedDueToLogout(userEmail: String): Boolean {
        return schedulerStateDao.getPausedByUser(userEmail) != null
    }

    suspend fun markSchedulerPausedDueToLogout(userEmail: String) {
        Log.i(TAG, "markSchedulerPausedDueToLogout: userEmail=$userEmail")
        schedulerStateDao.markPausedDueToLogout(userEmail)
    }

    suspend fun markSchedulerResumed(userEmail: String) {
        Log.i(TAG, "markSchedulerResumed: userEmail=$userEmail")
        schedulerStateDao.markResumed(userEmail)
    }

    // ================================================================
    // Full user cleanup (on logout)
    // ================================================================

    suspend fun deleteAllUserData(userEmail: String) {
        Log.i(TAG, "deleteAllUserData: userEmail=$userEmail")
        preferenceDao.deleteAllByUser(userEmail)
        jobResultDao.deleteAllByUser(userEmail)
        alertRunDao.deleteAllByUser(userEmail)
        emailLedgerDao.deleteAllByUser(userEmail)
        schedulerStateDao.deleteByUser(userEmail)
        Log.i(TAG, "deleteAllUserData complete: userEmail=$userEmail")
    }
}
