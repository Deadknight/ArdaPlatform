package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.PowerManager

enum class ArdaVideoRoute(
    val propertyValue: String,
) {
    Hdmi("hdmi"),
    Txp("txp");

    companion object {
        fun fromPropertyValue(value: String?): ArdaVideoRoute =
            if (value?.trim()?.lowercase() == Txp.propertyValue) Txp else Hdmi
    }
}

data class ArdaVideoRouteState(
    val selected: ArdaVideoRoute,
    val active: ArdaVideoRoute?,
) {
    val restartRequired: Boolean
        get() = active == null || active != selected
}

/**
 * Persistent video-route control consumed once when SurfaceFlinger starts.
 *
 * The property is deliberately written through /system/bin/setprop instead of
 * hidden android.os.SystemProperties APIs so this source stays public-SDK safe.
 * SELinux grants the platform-signed ArdaPlatform domain access only to the
 * dedicated ARDA route property.
 */
object ArdaVideoRouteController {
    const val selectedProperty = "persist.sys.arda.video_route"
    const val activeProperty = "sys.arda.video_route.active"

    val defaultRoute: ArdaVideoRoute = ArdaVideoRoute.Hdmi

    fun readState(): ArdaVideoRouteState {
        val selectedRaw = getProperty(selectedProperty)
        val selected = if (selectedRaw.isBlank()) {
            defaultRoute
        } else {
            ArdaVideoRoute.fromPropertyValue(selectedRaw)
        }
        val activeRaw = getProperty(activeProperty)
        val active = activeRaw
            .takeIf(String::isNotBlank)
            ?.let(ArdaVideoRoute::fromPropertyValue)

        return ArdaVideoRouteState(
            selected = selected,
            active = active,
        )
    }

    fun setSelected(route: ArdaVideoRoute) {
        val result = runTool("/system/bin/setprop", selectedProperty, route.propertyValue)
        check(result.exitCode == 0) {
            "Unable to set video route: ${result.output.ifBlank { "setprop exit ${result.exitCode}" }}"
        }

        val verified = getProperty(selectedProperty)
        check(verified == route.propertyValue) {
            "Video route verification failed: expected=${route.propertyValue} actual=${verified.ifBlank { "<empty>" }}"
        }

        ArdaPlatformState.log(
            "ARDA_VIDEO_ROUTE_SELECTED=PASS route=${route.propertyValue} reboot_required=true",
        )
    }

    fun rebootToApply(context: Context) {
        ArdaPlatformState.log("ARDA_VIDEO_ROUTE_REBOOT_REQUEST=route=${readState().selected.propertyValue}")
        val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable"
        }
        powerManager.reboot("arda-video-route")
    }

    private fun getProperty(name: String): String {
        val result = runTool("/system/bin/getprop", name)
        return if (result.exitCode == 0) result.output.trim() else ""
    }

    private data class ToolResult(
        val exitCode: Int,
        val output: String,
    )

    private fun runTool(vararg command: String): ToolResult {
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            ToolResult(
                exitCode = process.waitFor(),
                output = output.trim(),
            )
        }.getOrElse { error ->
            ToolResult(
                exitCode = -1,
                output = error.message ?: error.javaClass.simpleName,
            )
        }
    }
}
