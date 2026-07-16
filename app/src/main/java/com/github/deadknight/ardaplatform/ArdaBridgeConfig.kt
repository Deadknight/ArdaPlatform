package com.github.deadknight.ardaplatform

import android.content.Context

data class ArdaBridgeConfig(
    val host: String,
    val port: Int,
    val token: String,
)

object ArdaBridgeConfigStore {
    private const val prefsName = "arda_platform_bootstrap"
    private const val keyHost = "bridge_host"
    private const val keyPort = "bridge_port"
    private const val keyToken = "bridge_token"

    fun load(context: Context): ArdaBridgeConfig {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return ArdaBridgeConfig(
            host = prefs.getString(
                keyHost,
                ArdaBluetoothTransport.defaultBridgeHost,
            ).orEmpty().ifBlank { ArdaBluetoothTransport.defaultBridgeHost },
            port = prefs.getInt(
                keyPort,
                ArdaBluetoothTransport.defaultBridgePort,
            ),
            token = prefs.getString(
                keyToken,
                ArdaBluetoothTransport.defaultDebugToken,
            ).orEmpty(),
        )
    }

    fun save(context: Context, config: ArdaBridgeConfig) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyHost, config.host)
            .putInt(keyPort, config.port)
            .putString(keyToken, config.token)
            .apply()
    }
}
