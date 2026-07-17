package com.github.deadknight.ardaplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager

/** Device-wide boot owner for the single-user ARDA transport service. */
class ArdaPlatformBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val serviceIntent = Intent(context, ArdaBootstrapServerService::class.java)
                    .setAction(ArdaBootstrapServerService.ACTION_START)
                    .putExtra(
                        ArdaBootstrapServerService.EXTRA_START_SOURCE,
                        intent.action ?: "unknown",
                    )
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                val user = if (context.getSystemService(UserManager::class.java)?.isSystemUser == true) 0 else -1
                ArdaPlatformState.log(
                    "ARDA_RN3B_AAOS_BOOT_START_REQUEST=" +
                        "${intent.action ?: "unknown"};user=$user",
                )
            }
        }
    }
}
