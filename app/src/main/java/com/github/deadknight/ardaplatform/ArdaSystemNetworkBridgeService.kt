package com.github.deadknight.ardaplatform

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ArdaSystemNetworkBridgeService : Service() {
    private val nativeExecutor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)

    @Volatile private var systemMessenger: Messenger? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var activeEndpoint: String? = null
    @Volatile private var pendingPhoneIp: String? = null
    @Volatile private var pendingSocksPort: Int = -1

    private val incomingMessenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MSG_REGISTER_SYSTEM -> {
                    systemMessenger = message.replyTo
                    log("ARDA_RN4_SYSNET_BRIDGE_REGISTER=PASS")
                    flushPendingStart()
                }

                MSG_TUN_READY -> {
                    val data = message.data
                    val pfd = data.getParcelable(KEY_TUN_FD, ParcelFileDescriptor::class.java)
                    val phoneIp = data.getString(KEY_PHONE_IP).orEmpty()
                    val socksPort = data.getInt(KEY_SOCKS_PORT, -1)

                    if (pfd == null) {
                        log("ARDA_RN4_SYSNET_TUN_FD=FAIL reason=null")
                        sendSimple(MSG_HEV_FAILED)
                    } else {
                        startHev(pfd, phoneIp, socksPort)
                    }
                }

                MSG_SYSTEM_STOP -> {
                    val reason = message.data.getString(KEY_REASON) ?: "system_stop"
                    stopHev(reason)
                }

                else -> log("ARDA_RN4_SYSNET_UNKNOWN_MESSAGE=${message.what}")
            }
            true
        },
    )

    override fun onCreate() {
        super.onCreate()
        log("ARDA_RN4_SYSNET_BRIDGE_CREATE=PASS")
    }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val phoneIp = intent.getStringExtra(EXTRA_PHONE_IP)?.trim().orEmpty()
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, -1)

                if (phoneIp.isBlank() || socksPort !in 1..65535) {
                    log(
                        "ARDA_RN4_SYSNET_START_REJECTED=" +
                            "phone_ip=$phoneIp socks_port=$socksPort",
                    )
                    return START_NOT_STICKY
                }

                pendingPhoneIp = phoneIp
                pendingSocksPort = socksPort
                log("ARDA_RN4_SYSNET_START_REQUEST=endpoint=$phoneIp:$socksPort")
                flushPendingStart()
            }

            ACTION_STOP -> {
                val reason = intent.getStringExtra(EXTRA_STOP_REASON)
                    ?.takeIf { it.isNotBlank() }
                    ?: "explicit_stop"
                stopHev(reason)
                sendStop(reason)
            }

            else -> log("ARDA_RN4_SYSNET_UNKNOWN_ACTION=${intent?.action}")
        }

        return START_NOT_STICKY
    }

    @Synchronized
    private fun flushPendingStart() {
        val messenger = systemMessenger ?: run {
            log("ARDA_RN4_SYSNET_WAITING_FOR_SYSTEM_BRIDGE=YES")
            return
        }

        val phoneIp = pendingPhoneIp ?: return
        val socksPort = pendingSocksPort
        if (socksPort !in 1..65535) return

        val data = Bundle().apply {
            putString(KEY_PHONE_IP, phoneIp)
            putInt(KEY_SOCKS_PORT, socksPort)
        }

        try {
            messenger.send(
                Message.obtain(null, MSG_START).apply {
                    setData(data)
                },
            )
            log("ARDA_RN4_SYSNET_START_SENT=endpoint=$phoneIp:$socksPort")
            pendingPhoneIp = null
            pendingSocksPort = -1
        } catch (error: RemoteException) {
            log("ARDA_RN4_SYSNET_START_SEND_FAIL=${error.javaClass.simpleName}")
            systemMessenger = null
        }
    }

    @Synchronized
    private fun startHev(
        newTun: ParcelFileDescriptor,
        phoneIp: String,
        socksPort: Int,
    ) {
        if (phoneIp.isBlank() || socksPort !in 1..65535) {
            runCatching { newTun.close() }
            log("ARDA_RN4_SYSNET_HEV_START_REJECTED=bad_endpoint")
            sendSimple(MSG_HEV_FAILED)
            return
        }

        val endpoint = "$phoneIp:$socksPort"

        if (!TProxyService.isLibraryLoaded()) {
            runCatching { newTun.close() }
            log(
                "ARDA_RN4_SYSNET_HEV_LIBRARY=FAIL error=" +
                    TProxyService.getLibraryLoadError().ifBlank { "unknown" },
            )
            sendSimple(MSG_HEV_FAILED)
            return
        }

        stopHev("restart")

        val config = writeHevConfig(phoneIp, socksPort)
        tun = newTun
        activeEndpoint = endpoint
        stopping.set(false)

        log(
            "ARDA_RN4_SYSNET_TUN_FD=PASS " +
                "endpoint=$endpoint fd=${newTun.fd} config=${config.absolutePath}",
        )

        nativeExecutor.execute {
            log("ARDA_RN4_SYSNET_HEV_START_CALL=ENTER fd=${newTun.fd}")
            val result = runCatching {
                TProxyService.TProxyStartService(config.absolutePath, newTun.fd)
            }.onFailure { error ->
                log(
                    "ARDA_RN4_SYSNET_HEV_ERROR=" +
                        "${error.javaClass.simpleName}:${error.message ?: "no-message"}",
                )
            }.getOrDefault(false)

            log("ARDA_RN4_SYSNET_HEV_START_CALL_RETURN=$result")

            Thread {
                repeat(50) { attempt ->
                    if (stopping.get()) return@Thread
                    if (isNativeRunning()) {
                        log(
                            "ARDA_RN4_SYSNET_HEV_READY=PASS " +
                                "endpoint=$endpoint attempt=$attempt",
                        )
                        sendSimple(MSG_HEV_READY)
                        return@Thread
                    }
                    Thread.sleep(100)
                }

                log("ARDA_RN4_SYSNET_HEV_READY=FAIL endpoint=$endpoint")
                sendSimple(MSG_HEV_FAILED)
            }.apply {
                name = "arda-rn4-sysnet-start-gate"
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    private fun stopHev(reason: String) {
        stopping.set(true)

        if (isNativeRunning() || tun != null || activeEndpoint != null) {
            val stopped = runCatching {
                TProxyService.TProxyStopService()
            }.getOrDefault(false)

            var running = isNativeRunning()
            repeat(20) {
                if (!running) return@repeat
                Thread.sleep(50)
                running = isNativeRunning()
            }

            log(
                "ARDA_RN4_SYSNET_HEV_STOP=$stopped " +
                    "still_running=$running reason=$reason",
            )
        }

        runCatching { tun?.close() }
        tun = null
        activeEndpoint = null
        log("ARDA_RN4_SYSNET_LOCAL_STOP=PASS reason=$reason")
    }

    private fun sendStop(reason: String) {
        val data = Bundle().apply {
            putString(KEY_REASON, reason)
        }
        sendMessage(MSG_STOP, data)
    }

    private fun sendSimple(what: Int) {
        sendMessage(what, Bundle.EMPTY)
    }

    private fun sendMessage(what: Int, data: Bundle) {
        val messenger = systemMessenger ?: return
        try {
            messenger.send(
                Message.obtain(null, what).apply {
                    setData(data)
                },
            )
        } catch (error: RemoteException) {
            log(
                "ARDA_RN4_SYSNET_SEND_FAIL=" +
                    "what=$what error=${error.javaClass.simpleName}",
            )
            systemMessenger = null
        }
    }

    private fun isNativeRunning(): Boolean =
        runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)

    private fun writeHevConfig(phoneIp: String, socksPort: Int): File {
        val dir = File(filesDir, "rn4-system-network").apply { mkdirs() }
        return File(dir, "hev-socks5-tunnel.yml").apply {
            writeText(
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
        }
    }

    override fun onDestroy() {
        stopHev("service_destroy")
        nativeExecutor.shutdownNow()
        systemMessenger = null
        super.onDestroy()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        ArdaPlatformState.log(line)
    }

    companion object {
        const val ACTION_START =
            "com.github.deadknight.ardaplatform.action.RN4_SYSTEM_NET_START"
        const val ACTION_STOP =
            "com.github.deadknight.ardaplatform.action.RN4_SYSTEM_NET_STOP"

        const val EXTRA_PHONE_IP = "phone_ip"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_STOP_REASON = "stop_reason"

        const val MSG_REGISTER_SYSTEM = 0x415201
        const val MSG_START = 0x415202
        const val MSG_STOP = 0x415203
        const val MSG_TUN_READY = 0x415204
        const val MSG_HEV_READY = 0x415205
        const val MSG_HEV_FAILED = 0x415206
        const val MSG_SYSTEM_STOP = 0x415207

        const val KEY_PHONE_IP = "phone_ip"
        const val KEY_SOCKS_PORT = "socks_port"
        const val KEY_TUN_FD = "tun_fd"
        const val KEY_REASON = "reason"

        private const val TAG = "ARDA_RN4_SYSNET"
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_MTU = 1500
    }
}
