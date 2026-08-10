package com.github.deadknight.ardaplatform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

class ArdaLauncherBackgroundProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        requireSupported(uri)
        return "image/jpeg"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireSupported(uri)
        if (mode != "r") {
            throw SecurityException("Launcher background provider is read-only")
        }
        val file = ArdaVisualThemeStore.launcherBackgroundFile(
            requireNotNull(context),
        )
        if (!file.isFile) {
            throw FileNotFoundException("No ARDA launcher background selected")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    private fun requireSupported(uri: Uri) {
        require(
            uri.authority == "${requireNotNull(context).packageName}" +
                ArdaVisualTheme.launcherBackgroundAuthoritySuffix &&
                uri.pathSegments == listOf(ArdaVisualTheme.launcherBackgroundPath),
        ) { "Unsupported URI: $uri" }
    }
}
