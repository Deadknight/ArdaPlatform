package com.github.deadknight.ardaplatform

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import org.json.JSONObject
import java.io.Closeable
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed device-owned LocalOnlyHotspot identity advertised through senderd. */
data class ArdaHotspotInfo(
    val ssid: String,
    val passphrase: String?,
    val security: String,
    val bssid: String?,
    val apIp: String?,
) {
    fun toJson(): String = JSONObject()
        .put("available", true)
        .put("ssid", ssid)
        .put("psk", passphrase ?: JSONObject.NULL)
        .put("security", security)
        .put("bssid", bssid ?: JSONObject.NULL)
        .put("ap_ip", apIp ?: JSONObject.NULL)
        .toString()
}

private data class ArdaHotspotIdentity(
    val ssid: String,
    val passphrase: String,
)

/**
 * Final AAOS hotspot owner.
 *
 * This class never falls back to framework-generated AndroidShare/ArdaShare
 * credentials. Failure to create the fixed AP is a visible transport failure.
 */
class ArdaHotspotController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val identity = loadOrCreateIdentity(appContext)

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var currentInfo: ArdaHotspotInfo? = null

    fun startBlocking(timeoutMs: Long): ArdaHotspotInfo? {
        currentInfo?.let {
            ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_REUSE=PASS")
            return it
        }
        require(Build.VERSION.SDK_INT >= 36) {
            "Fixed ARDA LocalOnlyHotspot requires Android 16/API 36"
        }

        val config = buildStableSoftApConfiguration()
        require(wifiManager.validateSoftApConfiguration(config)) {
            "Framework rejected fixed ARDA LocalOnlyHotspot configuration"
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<ArdaHotspotInfo?>()
        val failure = AtomicReference<String?>()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(newReservation: WifiManager.LocalOnlyHotspotReservation) {
                reservation = newReservation
                val info = reservationInfo(newReservation, timeoutMs)
                currentInfo = info
                result.set(info)
                ArdaPlatformState.hotspotReady.value = true
                ArdaPlatformState.hotspotSsid.value = info.ssid
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_CONFIG_MODE=PLATFORM_SINGLE_USER_FIXED")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_STABLE_CONFIG_GATE=PASS")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_IDENTITY=DEVICE_OWNED_STABLE")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_SSID=${info.ssid}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_BSSID=${info.bssid ?: "unreported"}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_AP_IP=${info.apIp ?: "unreported"}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_SECURITY=${info.security}")
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_START=PASS")
                latch.countDown()
            }

            override fun onStopped() {
                reservation = null
                currentInfo = null
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

        runCatching {
            wifiManager.startLocalOnlyHotspotWithConfiguration(
                config,
                callbackExecutor,
                callback,
            )
        }.onFailure { error ->
            failure.set("${error.javaClass.simpleName}:${error.safeMessage()}")
            ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_ERROR=${failure.get()}")
            latch.countDown()
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
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

    private fun buildStableSoftApConfiguration(): SoftApConfiguration {
        val builder = SoftApConfiguration.Builder()
        val builderClass = builder.javaClass

        val ssidConfigured = runCatching {
            val wifiSsid = WifiSsid.fromBytes(identity.ssid.toByteArray(Charsets.UTF_8))
            findBuilderMethod(builderClass, "setWifiSsid", WifiSsid::class.java)
                .invoke(builder, wifiSsid)
        }.recoverCatching {
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

        require(ssidConfigured && passphraseConfigured) {
            "AAOS SoftApConfiguration does not expose fixed SSID/PSK setters"
        }

        val bandConfigured = invokeBuilderBestEffort(
            builder,
            "setBand",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(1),
        )
        val autoShutdownConfigured = invokeBuilderBestEffort(
            builder,
            "setAutoShutdownEnabled",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            arrayOf(false),
        )
        // RANDOMIZATION_NONE = 0 in SoftApConfiguration. A stable SoftAP MAC lets
        // the phone make an exact SSID+BSSID request when the framework reports it.
        val stableMacConfigured = invokeBuilderBestEffort(
            builder,
            "setMacRandomizationSetting",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(0),
        )
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_LOHS_BUILDER=" +
                "band=$bandConfigured;auto_shutdown=$autoShutdownConfigured;" +
                "stable_mac=$stableMacConfigured",
        )
        return builder.build()
    }

    private fun invokeBuilderBestEffort(
        builder: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Boolean = runCatching {
        findBuilderMethod(builder.javaClass, name, *parameterTypes)
            .invoke(builder, *args)
    }.isSuccess

    private fun findBuilderMethod(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): java.lang.reflect.Method = runCatching {
        type.getMethod(name, *parameterTypes)
    }.getOrElse {
        type.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }

    @Suppress("DEPRECATION")
    private fun reservationInfo(
        reservation: WifiManager.LocalOnlyHotspotReservation,
        timeoutMs: Long,
    ): ArdaHotspotInfo {
        if (Build.VERSION.SDK_INT >= 30) {
            val config = reservation.softApConfiguration
            val frameworkBssid = normalizeMac(config.bssid?.toString())
            val interfaceBssid = readSoftApInterfaceBssid()
            val resolvedBssid = frameworkBssid ?: interfaceBssid
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_BSSID_SOURCE=" + when {
                    frameworkBssid != null -> "FRAMEWORK_CONFIG"
                    interfaceBssid != null -> "NETWORK_INTERFACE"
                    else -> "UNAVAILABLE"
                },
            )
            val passphrase = config.passphrase ?: identity.passphrase
            return ArdaHotspotInfo(
                ssid = config.ssid ?: identity.ssid,
                passphrase = passphrase,
                security = if (passphrase.isBlank()) "open" else "wpa2",
                bssid = resolvedBssid,
                apIp = waitForSoftApInterfaceIpv4(timeoutMs),
            )
        }

        val config: WifiConfiguration? = reservation.wifiConfiguration
        val passphrase = config?.preSharedKey?.trim('"') ?: identity.passphrase
        return ArdaHotspotInfo(
            ssid = config?.SSID?.trim('"') ?: identity.ssid,
            passphrase = passphrase,
            security = if (passphrase.isBlank()) "open" else "wpa2",
            bssid = normalizeMac(config?.BSSID),
            apIp = waitForSoftApInterfaceIpv4(timeoutMs),
        )
    }

    private fun waitForSoftApInterfaceIpv4(timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            readSoftApInterfaceIpv4()?.let { return it }
            Thread.sleep(250)
        }
        return readSoftApInterfaceIpv4()
    }

    private fun readSoftApInterfaceIpv4(): String? {
        for (name in interfaceCandidates()) {
            val addresses = runCatching {
                NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
            }.getOrDefault(emptyList())
            val ipv4 = addresses.firstOrNull { address ->
                address is java.net.Inet4Address &&
                    !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            } ?: continue
            val text = ipv4.hostAddress ?: continue
            ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_AP_INTERFACE=$name:$text")
            return text
        }
        return null
    }

    private fun readSoftApInterfaceBssid(): String? {
        for (name in interfaceCandidates()) {
            val address = runCatching {
                NetworkInterface.getByName(name)?.hardwareAddress
            }.getOrNull() ?: continue
            if (address.size != 6) continue
            val mac = address.joinToString(":") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
            normalizeMac(mac)?.let {
                ArdaPlatformState.log("ARDA_RN3B_AAOS_LOHS_BSSID_INTERFACE=$name:$it")
                return it
            }
        }
        return null
    }

    private fun interfaceCandidates(): List<String> {
        val preferred = listOf("ap0", "softap0", "wlan1", "wlan0")
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .map { it.name }
                .filter { it.contains("ap", ignoreCase = true) || it.startsWith("wlan") }
        }.getOrDefault(emptyList())
        return (preferred + all).distinct()
    }

    private fun normalizeMac(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.matches(MAC_PATTERN) }
            ?: return null
        return normalized.takeUnless {
            it == "00:00:00:00:00:00" ||
                it == "02:00:00:00:00:00" ||
                it == "FF:FF:FF:FF:FF:FF"
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
        ArdaPlatformState.hotspotReady.value = false
        ArdaPlatformState.hotspotSsid.value = null
        callbackExecutor.shutdownNow()
    }

    private fun loadOrCreateIdentity(context: Context): ArdaHotspotIdentity {
        val storageContext = context.createDeviceProtectedStorageContext()
        val preferences = storageContext.getSharedPreferences(
            HOTSPOT_PREFS,
            Context.MODE_PRIVATE,
        )

        val savedVersion = preferences.getInt(KEY_CREDENTIAL_VERSION, 0)
        val savedSsid = preferences.getString(KEY_SSID, null)
        val savedPassphrase = preferences.getString(KEY_PASSPHRASE, null)
        if (
            savedVersion == CREDENTIAL_VERSION &&
            isValidGeneratedSsid(savedSsid) &&
            isValidPassphrase(savedPassphrase)
        ) {
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_SOURCE=DEVICE_PROTECTED_STORAGE",
            )
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_VERSION=$savedVersion",
            )
            return ArdaHotspotIdentity(
                ssid = requireNotNull(savedSsid),
                passphrase = requireNotNull(savedPassphrase),
            )
        }

        if (savedSsid != null || savedPassphrase != null || savedVersion != 0) {
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_STATE=INVALID_OR_LEGACY_REGENERATED",
            )
        }

        val random = SecureRandom()
        val identity = ArdaHotspotIdentity(
            ssid = SSID_PREFIX + randomToken(
                random = random,
                alphabet = SSID_ALPHABET,
                length = SSID_SUFFIX_LENGTH,
            ),
            passphrase = randomToken(
                random = random,
                alphabet = PASSPHRASE_ALPHABET,
                length = PASSPHRASE_LENGTH,
            ),
        )
        persistIdentity(preferences, identity)
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_SOURCE=FIRST_BOOT_DEVICE_GENERATED",
        )
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_VERSION=$CREDENTIAL_VERSION",
        )
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_LOHS_CREDENTIAL_STABILITY=UNTIL_DATA_RESET",
        )
        return identity
    }

    private fun persistIdentity(
        preferences: android.content.SharedPreferences,
        identity: ArdaHotspotIdentity,
    ) {
        check(
            preferences.edit()
                .putInt(KEY_CREDENTIAL_VERSION, CREDENTIAL_VERSION)
                .putString(KEY_SSID, identity.ssid)
                .putString(KEY_PASSPHRASE, identity.passphrase)
                .commit(),
        ) { "Unable to persist stable ARDA hotspot identity" }
    }

    private fun isValidGeneratedSsid(value: String?): Boolean =
        value != null && GENERATED_SSID_PATTERN.matches(value)

    private fun isValidPassphrase(value: String?): Boolean =
        value != null && value.length == PASSPHRASE_LENGTH &&
            value.all { it in PASSPHRASE_ALPHABET }

    private fun randomToken(
        random: SecureRandom,
        alphabet: String,
        length: Int,
    ): String = buildString(length) {
        repeat(length) {
            append(alphabet[random.nextInt(alphabet.length)])
        }
    }

    private fun Throwable.safeMessage(): String =
        message?.replace('\n', ' ') ?: "no-message"

    companion object {
        private const val HOTSPOT_PREFS = "arda_rn3b_hotspot_identity_v3"
        private const val KEY_CREDENTIAL_VERSION = "credential_version"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val CREDENTIAL_VERSION = 1
        private const val SSID_PREFIX = "ARDA-"
        private const val SSID_SUFFIX_LENGTH = 8
        private const val PASSPHRASE_LENGTH = 24
        private const val SSID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val PASSPHRASE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val GENERATED_SSID_PATTERN = Regex(
            "^ARDA-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$",
        )
        private val MAC_PATTERN = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    }
}
