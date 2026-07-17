package com.github.deadknight.ardaplatform

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Final user-facing entry point.
 *
 * The transport core is a device-wide singleUser service. Launching the icon
 * merely requests that service and immediately closes the Activity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(
            Intent(this, ArdaBootstrapServerService::class.java)
                .setAction(ArdaBootstrapServerService.ACTION_START)
                .putExtra(ArdaBootstrapServerService.EXTRA_START_SOURCE, "ACTIVITY"),
        )
        ArdaPlatformState.log("ARDA_RN3B_AAOS_ACTIVITY_FINISH=SINGLE_USER_SERVICE_HANDOFF")
        finishAndRemoveTask()
    }
}
