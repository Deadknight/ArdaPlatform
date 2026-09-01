package com.github.deadknight.ardaplatform

import android.content.Context
import android.provider.Settings
import java.io.File
import kotlin.math.roundToInt

/**
 * UI-side contract for the root-owned ARDA CPU tuner.
 *
 * The firmware boot ceiling is intentionally higher than the default runtime
 * policy. An absent setting always means stock 1500 MHz.
 */
object ArdaCpuPerformance {
    const val stockMhz = 1500
    const val hardCeilingMhz = 3000
    const val minOffsetMhz = -200
    const val maxOffsetMhz = 1500
    const val stepMhz = 100
    const val defaultOffsetMhz = 0
    const val highOverclockWarningMhz = 2000

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

    fun targetMhz(offsetMhz: Int): Int =
        (stockMhz + normalizeOffset(offsetMhz))
            .coerceIn(stockMhz + minOffsetMhz, hardCeilingMhz)

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
