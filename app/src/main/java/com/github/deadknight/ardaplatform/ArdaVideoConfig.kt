package com.github.deadknight.ardaplatform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object ArdaVideoConfigContract {
    const val actionReportSdr =
        "com.github.deadknight.ardaplatform.action.REPORT_VIDEO_SDR"
    const val extraPayload = "payload"
}

data class ArdaObservedVideoConfig(
    val configIndex: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val density: Int,
    val realDensity: Int,
    val widthMargin: Int,
    val heightMargin: Int,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val contentInsetLeft: Int,
    val contentInsetRight: Int,
    val contentInsetTop: Int,
    val contentInsetBottom: Int,
    val stableInsetLeft: Int,
    val stableInsetRight: Int,
    val stableInsetTop: Int,
    val stableInsetBottom: Int,
    val codec: String,
    val resolution: String,
) {
    val size: String = "${width}x${height}"
    val displayLabel: String = "$size @ ${fps} fps"
}

data class ArdaVideoConfigState(
    val forcedResolution: String,
    val selectedResolution: String?,
    val selectedFps: Int?,
    val selectedConfigIndex: Int?,
    val selectionPolicy: String?,
    val displayType: String?,
    val advertisedConfigs: List<ArdaObservedVideoConfig>,
    val firstObservedAtMillis: Long,
    val updatedAtMillis: Long,
    val rawSdrPayload: String?,
) {
    val isAuto: Boolean get() = forcedResolution.isBlank()

    val advertisedResolutions: List<String>
        get() = advertisedConfigs
            .sortedWith(
                compareByDescending<ArdaObservedVideoConfig> { it.width * it.height }
                    .thenByDescending { it.fps },
            )
            .map { it.size }
            .distinct()
}

object ArdaVideoConfigStore {
    private const val preferencesName = "arda_platform_video"
    private const val keyForcedResolution = "forced_resolution"
    private const val keyFirstSdrPayload = "first_sdr_payload"
    private const val keyLatestSdrPayload = "latest_sdr_payload"
    private const val keyFirstObservedAtMillis = "first_observed_at_millis"
    private const val keyUpdatedAtMillis = "updated_at_millis"
    private const val runtimeTomlName = "arda-aap-video.toml"
    private const val autoPolicy = "balanced"

    private val knownResolutions = setOf(
        "800x480",
        "1280x720",
        "1920x1080",
        "2560x1440",
        "3840x2160",
        "720x1280",
        "1080x1920",
        "1440x2560",
        "2160x3840",
    )

    fun ensureRuntimeConfig(context: Context): ArdaVideoConfigState {
        val state = load(context)
        writeRuntimeOverride(context, state.forcedResolution)
        return state
    }

    fun load(context: Context): ArdaVideoConfigState {
        val storage = storageContext(context)
        val prefs = storage.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val forced = normalizeForcedResolution(
            prefs.getString(keyForcedResolution, null).orEmpty(),
        )
        val latest = prefs.getString(keyLatestSdrPayload, null)
        val parsed = parseSdrPayload(latest)
        return ArdaVideoConfigState(
            forcedResolution = forced,
            selectedResolution = parsed?.optString("selected_resolution")
                ?.takeIf(String::isNotBlank),
            selectedFps = parsed?.optInt("selected_fps", -1)
                ?.takeIf { it > 0 },
            selectedConfigIndex = parsed?.optInt("selected_config_index", -1)
                ?.takeIf { it >= 0 },
            selectionPolicy = parsed?.optString("selection_policy")
                ?.takeIf(String::isNotBlank),
            displayType = parsed?.optString("display_type")
                ?.takeIf(String::isNotBlank),
            advertisedConfigs = parsed?.optJSONArray("configs")
                .toObservedConfigs(),
            firstObservedAtMillis = prefs.getLong(keyFirstObservedAtMillis, 0L),
            updatedAtMillis = prefs.getLong(keyUpdatedAtMillis, 0L),
            rawSdrPayload = latest,
        )
    }

    fun saveForcedResolution(context: Context, resolution: String) {
        val normalized = normalizeForcedResolution(resolution)
        val storage = storageContext(context)
        check(
            storage.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit()
                .putString(keyForcedResolution, normalized)
                .commit(),
        ) { "Unable to persist forced video resolution" }
        writeRuntimeOverride(storage, normalized)
        ArdaPlatformState.log(
            "ARDA_VIDEO_FORCE_CONFIG=PASS value=${normalized.ifBlank { "AUTO_BALANCED" }}",
        )
    }

    fun reportSdr(context: Context, payload: String) {
        val parsed = requireNotNull(parseSdrPayload(payload)) {
            "Invalid SDR video report JSON"
        }
        require(parsed.optJSONArray("configs")?.length() ?: 0 > 0) {
            "SDR video report contains no video configurations"
        }

        val storage = storageContext(context)
        val prefs = storage.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
            .putString(keyLatestSdrPayload, payload)
            .putLong(keyUpdatedAtMillis, now)
        if (!prefs.contains(keyFirstSdrPayload)) {
            editor
                .putString(keyFirstSdrPayload, payload)
                .putLong(keyFirstObservedAtMillis, now)
        }
        check(editor.commit()) { "Unable to persist SDR video report" }

        val selected = parsed.optString("selected_resolution").ifBlank { "unknown" }
        val count = parsed.optJSONArray("configs")?.length() ?: 0
        ArdaPlatformState.log(
            "ARDA_VIDEO_SDR_PERSIST=PASS configs=$count selected=$selected",
        )
    }


    fun loadFromSnapshot(
        forcedResolution: String,
        rawSdrPayload: String?,
        updatedAtMillis: Long,
    ): ArdaVideoConfigState {
        val parsed = parseSdrPayload(rawSdrPayload)
        return ArdaVideoConfigState(
            forcedResolution = normalizeForcedResolution(forcedResolution),
            selectedResolution = parsed?.optString("selected_resolution")
                ?.takeIf(String::isNotBlank),
            selectedFps = parsed?.optInt("selected_fps", -1)?.takeIf { it > 0 },
            selectedConfigIndex = parsed?.optInt("selected_config_index", -1)
                ?.takeIf { it >= 0 },
            selectionPolicy = parsed?.optString("selection_policy")
                ?.takeIf(String::isNotBlank),
            displayType = parsed?.optString("display_type")
                ?.takeIf(String::isNotBlank),
            advertisedConfigs = parsed?.optJSONArray("configs").toObservedConfigs(),
            firstObservedAtMillis = 0L,
            updatedAtMillis = updatedAtMillis,
            rawSdrPayload = rawSdrPayload,
        )
    }

    fun runtimeTomlFile(context: Context): File =
        File(storageContext(context).filesDir, runtimeTomlName)

    fun normalizeForcedResolution(value: String): String {
        val normalized = value.trim().lowercase()
            .removePrefix("video_")
            .replace('×', 'x')
        if (normalized.isBlank() || normalized == "auto" || normalized == "balanced") {
            return ""
        }
        require(normalized in knownResolutions || normalized.matches(Regex("\\d{3,4}x\\d{3,4}"))) {
            "Unsupported video resolution: $value"
        }
        return normalized
    }

    private fun writeRuntimeOverride(context: Context, forcedResolution: String) {
        val normalized = normalizeForcedResolution(forcedResolution)
        val target = File(context.filesDir, runtimeTomlName)
        val temp = File(context.filesDir, "$runtimeTomlName.tmp")
        context.filesDir.mkdirs()
        val text = buildString {
            appendLine("# Managed by ArdaPlatform. Manual edits may be overwritten.")
            appendLine("[video_override]")
            appendLine("force_resolution = \"${escapeToml(normalized)}\"")
            appendLine("auto_policy = \"$autoPolicy\"")
        }
        FileOutputStream(temp, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        temp.setReadable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(false, false)
        temp.setWritable(true, true)
        check(temp.renameTo(target)) { "Unable to atomically replace ${target.path}" }
        ArdaPlatformState.log("ARDA_VIDEO_RUNTIME_CONFIG_WRITE=PASS path=${target.path}")
    }

    private fun storageContext(context: Context): Context =
        context.createDeviceProtectedStorageContext()

    private fun parseSdrPayload(payload: String?): JSONObject? =
        payload?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }

    private fun JSONArray?.toObservedConfigs(): List<ArdaObservedVideoConfig> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)
                if (width <= 0 || height <= 0) continue
                add(
                    ArdaObservedVideoConfig(
                        configIndex = item.optInt("config_index", index),
                        width = width,
                        height = height,
                        fps = item.optInt("fps", 0),
                        density = item.optInt("density", 0),
                        realDensity = item.optInt("real_density", 0),
                        widthMargin = item.optInt("width_margin", 0),
                        heightMargin = item.optInt("height_margin", 0),
                        marginLeft = item.optInt("margin_left", 0),
                        marginRight = item.optInt("margin_right", 0),
                        marginTop = item.optInt("margin_top", 0),
                        marginBottom = item.optInt("margin_bottom", 0),
                        contentInsetLeft = item.optInt("content_inset_left", 0),
                        contentInsetRight = item.optInt("content_inset_right", 0),
                        contentInsetTop = item.optInt("content_inset_top", 0),
                        contentInsetBottom = item.optInt("content_inset_bottom", 0),
                        stableInsetLeft = item.optInt("stable_inset_left", 0),
                        stableInsetRight = item.optInt("stable_inset_right", 0),
                        stableInsetTop = item.optInt("stable_inset_top", 0),
                        stableInsetBottom = item.optInt("stable_inset_bottom", 0),
                        codec = item.optString("codec"),
                        resolution = item.optString("resolution"),
                    ),
                )
            }
        }
    }

    private fun escapeToml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
