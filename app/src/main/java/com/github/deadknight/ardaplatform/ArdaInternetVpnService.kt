package com.github.deadknight.ardaplatform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RN-4 per-user VPN.
 *
 * Android routes application traffic into the TUN device. hev-socks5-tunnel
 * translates the packets to SOCKS5 and connects to the phone over the existing
 * ARDA local-only Wi-Fi. The phone SOCKS server performs the real cellular
 * egress.
 */
class ArdaInternetVpnService : VpnService() {
    private val nativeExecutor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var activeEndpoint: String? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        log("ARDA_RN4_AAOS_VPN_SERVICE_CREATE=PASS user=${Process.myUserHandle()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val reason = intent.getStringExtra(EXTRA_STOP_REASON)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "explicit_stop"
                stopTunnel(reason)
                stopSelf()
            }
            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting cellular internet bridge"),
                )
                val phoneIp = intent.getStringExtra(EXTRA_PHONE_IP)?.trim().orEmpty()
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, -1)
                val allowedPackage = intent
                    .getStringExtra(EXTRA_ALLOWED_PACKAGE)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                startTunnel(phoneIp, socksPort, allowedPackage)
            }
            else -> log("ARDA_RN4_AAOS_VPN_UNKNOWN_ACTION=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startTunnel(
        phoneIp: String,
        socksPort: Int,
        allowedPackage: String?,
    ) {
        require(phoneIp.isNotBlank()) { "RN-4 phone IP is empty" }
        require(socksPort in 1..65535) { "RN-4 SOCKS5 port is invalid" }

        val phoneAddress = InetAddress.getByName(phoneIp)
        require(phoneAddress is Inet4Address) {
            "RN-4 PoC currently requires an IPv4 phone endpoint"
        }

        val endpoint = "$phoneIp:$socksPort"

        log("ARDA_RN4_AAOS_HEV_LIBRARY_PREFLIGHT=ENTER endpoint=$endpoint")
        if (!TProxyService.isLibraryLoaded()) {
            val loadError = TProxyService.getLibraryLoadError()
                .ifBlank { "unknown" }
            log("ARDA_RN4_AAOS_HEV_LIBRARY_PREFLIGHT=FAIL error=$loadError")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        log("ARDA_RN4_AAOS_HEV_LIBRARY_PREFLIGHT=PASS")

        val previousEndpoint = activeEndpoint
        val previousNativeRunning = isNativeRunning()

        // OP_PROXY_READY belongs to a newly established phone transport
        // session. Never reuse an HEV instance merely because the endpoint
        // string happens to be identical to the previous session.
        if (previousEndpoint != null || tun != null || previousNativeRunning) {
            log(
                "ARDA_RN4_AAOS_TUNNEL_RESTART_REQUIRED=" +
                    "old=${previousEndpoint ?: "none"} new=$endpoint " +
                    "native_running=$previousNativeRunning",
            )
        }

        stopTunnel("restart")

        if (VpnService.prepare(this) != null) {
            log("ARDA_RN4_AAOS_VPN_PERMISSION_REQUIRED=YES")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("ARDA cellular bridge")
            .setMtu(TUN_MTU)
            .addAddress(TUN_IPV4, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .excludeRoute(IpPrefix(phoneAddress, 32))
            .addDnsServer(DNS_PRIMARY)
            .addDnsServer(DNS_SECONDARY)
            .setBlocking(false)
            .setMetered(false)

        if (allowedPackage != null) {
            builder.addAllowedApplication(allowedPackage)
            log(
                "ARDA_RN4_AAOS_VPN_ALLOWED_PACKAGE=" +
                    "$allowedPackage user=${Process.myUserHandle()}",
            )
        } else {
            log(
                "ARDA_RN4_AAOS_VPN_ALLOWED_PACKAGE=ALL_CURRENT_USER " +
                    "user=${Process.myUserHandle()}",
            )
        }

        val established = builder.establish()
            ?: error("VpnService.Builder.establish() returned null")

        val config = writeHevConfig(phoneIp, socksPort)
        tun = established
        activeEndpoint = endpoint
        stopping.set(false)

        log("ARDA_RN4_AAOS_VPN_ESTABLISH=PASS endpoint=$endpoint fd=${established.fd}")
        log("ARDA_RN4_AAOS_VPN_ROUTE=0.0.0.0/0 exclude=$phoneIp/32")
        log("ARDA_RN4_AAOS_VPN_DNS=$DNS_PRIMARY,$DNS_SECONDARY")
        log(
            "ARDA_RN4_AAOS_HEV_DISPATCH_SUBMIT=" +
                "config=${config.absolutePath} config_exists=${config.isFile} " +
                "config_size=${config.length()} fd=${established.fd}",
        )

        nativeExecutor.execute {
            log(
                "ARDA_RN4_AAOS_HEV_EXECUTOR_ENTER=" +
                    "thread=${Thread.currentThread().name} fd=${established.fd}",
            )
            log("ARDA_RN4_AAOS_HEV_START_CALL=ENTER")
            val result = runCatching {
                TProxyService.TProxyStartService(config.absolutePath, established.fd)
            }.onFailure { error ->
                log(
                    "ARDA_RN4_AAOS_HEV_ERROR=" +
                        "${error.javaClass.name}:${error.safeMessage()}",
                )
                Log.e(TAG, "ARDA_RN4_AAOS_HEV_THROWABLE", error)
            }.getOrDefault(false)

            log("ARDA_RN4_AAOS_HEV_START_CALL_RETURN=$result")

            // TProxyStartService() dispatches the native worker. Its return
            // value is not a tunnel-exit event; TProxyIsRunning() below is
            // the authoritative runtime gate.
            if (!result && !stopping.get()) {
                log("ARDA_RN4_AAOS_HEV_START_DISPATCH=FAIL endpoint=$endpoint")
            } else if (result && !stopping.get()) {
                log("ARDA_RN4_AAOS_HEV_START_DISPATCH=PASS endpoint=$endpoint")
            }
        }

        Thread {
            repeat(50) {
                if (isNativeRunning()) {
                    log(
                        "ARDA_RN4_AAOS_TUNNEL_START=PASS endpoint=$endpoint " +
                            "stats=${nativeStatsText()}",
                    )
                    updateNotification("Cellular internet bridge active")
                    return@Thread
                }
                Thread.sleep(100)
            }
            log("ARDA_RN4_AAOS_TUNNEL_START=FAIL endpoint=$endpoint")
        }.apply {
            name = "arda-rn4-start-gate"
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stopTunnel(reason: String) {
        stopping.set(true)
        val wasRunning = isNativeRunning()
        val hadTunnelState = wasRunning || activeEndpoint != null || tun != null

        if (hadTunnelState) {
            val stopped = runCatching {
                TProxyService.TProxyStopService()
            }.onFailure { error ->
                log(
                    "ARDA_RN4_AAOS_HEV_STOP_ERROR=" +
                        "${error.javaClass.simpleName}:${error.safeMessage()}",
                )
            }.getOrDefault(false)

            var stillRunning = isNativeRunning()
            for (attempt in 0 until 20) {
                if (!stillRunning) break
                Thread.sleep(50)
                stillRunning = isNativeRunning()
            }

            log(
                "ARDA_RN4_AAOS_HEV_STOP=$stopped reason=$reason " +
                    "was_running=$wasRunning still_running=$stillRunning",
            )
        }

        runCatching { tun?.close() }
        tun = null
        activeEndpoint = null

        if (reason != "restart") {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        log("ARDA_RN4_AAOS_VPN_STOP=PASS reason=$reason")
    }

    private fun isNativeRunning(): Boolean =
        runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)

    private fun nativeStatsText(): String =
        runCatching {
            TProxyService.TProxyGetStats().joinToString(
                prefix = "[",
                postfix = "]",
            )
        }.getOrElse { error ->
            "error:${error.javaClass.simpleName}:${error.safeMessage()}"
        }

    private fun writeHevConfig(phoneIp: String, socksPort: Int): File {
        val dir = File(filesDir, "rn4").apply { mkdirs() }
        val file = File(dir, "hev-socks5-tunnel.yml")
        file.writeText(
            """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_IPV4
              icmp: 'reply'
            socks5:
              address: '$phoneIp'
              port: $socksPort
              udp: 'udp'
              pipeline: true
            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: debug
            """.trimIndent() + "\n",
        )
        return file
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ARDA Internet Bridge",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ARDA Internet")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onRevoke() {
        log("ARDA_RN4_AAOS_VPN_REVOKED=YES")
        stopTunnel("revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("service_destroy")
        if (activeInstance === this) {
            activeInstance = null
        }
        nativeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun log(line: String) {
        Log.i(TAG, line)
        ArdaPlatformState.log(line)
    }

    private fun Throwable.safeMessage(): String =
        message?.replace('\n', ' ') ?: "no-message"

    companion object {
        const val ACTION_START =
            "com.github.deadknight.ardaplatform.action.RN4_VPN_START"
        const val ACTION_STOP =
            "com.github.deadknight.ardaplatform.action.RN4_VPN_STOP"
        const val EXTRA_PHONE_IP = "phone_ip"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_ALLOWED_PACKAGE = "allowed_package"
        const val EXTRA_STOP_REASON = "stop_reason"

        @Volatile private var activeInstance: ArdaInternetVpnService? = null

        /**
         * Receiver and VPN service run in the same per-user app process.
         * Stop the live instance directly so HEV is torn down before a
         * reconnect can reuse stale native state.
         */
        fun stopActive(reason: String): Boolean {
            val service = activeInstance ?: return false
            service.stopTunnel(reason)
            service.stopSelf()
            return true
        }

        private const val TAG = "ARDA_RN4_AAOS"
        private const val CHANNEL_ID = "arda_rn4_internet"
        private const val NOTIFICATION_ID = 4104

        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_PREFIX = 30
        private const val TUN_MTU = 1500
        private const val DNS_PRIMARY = "1.1.1.1"
        private const val DNS_SECONDARY = "8.8.8.8"
    }
}
