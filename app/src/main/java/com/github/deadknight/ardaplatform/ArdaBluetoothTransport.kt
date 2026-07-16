package com.github.deadknight.ardaplatform

import java.util.UUID

object ArdaBluetoothTransport {
    val serviceUuid: UUID =
        UUID.fromString("e43cd6de-4bb2-49fe-8d43-ecace91038cc")

    const val serviceName = "ARDA Bootstrap"
    const val bridgeMagic = "ARDBR001"
    const val bridgeAck = "OKAY"
    const val defaultBridgeHost = "127.0.0.1"
    const val defaultBridgePort = 5283
    const val defaultDebugToken = "Arda-RN3B-Debug-Only-2026!"
}
