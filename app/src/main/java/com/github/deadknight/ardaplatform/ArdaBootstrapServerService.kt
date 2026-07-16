package com.github.deadknight.ardaplatform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-framework transport owner for RN-3B.
 *
 * The service owns Bluetooth RFCOMM and the AAOS LocalOnlyHotspot reservation.
 * senderd still owns every phone-facing protocol decision and applies the proxy.
 */
class ArdaBootstrapServerService : Service() {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val pumpExecutor = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var bluetoothPeer: BluetoothSocket? = null
    @Volatile private var senderdBridge: Socket? = null
    @Volatile private var hotspotInfo: ArdaHotspotInfo? = null

    private lateinit var hotspotController: ArdaHotspotController

    override fun onCreate() {
        super.onCreate()
        hotspotController = ArdaHotspotController(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) {
            ArdaPlatformState.log("ARDA_RN3B_AAOS_SERVER_ALREADY_RUNNING=YES")
            return
        }

        startForeground(notificationId, buildNotification("Starting ARDA local link"))
        ArdaPlatformState.serverRunning.value = true
        ArdaPlatformState.log("ARDA_RN3B_AAOS_ROLE=RFCOMM_AND_LOCAL_ONLY_AP")
        ArdaPlatformState.log("ARDA_RN3B_AAOS_UUID=${ArdaBluetoothTransport.serviceUuid}")

        acceptExecutor.execute {
            try {
                hotspotInfo = hotspotController.startBlocking(hotspotStartTimeoutMs)
                if (hotspotInfo == null) {
                    ArdaPlatformState.log("ARDA_RN3B_AAOS_WIFI_BOOTSTRAP=FAIL")
                    updateNotification("Bluetooth ready; local Wi-Fi unavailable")
                } else {
                    ArdaPlatformState.log("ARDA_RN3B_AAOS_WIFI_BOOTSTRAP=PASS")
                    updateNotification("Waiting for ARDA companion")
                }

                val adapter = bluetoothAdapter() ?: error("Bluetooth adapter unavailable")
                require(adapter.isEnabled) { "Bluetooth adapter disabled" }
                require(hasBluetoothConnectPermission()) {
                    "BLUETOOTH_CONNECT permission is not granted"
                }

                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    ArdaBluetoothTransport.serviceName,
                    ArdaBluetoothTransport.serviceUuid,
                )
                ArdaPlatformState.log("ARDA_RN3B_AAOS_RFCOMM_LISTEN=PASS")

                while (running.get()) {
                    val peer = serverSocket?.accept() ?: break
                    bluetoothPeer = peer
                    ArdaPlatformState.peerConnected.value = true
                    ArdaPlatformState.log(
                        "ARDA_RN3B_AAOS_PEER_CONNECTED=${safePeerAddress(peer)}",
                    )
                    updateNotification("ARDA companion connected")

                    try {
                        bridgePeerToSenderd(peer)
                    } catch (error: Throwable) {
                        ArdaPlatformState.log(
                            "ARDA_RN3B_AAOS_BRIDGE_ERROR=" +
                                "${error.javaClass.simpleName}:${error.safeMessage()}",
                        )
                    } finally {
                        closeQuietly(senderdBridge)
                        closeQuietly(peer)
                        senderdBridge = null
                        bluetoothPeer = null
                        ArdaPlatformState.peerConnected.value = false
                        updateNotification("Waiting for ARDA companion")
                        ArdaPlatformState.log("ARDA_RN3B_AAOS_PEER_DISCONNECTED=YES")
                    }
                }
            } catch (error: Throwable) {
                ArdaPlatformState.log(
                    "ARDA_RN3B_AAOS_SERVER_ERROR=" +
                        "${error.javaClass.simpleName}:${error.safeMessage()}",
                )
            } finally {
                closeQuietly(serverSocket)
                serverSocket = null
                running.set(false)
                ArdaPlatformState.serverRunning.value = false
            }
        }
    }

    private fun bridgePeerToSenderd(peer: BluetoothSocket) {
        val config = ArdaBridgeConfigStore.load(this)
        require(config.host == "127.0.0.1") {
            "RN-3B debug bridge must remain loopback-only"
        }
        require(config.port in 1..65535) { "Invalid senderd bridge port" }
        require(config.token.isNotBlank()) { "Bridge token is empty" }

        val bridge = Socket()
        senderdBridge = bridge
        bridge.tcpNoDelay = true
        bridge.connect(InetSocketAddress(config.host, config.port), bridgeConnectTimeoutMs)
        ArdaPlatformState.log(
            "ARDA_RN3B_AAOS_SENDERD_BRIDGE_CONNECT=${config.host}:${config.port}",
        )
        ArdaPlatformState.log("ARDA_RN3B_AAOS_SENDERD_BRIDGE_CONNECT_GATE=PASS")

        authenticateBridge(bridge, config.token)
        ArdaPlatformState.log("ARDA_RN3B_AAOS_SENDERD_BRIDGE_AUTH=PASS")

        registerHotspotWithSenderd(bridge, hotspotInfo)
        ArdaPlatformState.log("ARDA_RN3B_AAOS_BYTE_PUMP=STARTED")

        val completion = ExecutorCompletionService<Unit>(pumpExecutor)
        completion.submit {
            copyUntilClosed(
                input = peer.inputStream,
                output = bridge.getOutputStream(),
                direction = "PHONE_TO_SENDERD",
            )
        }
        completion.submit {
            copyUntilClosed(
                input = bridge.getInputStream(),
                output = peer.outputStream,
                direction = "SENDERD_TO_PHONE",
            )
        }

        try {
            completion.take().get()
        } finally {
            closeQuietly(bridge)
            closeQuietly(peer)
        }
    }

    private fun registerHotspotWithSenderd(
        bridge: Socket,
        info: ArdaHotspotInfo?,
    ) {
        val payload = info?.toJson() ?: JSONObject()
            .put("available", false)
            .put("error", "local_only_hotspot_unavailable")
            .toString()

        ArdaBluetoothProtocol.writeFrame(
            bridge.getOutputStream(),
            ArdaBluetoothProtocol.OP_PLATFORM_WIFI_INFO,
            platformWifiRequestId,
            payload,
        )
        val ack = ArdaBluetoothProtocol.readFrame(bridge.getInputStream())
        require(
            ack.operation == ArdaBluetoothProtocol.OP_PLATFORM_WIFI_ACK &&
                ack.requestId == platformWifiRequestId,
        ) {
            "Expected PLATFORM_WIFI_ACK, got " +
                "${ArdaBluetoothProtocol.operationName(ack.operation)}/${ack.requestId}"
        }
        ArdaPlatformState.log("ARDA_RN3B_AAOS_PLATFORM_WIFI_REGISTER=PASS")
    }

    private fun authenticateBridge(socket: Socket, token: String) {
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        require(tokenBytes.size <= maxBridgeTokenBytes) {
            "Bridge token exceeds $maxBridgeTokenBytes bytes"
        }

        val output = socket.getOutputStream()
        output.write(
            ArdaBluetoothTransport.bridgeMagic.toByteArray(StandardCharsets.US_ASCII),
        )
        output.write(tokenBytes.size and 0xff)
        output.write((tokenBytes.size ushr 8) and 0xff)
        output.write(tokenBytes)
        output.flush()

        val ack = ByteArray(4)
        socket.getInputStream().readFully(ack)
        val ackText = ack.toString(StandardCharsets.US_ASCII)
        require(ackText == ArdaBluetoothTransport.bridgeAck) {
            "senderd rejected local bridge authentication: $ackText"
        }
    }

    private fun copyUntilClosed(
        input: InputStream,
        output: OutputStream,
        direction: String,
    ) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                total += read
            }
        } finally {
            ArdaPlatformState.log(
                "ARDA_RN3B_AAOS_BYTE_PUMP_END=$direction bytes=$total",
            )
        }
    }

    private fun stopServer() {
        running.set(false)
        closeQuietly(senderdBridge)
        closeQuietly(bluetoothPeer)
        closeQuietly(serverSocket)
        hotspotController.close()
        hotspotInfo = null
        senderdBridge = null
        bluetoothPeer = null
        serverSocket = null
        ArdaPlatformState.peerConnected.value = false
        ArdaPlatformState.serverRunning.value = false
        ArdaPlatformState.log("ARDA_RN3B_AAOS_SERVER_STOPPED=YES")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasBluetoothConnectPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun safePeerAddress(socket: BluetoothSocket): String =
        runCatching { socket.remoteDevice.address }.getOrDefault("unknown")

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "ARDA Bluetooth and local Wi-Fi",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ARDA local transport")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(notificationId, buildNotification(text))
    }

    override fun onDestroy() {
        running.set(false)
        closeQuietly(senderdBridge)
        closeQuietly(bluetoothPeer)
        closeQuietly(serverSocket)
        hotspotController.close()
        acceptExecutor.shutdownNow()
        pumpExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Throwable.safeMessage(): String =
        message?.replace('\n', ' ') ?: "no-message"

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            if (count < 0) error("senderd closed before bridge acknowledgement")
            offset += count
        }
    }

    companion object {
        const val ACTION_START =
            "com.github.deadknight.ardaplatform.action.START_BOOTSTRAP"
        const val ACTION_STOP =
            "com.github.deadknight.ardaplatform.action.STOP_BOOTSTRAP"

        private const val channelId = "arda_bt_bootstrap"
        private const val notificationId = 3103
        private const val bridgeConnectTimeoutMs = 5_000
        private const val hotspotStartTimeoutMs = 20_000L
        private const val maxBridgeTokenBytes = 512
        private const val platformWifiRequestId = 0x4152444157494649L
    }
}
