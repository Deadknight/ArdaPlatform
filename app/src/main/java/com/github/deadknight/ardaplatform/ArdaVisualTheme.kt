package com.github.deadknight.ardaplatform

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ArdaVisualTheme {
    const val launcherBackgroundModeDefault = 0
    const val launcherBackgroundModeColor = 1
    const val launcherBackgroundModeImage = 2

    const val launcherBackgroundModeSetting = "arda_launcher_background_mode"
    const val launcherBackgroundColorSetting = "arda_launcher_background_color"
    const val launcherBackgroundUriSetting = "arda_launcher_background_uri"
    const val navigationBarBackgroundColorSetting =
        "arda_navigation_bar_background_color"
    const val statusBarBackgroundColorSetting =
        "arda_status_bar_background_color"
    const val revisionSetting = "arda_visual_theme_revision"

    const val defaultLauncherColor = "#FF0B1016"
    const val noOverride = "default"

    const val launcherBackgroundAuthoritySuffix = ".launcher-background"
    const val launcherBackgroundPath = "current"

    fun launcherBackgroundUri(context: Context): Uri =
        Uri.parse(
            "content://${context.packageName}$launcherBackgroundAuthoritySuffix/" +
                launcherBackgroundPath,
        )

    fun isValidColor(value: String): Boolean =
        runCatching { android.graphics.Color.parseColor(normalizeColor(value)) }.isSuccess

    fun normalizeColor(value: String): String {
        val trimmed = value.trim()
        if (trimmed.equals(noOverride, ignoreCase = true)) return noOverride
        val hex = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return when (hex.length) {
            7 -> "#FF${hex.substring(1).uppercase()}"
            9 -> "#${hex.substring(1).uppercase()}"
            else -> hex.uppercase()
        }
    }
}

object ArdaVisualThemeStore {
    private const val launcherBackgroundFileName = "arda_launcher_background.jpg"
    private const val maxImageEdge = 2048
    private const val jpegQuality = 90

    fun launcherBackgroundFile(context: Context): File =
        File(context.filesDir, launcherBackgroundFileName)

    fun hasLauncherBackground(context: Context): Boolean =
        launcherBackgroundFile(context).isFile &&
            launcherBackgroundFile(context).length() > 0L

    fun importLauncherBackground(context: Context, sourceUri: Uri) {
        val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxImageEdge) {
                val scale = maxImageEdge.toFloat() / longest.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }

        val target = launcherBackgroundFile(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.parentFile?.mkdirs()

        FileOutputStream(temp).use { output ->
            check(
                bitmap.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    jpegQuality,
                    output,
                ),
            ) { "Could not encode launcher background image" }
            output.fd.sync()
        }
        bitmap.recycle()

        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace launcher background image")
        }
        check(temp.renameTo(target)) {
            temp.delete()
            "Could not publish launcher background image"
        }
    }

    fun removeLauncherBackground(context: Context) {
        launcherBackgroundFile(context).delete()
    }

    fun bumpRevision(context: Context) {
        val resolver = context.contentResolver
        val current = Settings.Global.getInt(
            resolver,
            ArdaVisualTheme.revisionSetting,
            0,
        )
        check(
            Settings.Global.putInt(
                resolver,
                ArdaVisualTheme.revisionSetting,
                if (current == Int.MAX_VALUE) 1 else current + 1,
            ),
        ) { "Could not update ARDA visual theme revision" }
    }
}
