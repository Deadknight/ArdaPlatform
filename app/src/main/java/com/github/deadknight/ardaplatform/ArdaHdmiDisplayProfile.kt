package com.github.deadknight.ardaplatform

import android.content.Context

/** Saved user preferences only. This class NEVER changes a physical HDMI mode. */
data class ArdaHdmiDisplayProfile(
    val port: Int,
    val resolution: String = "auto",
    val rotationDegrees: Int = 0,
    val densityDpi: Int? = null,
    val allowAapMainOverride: Boolean = port == 0,
)

/** Physically identified HDMI outputs: port 0=MAIN, port 1=AAOS cluster.
 * Do not confuse this with AAP's logical video display_id or SurfaceFlinger ids.
 * A future privileged display backend must validate EDID and apply profiles
 * transactionally, with a rollback timer, before enabling live changes.
 */
object ArdaHdmiDisplayProfileStore {
    private const val prefsName = "arda_hdmi_display_profiles_v1"
    private val allowedRotations = setOf(0, 90, 180, 270)
    private val resolutionPattern = Regex("^[0-9]{2,5}x[0-9]{2,5}$")

    fun validate(profile: ArdaHdmiDisplayProfile) {
        require(profile.port in 0..1) { "Only HDMI ports 0 and 1 are supported" }
        require(profile.rotationDegrees in allowedRotations) { "Invalid rotation" }
        require(profile.port == 0 || !profile.allowAapMainOverride) {
            "AAP MAIN override is not supported for cluster HDMI2"
        }
        val resolution = profile.resolution.lowercase().trim()
        require(resolution == "auto" || resolutionPattern.matches(resolution)) {
            "Resolution must be auto or WIDTHxHEIGHT"
        }
        if (resolution != "auto") {
            val (width, height) = resolution.split('x').map { it.toInt() }
            require(width in 240..7680 && height in 240..7680) {
                "Resolution out of sanity bounds; actual mode must also be supported by EDID"
            }
        }
        require(profile.densityDpi == null || profile.densityDpi in 72..640) {
            "Density must be auto or 72..640 DPI"
        }
    }

    fun load(context: Context, port: Int): ArdaHdmiDisplayProfile {
        require(port in 0..1)
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val profile = ArdaHdmiDisplayProfile(
            port = port,
            resolution = prefs.getString("p${port}_resolution", "auto") ?: "auto",
            rotationDegrees = prefs.getInt("p${port}_rotation", 0),
            densityDpi = prefs.getInt("p${port}_dpi", -1).takeIf { it > 0 },
            allowAapMainOverride = if (port == 0) {
                prefs.getBoolean("p${port}_aap_override", true)
            } else {
                false
            },
        )
        return runCatching { validate(profile); profile }
            .getOrElse { ArdaHdmiDisplayProfile(port) }
    }

    fun save(context: Context, profile: ArdaHdmiDisplayProfile) {
        validate(profile)
        val port = profile.port
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        check(prefs.edit()
            .putString("p${port}_resolution", profile.resolution.lowercase().trim())
            .putInt("p${port}_rotation", profile.rotationDegrees)
            .putInt("p${port}_dpi", profile.densityDpi ?: -1)
            .putBoolean("p${port}_aap_override", profile.allowAapMainOverride)
            .commit()) { "Unable to save HDMI profile" }
    }
}
