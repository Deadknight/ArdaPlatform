package com.github.deadknight.ardaplatform

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** In-process runtime state exposed to the system-user UI/provider. */
object ArdaPlatformState {
    private const val TAG = "ARDA_RN3B_AAOS"
    private val mainHandler = Handler(Looper.getMainLooper())

    val logs = mutableStateListOf<String>()
    val serverRunning = mutableStateOf(false)
    val peerConnected = mutableStateOf(false)
    val bridgeConnected = mutableStateOf(false)
    val hotspotReady = mutableStateOf(false)
    val hotspotSsid = mutableStateOf<String?>(null)
    val hotspotBssid = mutableStateOf<String?>(null)
    val hotspotIp = mutableStateOf<String?>(null)
    val hotspotSecurity = mutableStateOf<String?>(null)
    val peerAddress = mutableStateOf<String?>(null)
    val lastEvent = mutableStateOf<String?>(null)
    val lastError = mutableStateOf<String?>(null)

    fun log(line: String) {
        Log.i(TAG, line)
        mainHandler.post {
            logs += line
            while (logs.size > 400) logs.removeAt(0)
            lastEvent.value = line
            if (
                line.contains("_ERROR=") ||
                line.contains("=FAIL") ||
                line.contains("_UNAVAILABLE=")
            ) {
                lastError.value = line
            } else if (
                line.contains("BRIDGE_AUTH=PASS") ||
                line.contains("LOHS_START=PASS") ||
                line.contains("CONFIG_RELOAD=PASS")
            ) {
                lastError.value = null
            }
        }
    }
}
