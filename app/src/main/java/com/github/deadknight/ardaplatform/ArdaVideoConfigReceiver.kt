package com.github.deadknight.ardaplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives root/shell SDR reports from arda-connectivityd and persists them. */
class ArdaVideoConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ArdaVideoConfigContract.actionReportSdr) return
        val payload = intent.getStringExtra(ArdaVideoConfigContract.extraPayload)
            ?.takeIf(String::isNotBlank)
            ?: run {
                ArdaPlatformState.log("ARDA_VIDEO_SDR_PERSIST=FAIL reason=EMPTY_PAYLOAD")
                return
            }
        runCatching {
            ArdaVideoConfigStore.reportSdr(context, payload)
            context.contentResolver.notifyChange(ArdaPlatformContract.snapshotUri, null)
        }.onFailure { error ->
            ArdaPlatformState.log(
                "ARDA_VIDEO_SDR_PERSIST=FAIL reason=${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
}
