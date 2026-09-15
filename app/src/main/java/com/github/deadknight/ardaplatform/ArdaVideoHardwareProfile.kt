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
object ArdaVideoHardwareProfileController {
    const val hardwareProfileSetting = "arda_video_hw_profile"
    const val fpsCapSetting = "arda_video_fps_cap"

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
