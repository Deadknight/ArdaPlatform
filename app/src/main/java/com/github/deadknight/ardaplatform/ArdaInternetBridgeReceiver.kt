package com.github.deadknight.ardaplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import org.json.JSONObject

/**
 * Cross-user handoff for RN-4.
 *
 * The Bluetooth bootstrap service is singleUser/system-user, while VPN routing
 * is per Android user. The system-user service broadcasts the phone SOCKS
 * endpoint to every user. Only the foreground Driver user starts the VPN.
 */
class ArdaInternetBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> handleStart(context, intent)
            ACTION_STOP -> handleStop(context, intent)
        }
    }

    private fun handleStart(context: Context, intent: Intent) {
        val userManager = context.getSystemService(UserManager::class.java)
        val foreground = userManager?.isUserForeground == true
        val userId = Process.myUserHandle().toString()
        log("ARDA_RN4_AAOS_BRIDGE_RECEIVER=user=$userId;foreground=$foreground")

        if (!foreground) {
            log("ARDA_RN4_AAOS_VPN_SKIP_BACKGROUND_USER=$userId")
            return
        }

        val phoneIp = intent.getStringExtra(EXTRA_PHONE_IP)?.trim().orEmpty()
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, -1)
        if (phoneIp.isBlank() || socksPort !in 1..65535) {
            log("ARDA_RN4_AAOS_VPN_ENDPOINT_INVALID=$phoneIp:$socksPort")
            return
        }

        val consent = VpnService.prepare(context)
        if (consent != null) {
            // First RN-4 PoC uses adb appops pre-consent. We intentionally do
            // not launch UI from this background receiver.
            log("ARDA_RN4_AAOS_VPN_PERMISSION_REQUIRED=YES user=$userId")
            return
        }

        val service = Intent(context, ArdaInternetVpnService::class.java)
            .setAction(ArdaInternetVpnService.ACTION_START)
            .putExtra(ArdaInternetVpnService.EXTRA_PHONE_IP, phoneIp)
            .putExtra(ArdaInternetVpnService.EXTRA_SOCKS_PORT, socksPort)

        context.startForegroundService(service)
        log("ARDA_RN4_AAOS_VPN_START_DISPATCH=PASS user=$userId endpoint=$phoneIp:$socksPort")
    }

    private fun handleStop(context: Context, intent: Intent) {
        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "bridge_stop"

        // Prefer an explicit same-process stop so native HEV teardown happens
        // synchronously before the next OP_PROXY_READY can arrive.
        val directStop = ArdaInternetVpnService.stopActive(reason)
        val stopped = if (directStop) {
            true
        } else {
            context.stopService(
                Intent(context, ArdaInternetVpnService::class.java),
            )
        }

        log(
            "ARDA_RN4_AAOS_VPN_STOP_DISPATCH=$stopped " +
                "direct=$directStop user=${Process.myUserHandle()} reason=$reason",
        )
    }

    companion object {
        const val ACTION_START =
            "com.github.deadknight.ardaplatform.action.RN4_INTERNET_START"
        const val ACTION_STOP =
            "com.github.deadknight.ardaplatform.action.RN4_INTERNET_STOP"

        const val EXTRA_PHONE_IP = "phone_ip"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_REASON = "reason"

        /**
         * RN-4 v27 default data plane.
         *
         * The phone endpoint is still discovered dynamically from OP_PROXY_READY,
         * but the AAOS side now presents it through the system-owned arda0
         * NetworkAgent instead of a per-user VpnService.
         *
         * ArdaBootstrapServerService is singleUser/system-user, so starting the
         * singleUser bridge directly keeps the lifecycle in user 0 and avoids
         * the old cross-user VPN handoff.
         */
        fun dispatchStart(context: Context, payloadJson: String) {
            val json = JSONObject(payloadJson)
            val phoneIp = json.optString("phone_ip").trim()
            val socksPort = json.optInt("socks_port", -1)
            if (phoneIp.isBlank() || socksPort !in 1..65535) {
                log("ARDA_RN4_AAOS_PROXY_FRAME_NO_SOCKS=YES")
                return
            }

            val intent = Intent(context, ArdaSystemNetworkBridgeService::class.java)
                .setAction(ArdaSystemNetworkBridgeService.ACTION_START)
                .putExtra(ArdaSystemNetworkBridgeService.EXTRA_PHONE_IP, phoneIp)
                .putExtra(ArdaSystemNetworkBridgeService.EXTRA_SOCKS_PORT, socksPort)

            val component = context.startService(intent)
            log(
                "ARDA_RN4_SYSNET_LIFECYCLE_START_DISPATCH=PASS " +
                    "endpoint=$phoneIp:$socksPort component=" +
                    (component?.flattenToShortString() ?: "null"),
            )
        }

        fun dispatchStop(context: Context, reason: String) {
            val intent = Intent(context, ArdaSystemNetworkBridgeService::class.java)
                .setAction(ArdaSystemNetworkBridgeService.ACTION_STOP)
                .putExtra(ArdaSystemNetworkBridgeService.EXTRA_STOP_REASON, reason)

            val component = context.startService(intent)
            log(
                "ARDA_RN4_SYSNET_LIFECYCLE_STOP_DISPATCH=PASS " +
                    "reason=$reason component=" +
                    (component?.flattenToShortString() ?: "null"),
            )
        }

        /**
         * v27 rollback/fallback entry points.
         *
         * Keep the proven per-user VpnService path available without making it
         * the normal RN-4 lifecycle.
         */
        fun dispatchLegacyStart(context: Context, payloadJson: String) {
            val json = JSONObject(payloadJson)
            val phoneIp = json.optString("phone_ip").trim()
            val socksPort = json.optInt("socks_port", -1)
            if (phoneIp.isBlank() || socksPort !in 1..65535) {
                log("ARDA_RN4_AAOS_PROXY_FRAME_NO_SOCKS=YES")
                return
            }

            val intent = Intent(context, ArdaInternetBridgeReceiver::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PHONE_IP, phoneIp)
                .putExtra(EXTRA_SOCKS_PORT, socksPort)

            context.sendBroadcastAsUser(
                intent,
                allUsersHandle(),
                context.packageName + ".permission.MANAGE_ARDA_PLATFORM",
            )
            log(
                "ARDA_RN4_AAOS_LEGACY_VPN_START_BROADCAST=PASS " +
                    "endpoint=$phoneIp:$socksPort",
            )
        }

        fun dispatchLegacyStop(context: Context, reason: String) {
            val intent = Intent(context, ArdaInternetBridgeReceiver::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REASON, reason)

            context.sendBroadcastAsUser(
                intent,
                allUsersHandle(),
                context.packageName + ".permission.MANAGE_ARDA_PLATFORM",
            )
            log("ARDA_RN4_AAOS_LEGACY_VPN_STOP_BROADCAST=PASS reason=$reason")
        }

        /**
         * Public-SDK construction of Android's USER_ALL handle.
         *
         * UserHandle.ALL is hidden from the public SDK. UserHandle(Parcel) is
         * public; USER_ALL is Android's stable all-users sentinel (-1).
         */
        private fun allUsersHandle(): UserHandle {
            val parcel = Parcel.obtain()
            return try {
                parcel.writeInt(USER_ALL)
                parcel.setDataPosition(0)
                UserHandle(parcel)
            } finally {
                parcel.recycle()
            }
        }

        private fun log(line: String) {
            Log.i(TAG, line)
            ArdaPlatformState.log(line)
        }

        private const val USER_ALL = -1
        private const val TAG = "ARDA_RN4_AAOS"
    }
}
