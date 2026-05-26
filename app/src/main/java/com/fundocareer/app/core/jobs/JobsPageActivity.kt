package com.fundocareer.app.core.jobs

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fundocareer.app.core.jobalerts.ui.theme.JobAlertsTheme

class JobsPageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("JobsPageActivity", "scheduler screen opened")
        enableEdgeToEdge()
        setContent {
            JobAlertsTheme {
                JobsPageScreen(onBack = { finish() })
            }
        }
    }
}
