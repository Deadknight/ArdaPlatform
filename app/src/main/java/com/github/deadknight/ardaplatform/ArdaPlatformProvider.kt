package com.github.deadknight.ardaplatform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Signature-protected single-user bridge for the foreground driver UI.
 *
 * The provider always executes in the system-user core process, so configuration
 * updates target the TOML watched by arda-connectivity-supervisor even when the
 * Activity is later installed for a FULL driver user.
 */
class ArdaPlatformProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { ArdaBridgeConfigStore.ensureRuntimeConfig(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(matcher.match(uri) == matchSnapshot) { "Unsupported URI: $uri" }
        val coreContext = requireNotNull(context)
        val config = ArdaBridgeConfigStore.load(coreContext)
        val columns = snapshotColumns
        return MatrixCursor(columns, 1).apply {
            addRow(
                arrayOf(
                    config.host,
                    config.port,
                    config.token,
                    config.keepBootstrapRfcommOpen.asInt(),
                    config.keepBluetoothProfilesConnected.asInt(),
                    ArdaBridgeConfigStore.runtimeTomlFile(coreContext).path,
                    ArdaPlatformState.serverRunning.value.asInt(),
                    ArdaPlatformState.hotspotReady.value.asInt(),
                    ArdaPlatformState.hotspotSsid.value,
                    ArdaPlatformState.hotspotBssid.value,
                    ArdaPlatformState.hotspotIp.value,
                    ArdaPlatformState.hotspotSecurity.value,
                    ArdaPlatformState.peerConnected.value.asInt(),
                    ArdaPlatformState.peerAddress.value,
                    ArdaPlatformState.bridgeConnected.value.asInt(),
                    ArdaPlatformState.lastEvent.value,
                    ArdaPlatformState.lastError.value,
                ),
            )
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        require(matcher.match(uri) == matchSnapshot) { "Unsupported URI: $uri" }
        val coreContext = requireNotNull(context)
        val current = ArdaBridgeConfigStore.load(coreContext)
        val requested = requireNotNull(values) { "Configuration values are required" }
        val updated = ArdaBridgeConfig(
            host = requested.getAsString(ArdaPlatformContract.columnHost)
                ?: current.host,
            port = requested.getAsInteger(ArdaPlatformContract.columnPort)
                ?: current.port,
            token = requested.getAsString(ArdaPlatformContract.columnToken)
                ?: current.token,
            keepBootstrapRfcommOpen = requested.booleanOrDefault(
                ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
                current.keepBootstrapRfcommOpen,
            ),
            keepBluetoothProfilesConnected = requested.booleanOrDefault(
                ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
                current.keepBluetoothProfilesConnected,
            ),
        )
        ArdaBridgeConfigStore.saveAndReload(coreContext, updated)
        coreContext.contentResolver.notifyChange(ArdaPlatformContract.snapshotUri, null)
        return 1
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val coreContext = requireNotNull(context)
        when (method) {
            ArdaPlatformContract.methodEnsureStarted -> {
                coreContext.startForegroundService(
                    Intent(coreContext, ArdaBootstrapServerService::class.java)
                        .setAction(ArdaBootstrapServerService.ACTION_START)
                        .putExtra(
                            ArdaBootstrapServerService.EXTRA_START_SOURCE,
                            "PLATFORM_UI",
                        ),
                )
            }

            ArdaPlatformContract.methodReloadConfig -> {
                coreContext.startForegroundService(
                    Intent(coreContext, ArdaBootstrapServerService::class.java)
                        .setAction(ArdaBootstrapServerService.ACTION_RELOAD_CONFIG),
                )
            }

            else -> return super.call(method, arg, extras)
        }
        return Bundle.EMPTY
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.${ArdaPlatformContract.authority}.snapshot"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Insert is not supported")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Delete is not supported")

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun ContentValues.booleanOrDefault(key: String, default: Boolean): Boolean =
        when (val value = get(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> default
        }

    companion object {
        private const val matchSnapshot = 1
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ArdaPlatformContract.authority, "snapshot", matchSnapshot)
        }
        private val snapshotColumns = arrayOf(
            ArdaPlatformContract.columnHost,
            ArdaPlatformContract.columnPort,
            ArdaPlatformContract.columnToken,
            ArdaPlatformContract.columnKeepBootstrapRfcommOpen,
            ArdaPlatformContract.columnKeepBluetoothProfilesConnected,
            ArdaPlatformContract.columnRuntimeConfigPath,
            ArdaPlatformContract.columnServerRunning,
            ArdaPlatformContract.columnHotspotReady,
            ArdaPlatformContract.columnHotspotSsid,
            ArdaPlatformContract.columnHotspotBssid,
            ArdaPlatformContract.columnHotspotIp,
            ArdaPlatformContract.columnHotspotSecurity,
            ArdaPlatformContract.columnPeerConnected,
            ArdaPlatformContract.columnPeerAddress,
            ArdaPlatformContract.columnBridgeConnected,
            ArdaPlatformContract.columnLastEvent,
            ArdaPlatformContract.columnLastError,
        )
    }
}
