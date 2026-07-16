package com.github.deadknight.ardaplatform

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

object ArdaPlatformState {
    private const val TAG = "ARDA_RN3B_AAOS"
    private val mainHandler = Handler(Looper.getMainLooper())

    val logs = mutableStateListOf<String>()
    val serverRunning = mutableStateOf(false)
    val peerConnected = mutableStateOf(false)
    val hotspotReady = mutableStateOf(false)
    val hotspotSsid = mutableStateOf<String?>(null)

    fun log(line: String) {
        Log.i(TAG, line)
        mainHandler.post {
            logs += line
            while (logs.size > 400) logs.removeAt(0)
        }
    }
}
