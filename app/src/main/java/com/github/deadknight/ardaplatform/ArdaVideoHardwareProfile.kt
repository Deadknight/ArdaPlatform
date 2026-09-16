package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.Build
import android.provider.Settings

enum class ArdaVideoHardwareProfile(
    val settingValue: String,
) {
    Auto("auto"),
    Pi4Hdmi("pi4_hdmi"),
    Pi5HwcVds("pi5_hwc_vds");

    companion object {
        fun fromSetting(value: String?): ArdaVideoHardwareProfile =
            when (value?.trim()?.lowercase()) {
                Pi4Hdmi.settingValue -> Pi4Hdmi
                Pi5HwcVds.settingValue -> Pi5HwcVds
                else -> Auto
            }
    }
}

enum class ArdaVideoFpsMode(
    val settingValue: String,
    val manualCap: Int? = null,
) {
    Auto("auto"),
    Optimal("optimal"),
    Fps10("10", 10),
    Fps15("15", 15),
    Fps30("30", 30),
    Fps40("40", 40),
    Fps60("60", 60);

    companion object {
        fun fromSetting(value: String?): ArdaVideoFpsMode =
            entries.firstOrNull {
                it.settingValue == value?.trim()?.lowercase()
            } ?: Auto
    }
}

/**
 * UI-side contract consumed by the root AAP autostart script.
 *
 * Hardware profile is independent from the existing HDMI/TXP video-route
 * selector and Stable/Performance SurfaceFlinger capture selector.
 */
// ARDA_C2_ENCODER_TUNING_UI_V1
enum class ArdaVideoIveSpeed(
    val settingValue: String,
    val displayName: String,
) {
    Slowest("slowest", "SLOWEST"),
    Normal("normal", "NORMAL"),
    Fast("fast", "FAST"),
    HighSpeed("high_speed", "HIGH SPEED"),
    Fastest("fastest", "FASTEST");

    companion object {
        fun fromSetting(value: String?): ArdaVideoIveSpeed =
            entries.firstOrNull {
                it.settingValue == value?.trim()?.lowercase()
            } ?: Fastest
    }
}

object ArdaVideoHardwareProfileController {
    const val hardwareProfileSetting = "arda_video_hw_profile"
    const val fpsCapSetting = "arda_video_fps_cap"

    // ARDA_C2_ENCODER_TUNING_UI_V1
    const val gpuI420Setting = "arda_video_gpu_i420"
    const val iveSpeedSetting = "arda_video_ive_speed"
    const val bitrateBpsSetting = "arda_video_bitrate_bps"

    const val bitrateMinBps = 4096
    const val bitrateMaxBps = 12000000

    const val pi4OptimalFpsCap = 30
    const val pi5OptimalFpsCap = 60

    fun getHardwareProfile(context: Context): ArdaVideoHardwareProfile =
        ArdaVideoHardwareProfile.fromSetting(
            Settings.Global.getString(
                context.contentResolver,
                hardwareProfileSetting,
            ),
        )

    fun setHardwareProfile(
        context: Context,
        profile: ArdaVideoHardwareProfile,
    ) {
        check(
            Settings.Global.putString(
                context.contentResolver,
                hardwareProfileSetting,
                profile.settingValue,
            ),
        ) {
            "Could not update the Android Auto hardware video profile."
        }

        ArdaPlatformState.log(
            "ARDA_VIDEO_HW_PROFILE_SELECTED=PASS profile=${profile.settingValue}",
        )
    }

    fun getFpsMode(context: Context): ArdaVideoFpsMode =
        ArdaVideoFpsMode.fromSetting(
            Settings.Global.getString(
                context.contentResolver,
                fpsCapSetting,
            ),
        )

    fun setFpsMode(
        context: Context,
        mode: ArdaVideoFpsMode,
    ) {
        check(
            Settings.Global.putString(
                context.contentResolver,
                fpsCapSetting,
                mode.settingValue,
            ),
        ) {
            "Could not update the Android Auto FPS limit."
        }

        ArdaPlatformState.log(
            "ARDA_VIDEO_FPS_MODE_SELECTED=PASS mode=${mode.settingValue}",
        )
    }

    // ARDA_C2_ENCODER_TUNING_UI_V1
    fun getGpuI420Enabled(context: Context): Boolean =
        Settings.Global.getString(
            context.contentResolver,
            gpuI420Setting,
        )?.trim() == "1"

    fun setGpuI420Enabled(
        context: Context,
        enabled: Boolean,
    ) {
        check(
            Settings.Global.putString(
                context.contentResolver,
                gpuI420Setting,
                if (enabled) "1" else "0",
            ),
        ) {
            "Could not update GPU conversion setting."
        }
        ArdaPlatformState.log(
            "ARDA_VIDEO_GPU_I420_SELECTED=PASS enabled=$enabled",
        )
    }

    fun getIveSpeed(context: Context): ArdaVideoIveSpeed =
        ArdaVideoIveSpeed.fromSetting(
            Settings.Global.getString(
                context.contentResolver,
                iveSpeedSetting,
            ),
        )

    fun setIveSpeed(
        context: Context,
        speed: ArdaVideoIveSpeed,
    ) {
        check(
            Settings.Global.putString(
                context.contentResolver,
                iveSpeedSetting,
                speed.settingValue,
            ),
        ) {
            "Could not update AVC encoder speed."
        }
        ArdaPlatformState.log(
            "ARDA_VIDEO_IVE_SPEED_SELECTED=PASS speed=${speed.settingValue}",
        )
    }

    fun getBitrateBps(context: Context): Int {
        val raw = Settings.Global.getString(
            context.contentResolver,
            bitrateBpsSetting,
        )?.trim().orEmpty()

        val parsed = raw.toIntOrNull() ?: 0
        return if (
            parsed == 0 ||
            parsed in bitrateMinBps..bitrateMaxBps
        ) {
            parsed
        } else {
            0
        }
    }

    fun normalizeBitrateBps(value: Int?): Int =
        when {
            value == null -> 0
            value == 0 -> 0
            value in bitrateMinBps..bitrateMaxBps -> value
            else -> 0
        }

    fun setBitrateBps(
        context: Context,
        bitrateBps: Int,
    ) {
        val normalized = normalizeBitrateBps(bitrateBps)
        check(
            Settings.Global.putString(
                context.contentResolver,
                bitrateBpsSetting,
                normalized.toString(),
            ),
        ) {
            "Could not update AVC bitrate override."
        }
        ArdaPlatformState.log(
            "ARDA_VIDEO_BITRATE_SELECTED=PASS bitrate_bps=$normalized",
        )
    }

    fun resolveHardwareProfile(
        selected: ArdaVideoHardwareProfile,
    ): ArdaVideoHardwareProfile {
        if (selected != ArdaVideoHardwareProfile.Auto) return selected

        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val board = Build.BOARD.orEmpty().lowercase()

        return when {
            hardware.contains("rpi4") || board == "bcm2711" ->
                ArdaVideoHardwareProfile.Pi4Hdmi
            hardware.contains("rpi5") || board == "bcm2712" ->
                ArdaVideoHardwareProfile.Pi5HwcVds
            else ->
                ArdaVideoHardwareProfile.Pi5HwcVds
        }
    }

    fun optimalFpsCap(
        selected: ArdaVideoHardwareProfile,
    ): Int =
        when (resolveHardwareProfile(selected)) {
            ArdaVideoHardwareProfile.Pi4Hdmi -> pi4OptimalFpsCap
            ArdaVideoHardwareProfile.Pi5HwcVds -> pi5OptimalFpsCap
            ArdaVideoHardwareProfile.Auto -> pi5OptimalFpsCap
        }
}
