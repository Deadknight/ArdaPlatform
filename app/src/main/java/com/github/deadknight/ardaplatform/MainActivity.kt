package com.github.deadknight.ardaplatform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Vehicle-sized status and configuration entry point for the platform core. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArdaPlatformRepository.ensureCoreStarted(this)
        ArdaPlatformState.log("ARDA_RN3B_AAOS_ACTIVITY_OPEN=PLATFORM_UI")
        setContent {
            ArdaPlatformApp()
        }
    }
}
