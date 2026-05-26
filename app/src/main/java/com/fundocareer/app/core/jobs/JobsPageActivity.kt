package com.fundocareer.app.core.jobs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fundocareer.app.core.logging.FcLog

class JobsPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FcLog.i(FcLog.TAG_APP, "JobsPageActivity created")
        setContent {
            JobsPageScreen(onBack = { finish() })
        }
    }
}
