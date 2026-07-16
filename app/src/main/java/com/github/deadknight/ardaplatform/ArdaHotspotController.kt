package com.github.deadknight.ardaplatform

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.Closeable
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Identity advertised over Bluetooth and used by the phone's exact
 * WifiNetworkSpecifier request. SSID/PSK are stable across normal app updates;
 * BSSID is the actual value returned by the framework for the running LOHS.
 */
data class ArdaHotspotInfo(
    val ssid: String,
    val passphrase: String?,
    val security: String,
    val bssid: String?,
) {
    fun toJson(): String = JSONObject()
        .put("available", true)
        .put("ssid", ssid)
        .put("psk", passphrase ?: JSONObject.NULL)
        .put("security", security)
        .put("bssid", bssid ?: JSONObject.NULL)
        .toString()
}

private data class ArdaHotspotIdentity(
    val ssid: String,
    val passphrase: String,
)

class ArdaHotspotController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val identity = loadOrCreateIdentity(appContext)

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    private var currentInfo: ArdaHotspotInfo? = null

    @Volatile
    private var stableConfigActive: Boolean = false

    fun startBlocking(timeoutMs: Long): ArdaHotspotInfo? {
        currentInfo?.let {
            ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_REUSE=PASS")
            return it
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<ArdaHotspotInfo?>()
        val failure = AtomicReference<String?>()

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(newReservation: WifiManager.LocalOnlyHotspotReservation) {
                reservation = newReservation
                val info = reservationInfo(newReservation)
                currentInfo = info
                result.set(info)
                ArdaPlatformState.hotspotReady.value = true
                ArdaPlatformState.hotspotSsid.value = info.ssid
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_START=PASS")
                ArdaPlatformState.log(
                    "ARDA_RN3B_AAOS_LOHS_IDENTITY=" +
                        if (stableConfigActive) "STABLE" else "FRAMEWORK_RANDOM",
                )
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_SSID=${info.ssid}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_BSSID=${info.bssid ?: "framework_unreported"}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_SECURITY=${info.security}")
                latch.countDown()
            }

            override fun onStopped() {
                reservation = null
                currentInfo = null
                stableConfigActive = false
                ArdaPlatformState.hotspotReady.value = false
                ArdaPlatformState.hotspotSsid.value = null
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_STOPPED=YES")
            }

            override fun onFailed(reason: Int) {
                failure.set(reasonName(reason))
                ArdaPlatformState.hotspotReady.value = false
                ArdaPlatformState.log(
                    "ARDA_RN3B_AAOS_LOHS_START=FAIL reason=${reasonName(reason)}",
                )
                latch.countDown()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 36) {
                val config = buildStableSoftApConfiguration()
                if (config != null) {
                    stableConfigActive = true
                    require(wifiManager.validateSoftApConfiguration(config)) {
                        "Framework rejected stable ARDA LocalOnlyHotspot configuration"
                    }
                    wifiManager.startLocalOnlyHotspotWithConfiguration(
                        config,
                        appContext.mainExecutor,
                        callback,
                    )
                    ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_CONFIG_MODE=STABLE_CUSTOM")
                    ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_STABLE_CONFIG_GATE=PASS")
                } else {
                    stableConfigActive = false
                    // Android 16 API 36 exposes custom LOHS, while the public
                    // SSID/passphrase Builder methods are part of the 36.1 SDK.
                    // Keep compileSdk 36 compatibility and fail visibly to the
                    // framework-generated identity when those runtime methods
                    // are not present on this particular AAOS image.
                    wifiManager.startLocalOnlyHotspot(callback, callbackHandler)
                    ArdaPlatformState.log(
                        "ARDA_RN3B_AAOS_LOHS_CONFIG_MODE=FRAMEWORK_RANDOM_FALLBACK",
                    )
                    ArdaPlatformState.log(
                        "ARDA_RN3B_AAOS_LOHS_STABLE_CONFIG_GATE=UNAVAILABLE_ON_RUNTIME",
                    )
                }
            } else {
                stableConfigActive = false
                wifiManager.startLocalOnlyHotspot(callback, callbackHandler)
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_CONFIG_MODE=FRAMEWORK_RANDOM_FALLBACK")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_STABLE_CONFIG_GATE=SDK_TOO_OLD")
            }
        } catch (error: Throwable) {
            failure.set("${error.javaClass.simpleName}:${error.message ?: "no-message"}")
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_ERROR=${failure.get()}",
            )
            return null
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            failure.set("timeout")
            ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_START=FAIL reason=timeout")
            close()
            return null
        }

        if (result.get() == null) {
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_UNAVAILABLE=${failure.get() ?: "unknown"}",
            )
        }
        return result.get()
    }


    private fun buildStableSoftApConfiguration(): SoftApConfiguration? {
        val builder = SoftApConfiguration.Builder()
        val builderClass = builder.javaClass

        val ssidConfigured = runCatching {
            val wifiSsid = WifiSsid.fromBytes(identity.ssid.toByteArray(Charsets.UTF_8))
            findBuilderMethod(builderClass, "setWifiSsid", WifiSsid::class.java)
                .invoke(builder, wifiSsid)
        }.recoverCatching {
            // Some Android 16 platform snapshots carried a String setter before
            // the public 36.1 WifiSsid API shape was finalized.
            findBuilderMethod(builderClass, "setSsid", String::class.java)
                .invoke(builder, identity.ssid)
        }.isSuccess

        val passphraseConfigured = runCatching {
            findBuilderMethod(
                builderClass,
                "setPassphrase",
                String::class.java,
                Int::class.javaPrimitiveType!!,
            ).invoke(
                builder,
                identity.passphrase,
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK,
            )
        }.isSuccess

        if (!ssidConfigured || !passphraseConfigured) {
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_CUSTOM_BUILDER=" +
                    "ssid=$ssidConfigured;passphrase=$passphraseConfigured",
            )
            return null
        }
        return builder.build()
    }

    private fun findBuilderMethod(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): java.lang.reflect.Method {
        return runCatching { type.getMethod(name, *parameterTypes) }
            .getOrElse {
                type.getDeclaredMethod(name, *parameterTypes).apply {
                    isAccessible = true
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun reservationInfo(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): ArdaHotspotInfo {
        return if (Build.VERSION.SDK_INT >= 30) {
            val config = reservation.softApConfiguration
            val passphrase = config.passphrase
            ArdaHotspotInfo(
                ssid = config.ssid ?: error("LocalOnlyHotspot returned an empty SSID"),
                passphrase = passphrase,
                security = if (passphrase.isNullOrBlank()) "open" else "wpa2",
                bssid = config.bssid?.toString()?.uppercase(Locale.US),
            )
        } else {
            val config: WifiConfiguration? = reservation.wifiConfiguration
            val passphrase = config?.preSharedKey?.trim('"')
            ArdaHotspotInfo(
                ssid = config?.SSID?.trim('"')
                    ?: error("LocalOnlyHotspot returned an empty SSID"),
                passphrase = passphrase,
                security = if (passphrase.isNullOrBlank()) "open" else "wpa2",
                bssid = config?.BSSID?.uppercase(Locale.US),
            )
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "NO_CHANNEL"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "GENERIC"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "INCOMPATIBLE_MODE"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "TETHERING_DISALLOWED"
        else -> "UNKNOWN_$reason"
    }

    override fun close() {
        runCatching { reservation?.close() }
        reservation = null
        currentInfo = null
        stableConfigActive = false
        ArdaPlatformState.hotspotReady.value = false
        ArdaPlatformState.hotspotSsid.value = null
    }

    private fun loadOrCreateIdentity(context: Context): ArdaHotspotIdentity {
        val storageContext = context.createDeviceProtectedStorageContext()
        val preferences = storageContext.getSharedPreferences(
            HOTSPOT_PREFS,
            Context.MODE_PRIVATE,
        )
        val savedSsid = preferences.getString(KEY_SSID, null)
        val savedPassphrase = preferences.getString(KEY_PASSPHRASE, null)
        if (!savedSsid.isNullOrBlank() && !savedPassphrase.isNullOrBlank()) {
            return ArdaHotspotIdentity(savedSsid, savedPassphrase)
        }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        val suffix = androidId
            .filter { it.isLetterOrDigit() }
            .takeLast(4)
            .uppercase(Locale.US)
            .ifBlank { "AAOS" }
        val newIdentity = ArdaHotspotIdentity(
            ssid = "ARDA-RN3B-$suffix",
            passphrase = randomAsciiPassphrase(),
        )
        check(
            preferences.edit()
                .putString(KEY_SSID, newIdentity.ssid)
                .putString(KEY_PASSPHRASE, newIdentity.passphrase)
                .commit(),
        ) { "Unable to persist stable ARDA hotspot identity" }
        return newIdentity
    }

    private fun randomAsciiPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString(PASSPHRASE_LENGTH) {
            repeat(PASSPHRASE_LENGTH) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }

    companion object {
        private const val HOTSPOT_PREFS = "arda_rn3b_hotspot_identity"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val PASSPHRASE_LENGTH = 24
    }
}
