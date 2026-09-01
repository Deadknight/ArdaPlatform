package com.github.deadknight.ardaplatform

import android.content.Context
import android.os.PowerManager

// ARDA_CAPTURE_PROFILE_SELECTOR_V1
enum class ArdaCaptureProfile(
    val propertyValue: String,
    val title: String,
    val description: String,
) {
    STABLE(
        propertyValue = "stable",
        title = "Stable (FB1)",
        description = "Recommended. Single-inflight stable capture profile.",
    ),
    PERFORMANCE(
        propertyValue = "performance",
        title = "Performance (B2.1)",
        description = "Experimental. ~25–26 fps; rare frame artifact may still occur.",
    );

    companion object {
        fun fromProperty(value: String?): ArdaCaptureProfile =
            when (value?.trim()?.lowercase()) {
                PERFORMANCE.propertyValue -> PERFORMANCE
                else -> STABLE // IMPORTANT: unset/invalid is always FB1.
            }
    }
}

object ArdaCaptureProfileController {
    const val DESIRED_PROPERTY = "persist.sys.arda.capture_profile"
    const val ACTIVE_PROPERTY = "sys.arda.capture_profile.active"

    private const val PREFS = "arda_capture_profile"
    private const val PREF_KEY = "desired_profile"

    /**
     * Product rule:
     * - unset / unknown => STABLE / FB1
     * - performance must always be explicitly selected
     */
    fun desiredProfile(context: Context): ArdaCaptureProfile {
        val property = getSystemProperty(DESIRED_PROPERTY, "")
        if (property.isNotBlank()) {
            return ArdaCaptureProfile.fromProperty(property)
        }

        val stored = context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)

        return ArdaCaptureProfile.fromProperty(stored)
    }

    fun activeProfile(): ArdaCaptureProfile =
        ArdaCaptureProfile.fromProperty(
            getSystemProperty(ACTIVE_PROPERTY, "stable")
        )

    fun persistDesiredProfile(
        context: Context,
        profile: ArdaCaptureProfile,
    ): Result<Unit> {
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, profile.propertyValue)
            .apply()

        return setSystemProperty(DESIRED_PROPERTY, profile.propertyValue)
    }

    fun applyAndReboot(
        context: Context,
        profile: ArdaCaptureProfile,
    ): Result<Unit> {
        val persisted = persistDesiredProfile(context, profile)
        if (persisted.isFailure) return persisted

        return runCatching {
            val powerManager =
                context.getSystemService(PowerManager::class.java)
                    ?: error("PowerManager unavailable")
            powerManager.reboot("arda_capture_profile")
        }
    }

    private fun getSystemProperty(key: String, defaultValue: String): String =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod(
                "get",
                String::class.java,
                String::class.java,
            )
            method.invoke(null, key, defaultValue) as String
        }.getOrDefault(defaultValue)

    private fun setSystemProperty(key: String, value: String): Result<Unit> =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod(
                "set",
                String::class.java,
                String::class.java,
            )
            method.invoke(null, key, value)
            Unit
        }
}
