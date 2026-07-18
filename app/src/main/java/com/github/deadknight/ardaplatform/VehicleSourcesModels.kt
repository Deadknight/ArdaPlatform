package com.github.deadknight.ardaplatform

data class VehicleSourceSnapshot(
    val userId: Int,
    val packageName: String,
    val componentName: String,
    val adapterId: String,
    val displayName: String,
    val uid: Int,
    val apiVersion: Int,
    val signerSummary: String,
    val requestedPriority: Int,
    val effectivePriority: Int,
    val sourceId: Int,
    val requestedPublishCapabilities: IntArray,
    val requestedCommandCapabilities: IntArray,
    val requestedPublishPropertyIds: IntArray,
    val requestedCommandPropertyIds: IntArray,
    val grantedPublishCapabilities: IntArray,
    val grantedCommandCapabilities: IntArray,
    val grantedPublishPropertyIds: IntArray,
    val grantedCommandPropertyIds: IntArray,
    val state: String,
    val enabled: Boolean,
    val connected: Boolean,
    val ready: Boolean,
    val routerRegistered: Boolean,
    val pendingSignals: Int,
    val additionalApproval: Boolean,
    val lastError: String,
)

data class VehicleSourcesState(
    val sources: List<VehicleSourceSnapshot> = emptyList(),
    val error: String? = null,
)
