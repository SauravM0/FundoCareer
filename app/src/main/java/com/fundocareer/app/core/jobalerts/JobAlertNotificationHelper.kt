package com.fundocareer.app.core.jobalerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fundocareer.app.core.logging.FcLog

object JobAlertNotificationHelper {
    private const val CHANNEL_ID = "fundocareer_job_alerts"
    private const val CHANNEL_NAME = "Job Alerts"
    private const val CHANNEL_DESC = "Notifications for new job matches"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESC
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        FcLog.d(FcLog.TAG_PERMISSION, "Notification channel created", mapOf(
            "channelId" to CHANNEL_ID,
        ))
    }

    fun showJobAlertNotification(
        context: Context,
        count: Int,
        preferenceName: String,
        jobsNew: Int,
        jobsFound: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                FcLog.w(FcLog.TAG_PERMISSION, "POST_NOTIFICATIONS not granted, skipping notification")
                return
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$count new job${
                if (count == 1) "" else "s"
            } found")
            .setContentText("${preferenceName.take(60)} — $jobsNew new of $jobsFound total")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${preferenceName.take(120)}\n$jobsNew new job${if (jobsNew == 1) "" else "s"} matched your alert (out of $jobsFound found)"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        FcLog.i(FcLog.TAG_EMAIL, "Notification shown", mapOf(
            "count" to count,
            "preferenceName" to preferenceName,
        ))
    }
}
