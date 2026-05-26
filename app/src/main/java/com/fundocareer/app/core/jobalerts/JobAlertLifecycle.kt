package com.fundocareer.app.core.jobalerts

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fundocareer.app.core.logging.FcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object JobAlertLifecycle {

    @JvmStatic
    fun showLogoutWarning(activity: Activity, userEmail: String, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Logout?")
            .setMessage(
                "Job discovery will continue running on your schedule after logout. " +
                "Your settings and history will remain saved."
            )
            .setPositiveButton("Logout") { _, _ ->
                FcLog.i(FcLog.TAG_SCHEDULER, "Logout confirmed", mapOf(
                    "userEmail" to FcLog.maskEmail(userEmail),
                ))
                performLogoutCleanup(activity, userEmail, null)
                onConfirmed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @JvmStatic
    fun onLogin(context: Context, newEmail: String, previousEmail: String?) {
        FcLog.i(FcLog.TAG_SCHEDULER, "onLogin", mapOf(
            "newEmail" to FcLog.maskEmail(newEmail),
            "previousEmail" to previousEmail?.let { FcLog.maskEmail(it) },
        ))
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = JobAlertRepository.getInstance(context)

                if (newEmail == previousEmail) {
                    val wasPaused = repo.isSchedulerPausedDueToLogout(newEmail)
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
                        FcLog.i(FcLog.TAG_SCHEDULER, "Same-account auto-resume", mapOf(
                            "userEmail" to FcLog.maskEmail(newEmail),
                        ))
                        return@launch
                    }
                }

                if (previousEmail != null && newEmail != previousEmail) {
                    FcLog.i(FcLog.TAG_SCHEDULER, "Different account login detected", mapOf(
                        "previous" to FcLog.maskEmail(previousEmail),
                        "newEmail" to FcLog.maskEmail(newEmail),
                    ))
                } else if (previousEmail == null) {
                    FcLog.i(FcLog.TAG_SCHEDULER, "First-ever login", mapOf(
                        "userEmail" to FcLog.maskEmail(newEmail),
                    ))
                }
            } catch (e: Exception) {
                FcLog.e(FcLog.TAG_SCHEDULER, "onLogin error", e)
            }
        }
    }

    @JvmStatic
    fun onLogout(context: Context, userEmail: String?) {
        if (userEmail.isNullOrBlank()) return
        FcLog.i(FcLog.TAG_SCHEDULER, "onLogout called - scheduler continues running", mapOf(
            "userEmail" to FcLog.maskEmail(userEmail),
        ))
    }

    @JvmStatic
    fun onUserStoppedScheduler(context: Context, userEmail: String) {
        FcLog.i(FcLog.TAG_SCHEDULER, "onUserStoppedScheduler - scheduler explicitly stopped by user", mapOf(
            "userEmail" to FcLog.maskEmail(userEmail),
        ))
    }

    private fun performLogoutCleanup(context: Context, userEmail: String, authToken: String?) {
        FcLog.i(FcLog.TAG_SCHEDULER, "Logout cleanup - scheduler continues running", mapOf(
            "userEmail" to FcLog.maskEmail(userEmail),
        ))
    }
}
