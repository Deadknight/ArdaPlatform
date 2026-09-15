package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlin.math.roundToInt

/**
 * UI-side contract for the root-owned ARDA CPU tuner.
 *
 * Offset 0 means this board's ARDA baseline, not a fixed global MHz value.
 * Raspberry Pi 4 uses the project's 1900 MHz baseline; Raspberry Pi 5 uses
 * the 2400 MHz platform baseline.
 *
 * Requests outside the currently exposed cpufreq OPP range may be stored by
 * the UI but require boot/firmware support before they can become active.
 */
object ArdaCpuPerformance {
    const val rpi4BaselineMhz = 1900
    const val rpi5BaselineMhz = 2400

    const val minOffsetMhz = -1000
    const val maxOffsetMhz = 1000
    const val stepMhz = 100
    const val defaultOffsetMhz = 0

    const val highOverclockWarningOffsetMhz = 500

    private const val requestedOffsetSetting = "arda_cpu_boost_mhz"

    private val policyRoot = File("/sys/devices/system/cpu/cpufreq/policy0")
    private val thermalPath = File("/sys/class/thermal/thermal_zone0/temp")

    data class RuntimeState(
        val currentMhz: Int?,
        val policyMaxMhz: Int?,
        val cpuInfoMaxMhz: Int?,
        val temperatureC: Float?,
    )

    fun getRequestedOffsetMhz(context: Context): Int =
        normalizeOffset(
            Settings.Global.getInt(
                context.contentResolver,
                requestedOffsetSetting,
                defaultOffsetMhz,
            ),
        )

    fun setRequestedOffsetMhz(context: Context, offsetMhz: Int) {
        val normalized = normalizeOffset(offsetMhz)
        check(
            Settings.Global.putInt(
                context.contentResolver,
                requestedOffsetSetting,
                normalized,
            ),
        ) {
            "Could not update the CPU performance setting."
        }
    }

    fun baselineMhz(): Int {
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val board = Build.BOARD.orEmpty().lowercase()

        return when {
            hardware.contains("rpi5") || board == "bcm2712" -> rpi5BaselineMhz
            hardware.contains("rpi4") || board == "bcm2711" -> rpi4BaselineMhz
            else -> readKhz("cpuinfo_max_freq") ?: rpi5BaselineMhz
        }
    }

    fun targetMhz(offsetMhz: Int): Int =
        baselineMhz() + normalizeOffset(offsetMhz)

    fun isHighOverclockOffset(offsetMhz: Int): Boolean =
        normalizeOffset(offsetMhz) >= highOverclockWarningOffsetMhz

    fun readRuntimeState(): RuntimeState =
        RuntimeState(
            currentMhz = readKhz("scaling_cur_freq"),
            policyMaxMhz = readKhz("scaling_max_freq"),
            cpuInfoMaxMhz = readKhz("cpuinfo_max_freq"),
            temperatureC = runCatching {
                thermalPath.readText().trim().toInt() / 1000f
            }.getOrNull(),
        )

    private fun readKhz(name: String): Int? =
        runCatching {
            policyRoot.resolve(name).readText().trim().toInt() / 1000
        }.getOrNull()

    private fun normalizeOffset(value: Int): Int {
        val clamped = value.coerceIn(minOffsetMhz, maxOffsetMhz)
        return ((clamped.toFloat() / stepMhz).roundToInt() * stepMhz)
            .coerceIn(minOffsetMhz, maxOffsetMhz)
    }
}
