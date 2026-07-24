package com.github.deadknight.ardaplatform

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

data class ArdaBridgeConfig(
    val host: String,
    val port: Int,
    val token: String,
    val keepBootstrapRfcommOpen: Boolean = true,
    val keepBluetoothProfilesConnected: Boolean = true,
    val forcedVideoResolution: String = "",
)

object ArdaBridgeConfigStore {
    private const val prefsName = "arda_platform_bootstrap"
    private const val keyHost = "bridge_host"
    private const val keyPort = "bridge_port"
    private const val keyToken = "bridge_token"
    private const val keyKeepBootstrapRfcommOpen = "keep_bootstrap_rfcomm_open"
    private const val keyKeepBluetoothProfilesConnected =
        "keep_bluetooth_profiles_connected"
    private const val runtimeTomlName = "arda-connectivity.toml"
    private const val legacyRuntimeTomlName = "arda-rn3b.toml"
    private const val maxBridgeTokenBytes = 512
    private const val generatedTokenLength = 40
    private const val tokenAlphabet =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_"

    fun ensureRuntimeConfig(context: Context): ArdaBridgeConfig {
        val storage = storageContext(context)
        val prefs = storage.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existingToken = prefs.getString(keyToken, null).orEmpty()
        if (existingToken.isBlank()) {
            val generated = generateToken()
            check(
                prefs.edit()
                    .putString(keyHost, ArdaBluetoothTransport.defaultBridgeHost)
                    .putInt(keyPort, ArdaBluetoothTransport.defaultBridgePort)
                    .putString(keyToken, generated)
                    .putBoolean(keyKeepBootstrapRfcommOpen, true)
                    .putBoolean(keyKeepBluetoothProfilesConnected, true)
                    .commit(),
            ) { "Unable to persist connectivity bridge identity" }
            ArdaPlatformState.log("ARDA_RN3B_AAOS_BRIDGE_TOKEN_SOURCE=FIRST_BOOT_GENERATED")
        }

        val config = load(storage)
        writeRuntimeToml(storage, config)
        ArdaVideoConfigStore.ensureRuntimeConfig(storage)
        return config
    }

    fun load(context: Context): ArdaBridgeConfig {
        val storage = storageContext(context)
        val prefs = storage.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return ArdaBridgeConfig(
            host = prefs.getString(
                keyHost,
                ArdaBluetoothTransport.defaultBridgeHost,
            ).orEmpty().ifBlank { ArdaBluetoothTransport.defaultBridgeHost },
            port = prefs.getInt(
                keyPort,
                ArdaBluetoothTransport.defaultBridgePort,
            ),
            token = prefs.getString(keyToken, null)
                .orEmpty()
                .ifBlank { ArdaBluetoothTransport.defaultDebugToken },
            keepBootstrapRfcommOpen = prefs.getBoolean(
                keyKeepBootstrapRfcommOpen,
                true,
            ),
            keepBluetoothProfilesConnected = prefs.getBoolean(
                keyKeepBluetoothProfilesConnected,
                true,
            ),
            forcedVideoResolution = ArdaVideoConfigStore.load(storage).forcedResolution,
        )
    }

    fun save(context: Context, config: ArdaBridgeConfig) {
        validate(config)
        val storage = storageContext(context)
        check(
            storage.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(keyHost, config.host)
                .putInt(keyPort, config.port)
                .putString(keyToken, config.token)
                .putBoolean(
                    keyKeepBootstrapRfcommOpen,
                    config.keepBootstrapRfcommOpen,
                )
                .putBoolean(
                    keyKeepBluetoothProfilesConnected,
                    config.keepBluetoothProfilesConnected,
                )
                .commit(),
        ) { "Unable to persist connectivity bridge configuration" }
        ArdaVideoConfigStore.saveForcedResolution(storage, config.forcedVideoResolution)
        writeRuntimeToml(storage, config)
    }

    fun saveAndReload(context: Context, config: ArdaBridgeConfig) {
        save(context, config)
        context.startForegroundService(
            Intent(context, ArdaBootstrapServerService::class.java)
                .setAction(ArdaBootstrapServerService.ACTION_RELOAD_CONFIG),
        )
    }

    fun runtimeTomlFile(context: Context): File =
        File(storageContext(context).filesDir, runtimeTomlName)

    fun generateReplacementToken(): String = generateToken()

    private fun validate(config: ArdaBridgeConfig) {
        require(config.host == ArdaBluetoothTransport.defaultBridgeHost) {
            "Connectivity bridge host must remain loopback-only"
        }
        require(config.port in 1..65535) { "Bridge port must be 1..65535" }
        require(config.token.isNotBlank()) { "Bridge token must not be blank" }
        require(config.token.toByteArray(Charsets.UTF_8).size <= maxBridgeTokenBytes) {
            "Bridge token exceeds $maxBridgeTokenBytes bytes"
        }
        ArdaVideoConfigStore.normalizeForcedResolution(config.forcedVideoResolution)
    }

    private fun writeRuntimeToml(context: Context, config: ArdaBridgeConfig) {
        validate(config)
        val target = File(context.filesDir, runtimeTomlName)
        val temp = File(context.filesDir, "$runtimeTomlName.tmp")
        context.filesDir.mkdirs()

        val text = buildString {
            appendLine("# Managed by ArdaPlatform. Manual edits may be overwritten.")
            appendLine("[connectivity]")
            appendLine("transport = \"tcp\"")
            appendLine("addr = \"${escapeToml(config.host)}:${config.port}\"")
            appendLine("bridge_token = \"${escapeToml(config.token)}\"")
            appendLine("once = false")
            appendLine(
                "keep_bootstrap_rfcomm_open = ${config.keepBootstrapRfcommOpen}",
            )
            appendLine(
                "keep_bluetooth_profiles_connected = " +
                    config.keepBluetoothProfilesConnected,
            )
        }

        FileOutputStream(temp, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        temp.setReadable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(false, false)
        temp.setWritable(true, true)
        check(temp.renameTo(target)) { "Unable to atomically replace ${target.path}" }

        val legacy = File(context.filesDir, legacyRuntimeTomlName)
        if (legacy.exists() && legacy != target) {
            legacy.delete()
        }

        ArdaPlatformState.log("ARDA_RN3B_AAOS_SENDERD_CONFIG_WRITE=PASS")
        ArdaPlatformState.log("ARDA_RN3B_AAOS_SENDERD_CONFIG_PATH=${target.path}")
    }

    private fun storageContext(context: Context): Context =
        context.createDeviceProtectedStorageContext()

    private fun generateToken(): String {
        val random = SecureRandom()
        return buildString(generatedTokenLength) {
            repeat(generatedTokenLength) {
                append(tokenAlphabet[random.nextInt(tokenAlphabet.length)])
            }
        }
    }

    private fun escapeToml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
