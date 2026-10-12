package com.roamwifi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.roamwifi.app.service.RoamService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoamTheme { RoamScreen() } }
    }

    override fun onDestroy() {
        // Back button / finishing the app = "closed": stop scanning to save battery.
        // (Pressing Home only stops the activity, so roaming continues with its notification.)
        if (isFinishing) RoamService.stop(this)
        super.onDestroy()
    }
}
