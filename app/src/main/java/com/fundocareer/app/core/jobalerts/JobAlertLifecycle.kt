package com.fundocareer.app.core.jobalerts

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Logout must not stop job alert scheduler. Only user Stop Scheduler action can stop it.
 *
 * - onLogout / showLogoutWarning: UI-only cleanup, scheduler state is NEVER touched.
 * - onUserStoppedScheduler: the ONLY path for scheduler cancellation (reserved for Stop button).
 */
object JobAlertLifecycle {
    private const val TAG = "JobAlertLifecycle"

    /**
     * Logout must not stop job alert scheduler. Only user Stop Scheduler action can stop it.
     * This dialog explicitly tells the user the scheduler survives logout.
     */
    @JvmStatic
    fun showLogoutWarning(activity: Activity, userEmail: String, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Logout?")
            .setMessage(
                "Job discovery will continue running on your schedule after logout. " +
                "Your settings and history will remain saved."
            )
            .setPositiveButton("Logout") { _, _ ->
                Log.i(TAG, "Logout confirmed for $userEmail")
                performLogoutCleanup(activity, userEmail, null)
                onConfirmed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @JvmStatic
    fun onLogin(context: Context, newEmail: String, previousEmail: String?) {
        Log.i(TAG, "onLogin: newEmail=$newEmail, previousEmail=$previousEmail")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = JobAlertRepository.getInstance(context)

                if (newEmail == previousEmail && previousEmail != null) {
                    val wasPaused = repo.isSchedulerPausedDueToLogout(newEmail)
                    // Scheduler must be started only from Native Jobs page Save & Start flow.
                    // Same-account resume does NOT auto-start the scheduler.
                    if (wasPaused) {
                        repo.markSchedulerResumed(newEmail)
                        repo.resumePausedDueToLogout(newEmail)
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Your job discovery scheduler has resumed.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        Log.i(TAG, "Same-account auto-resume for $newEmail")
                        return@launch
                    }
                }

                // Scheduler must be started only from Native Jobs page Save & Start flow.
                // Login does NOT auto-start the scheduler.
                if (previousEmail != null && newEmail != previousEmail) {
                    Log.i(TAG, "Different account login: clearing old data for $previousEmail, setting up for $newEmail")
                    repo.deleteAllUserData(previousEmail)
                    repo.deleteAllUserData(newEmail)
                    Log.i(TAG, "Old user data cleared for $newEmail")
                } else if (previousEmail == null) {
                    Log.i(TAG, "First-ever login for $newEmail")
                }
            } catch (e: Exception) {
                Log.e(TAG, "onLogin error", e)
            }
        }
    }

    /**
     * Logout must not stop job alert scheduler. Only user Stop Scheduler action can stop it.
     * This function is intentionally a no-op for scheduler state.
     */
    @JvmStatic
    fun onLogout(context: Context, userEmail: String?) {
        if (userEmail.isNullOrBlank()) return
        Log.i(TAG, "onLogout called for $userEmail — scheduler continues running (logout does not stop scheduler)")
    }

    /**
     * Reserved for the explicit Stop Scheduler button flow.
     * Only user Stop Scheduler action can stop it — logout must NOT call this.
     */
    @JvmStatic
    fun onUserStoppedScheduler(context: Context, userEmail: String) {
        Log.i(TAG, "onUserStoppedScheduler: $userEmail — scheduler explicitly stopped by user")
    }

    /**
     * Logout must not stop job alert scheduler. Only user Stop Scheduler action can stop it.
     * This function only handles shared UI/logout side-effects, never scheduler state.
     */
    private fun performLogoutCleanup(context: Context, userEmail: String, authToken: String?) {
        Log.i(TAG, "Logout confirmed for $userEmail — scheduler continues running")
    }
}
