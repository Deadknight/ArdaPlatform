package com.github.deadknight.ardaplatform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors

/** OEM Google Maps vehicle-energy provider hosted inside the ArdaPlatform system app. */
class MapsEnergyProvider : ContentProvider() {
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(): Boolean {
        val providerContext = requireNotNull(context)
        val status = VehicleEnergyStore.loadStatus(providerContext)
        Log.i(
            tag,
            "PROVIDER_CREATED uid=${Process.myUid()} modelBytes=${status.modelBytes} " +
                "forecastBytes=${status.forecastBytes}",
        )
        return true
    }

    override fun getType(uri: Uri): String? = when (normalizedPath(uri)) {
        "model", "forecast" -> mimeProtobuf
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val path = normalizedPath(uri)
        require(path == "model" || path == "forecast") { "Unsupported URI: $uri" }
        enforceCarEnergyOrTrustedCaller("query", uri)
        val file = if (path == "model") modelFile() else forecastFile()
        val columns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            "mime_type",
        )
        return MatrixCursor(columns, 1).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                    OpenableColumns.SIZE -> row.add(file.takeIf { it.isFile }?.length() ?: 0L)
                    "mime_type" -> row.add(mimeProtobuf)
                    else -> row.add(null)
                }
            }
            Log.i(
                tag,
                "QUERY path=$path caller=${callerDescription()} bytes=${file.takeIf { it.isFile }?.length() ?: 0L}",
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val path = normalizedPath(uri)
        val write = mode.contains('w')

        if (path == "model" && !write) {
            enforceCarEnergyOrTrustedCaller("read model", uri)
            val file = modelFile()
            if (!file.isFile) {
                Log.w(tag, "MODEL_OPEN_MISSING caller=${callerDescription()}")
                throw FileNotFoundException("Vehicle energy model is not installed")
            }
            Log.i(
                tag,
                "MODEL_OPEN_READ caller=${callerDescription()} bytes=${file.length()}",
            )
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        if (path == "forecast" && write) {
            enforceCarEnergyOrTrustedCaller("write forecast", uri)
            return createWritePipe(
                operation = "forecast",
                extractOuter = false,
                notifyModel = false,
            )
        }

        if (path == "debug/install_outer" && write) {
            enforceAdmin("install outer model", uri)
            return createWritePipe(
                operation = "debug/install_outer",
                extractOuter = true,
                notifyModel = true,
            )
        }

        if (path == "debug/install_inner" && write) {
            enforceAdmin("install inner model", uri)
            return createWritePipe(
                operation = "debug/install_inner",
                extractOuter = false,
                notifyModel = true,
            )
        }

        throw FileNotFoundException("Unsupported mode=$mode URI=$uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        require(normalizedPath(uri) == "forecast") { "Unsupported URI: $uri" }
        enforceCarEnergyOrTrustedCaller("insert forecast", uri)
        val payload = firstBlob(values, "data", "payload", "protobuf")
            ?: throw IllegalArgumentException("Forecast ContentValues has no blob payload")
        VehicleEnergyStore.saveForecast(requireNotNull(context), payload)
        Log.i(tag, "FORECAST_INSERT caller=${callerDescription()} bytes=${payload.size}")
        return VehicleEnergyContract.forecastUri
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        insert(uri, values)
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceAdmin("delete energy data", uri)
        return when (normalizedPath(uri)) {
            "model" -> {
                VehicleEnergyStore.clearModel(requireNotNull(context))
                1
            }

            "forecast" -> {
                val file = forecastFile()
                if (!file.exists() || file.delete()) 1 else 0
            }

            else -> 0
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val providerContext = requireNotNull(context)
        return when (method) {
            VehicleEnergyContract.methodStatus -> {
                enforceCarEnergyOrTrustedCaller("status", VehicleEnergyContract.rootUri)
                VehicleEnergyStore.statusBundle(providerContext).apply {
                    putString("authority", VehicleEnergyContract.authority)
                    putString("caller", callerDescription())
                }
            }

            VehicleEnergyContract.methodNotifyModel -> {
                enforceAdmin("notify model", VehicleEnergyContract.modelUri)
                VehicleEnergyStore.notifyModelChanged(providerContext)
                VehicleEnergyStore.statusBundle(providerContext, notifySent = true)
            }

            VehicleEnergyContract.methodSetBatteryPercent -> {
                enforceAdmin("set battery percentage", VehicleEnergyContract.modelUri)
                runAdminCall {
                    val percent = requireNotNull(extras).getInt(
                        VehicleEnergyContract.extraPercent,
                        Int.MIN_VALUE,
                    )
                    require(percent != Int.MIN_VALUE) { "Missing percent" }
                    VehicleEnergyStore.setBatteryPercent(providerContext, percent)
                    VehicleEnergyStore.statusBundle(providerContext, notifySent = true)
                }
            }

            VehicleEnergyContract.methodSetFuelType -> {
                enforceAdmin("set fuel type", VehicleEnergyContract.rootUri)
                runAdminCall {
                    val rawFuelType = requireNotNull(extras).getString(
                        VehicleEnergyContract.extraFuelType,
                    )
                    val fuelType = ConfiguredFuelType.parseWireName(rawFuelType)
                        ?: error("Unsupported fuel type: $rawFuelType")
                    VehicleEnergyStore.setFuelType(providerContext, fuelType)
                    VehicleEnergyStore.statusBundle(providerContext)
                }
            }

            VehicleEnergyContract.methodSetConnectorTypes -> {
                enforceAdmin("set connector types", VehicleEnergyContract.rootUri)
                runAdminCall {
                    val data = requireNotNull(extras)
                    val rawConnectorTypes = data.getStringArrayList(
                        VehicleEnergyContract.extraConnectorTypes,
                    ) ?: data.getString(VehicleEnergyContract.extraConnectorTypes)
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty()
                    val connectorTypes = rawConnectorTypes.map { raw ->
                        ConfiguredEvConnectorType.parseWireName(raw)
                            ?: error("Unsupported connector type: $raw")
                    }.toSet()
                    VehicleEnergyStore.setConnectorTypes(providerContext, connectorTypes)
                    VehicleEnergyStore.statusBundle(providerContext)
                }
            }

            VehicleEnergyContract.methodInstallInnerHex -> {
                enforceAdmin("install model hex", VehicleEnergyContract.modelUri)
                runAdminCall {
                    val hex = requireNotNull(extras).getString(VehicleEnergyContract.extraHex)
                        ?: error("Missing model hex")
                    val bytes = VehicleEnergyProto.decodeApiHex(hex)
                    VehicleEnergyStore.installModel(providerContext, bytes)
                    VehicleEnergyStore.statusBundle(providerContext, notifySent = true)
                }
            }

            VehicleEnergyContract.methodInstallInnerBytes -> {
                enforceAdmin("install model bytes", VehicleEnergyContract.modelUri)
                runAdminCall {
                    val bytes = requireNotNull(extras).getByteArray(
                        VehicleEnergyContract.extraModelBytes,
                    ) ?: error("Missing model bytes")
                    VehicleEnergyStore.installModel(providerContext, bytes)
                    VehicleEnergyStore.statusBundle(providerContext, notifySent = true)
                }
            }

            VehicleEnergyContract.methodClearModel -> {
                enforceAdmin("clear model", VehicleEnergyContract.modelUri)
                runAdminCall {
                    VehicleEnergyStore.clearModel(providerContext)
                    VehicleEnergyStore.statusBundle(providerContext, notifySent = true)
                }
            }

            else -> super.call(method, arg, extras)
        }
    }

    private fun createWritePipe(
        operation: String,
        extractOuter: Boolean,
        notifyModel: Boolean,
    ): ParcelFileDescriptor {
        val callingUid = Binder.getCallingUid()
        val callingPackage = safeCallingPackage()
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (error: IOException) {
            throw FileNotFoundException("Could not create provider pipe for $operation").apply {
                initCause(error)
            }
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        ioExecutor.execute {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    val received = input.readBytesBounded()
                    val payload = if (extractOuter) {
                        VehicleEnergyProto.normalizeModelPayload(received)
                    } else {
                        received
                    }
                    val providerContext = requireNotNull(context)
                    if (notifyModel) {
                        VehicleEnergyStore.installModel(providerContext, payload)
                    } else {
                        VehicleEnergyStore.saveForecast(providerContext, payload)
                    }
                    Log.i(
                        tag,
                        "WRITE_COMPLETE operation=$operation callerUid=$callingUid " +
                            "callerPackage=$callingPackage receivedBytes=${received.size} " +
                            "storedBytes=${payload.size}",
                    )
                }
            } catch (error: Throwable) {
                Log.e(
                    tag,
                    "WRITE_FAIL operation=$operation callerUid=$callingUid " +
                        "callerPackage=$callingPackage",
                    error,
                )
            }
        }
        return writeEnd
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxInputBytes) throw IOException("Input exceeds $maxInputBytes bytes")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun enforceCarEnergyOrTrustedCaller(operation: String, uri: Uri) {
        val callerUid = Binder.getCallingUid()
        if (isTrustedUid(callerUid)) return
        val providerContext = requireNotNull(context)
        if (providerContext.checkCallingPermission(carEnergyPermission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        throw SecurityException("$operation denied uid=$callerUid uri=$uri")
    }

    private fun enforceAdmin(operation: String, uri: Uri) {
        val callerUid = Binder.getCallingUid()
        if (isTrustedUid(callerUid)) return
        val providerContext = requireNotNull(context)
        if (providerContext.checkCallingPermission(adminPermission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        throw SecurityException("$operation denied uid=$callerUid uri=$uri")
    }

    private fun isTrustedUid(uid: Int): Boolean =
        uid == uidRoot || uid == uidShell ||
            uid % perUserRange == Process.myUid() % perUserRange

    private fun callerDescription(): String =
        "uid=${Binder.getCallingUid()},package=${safeCallingPackage()}"

    private fun safeCallingPackage(): String = runCatching {
        callingPackage ?: "<unknown>"
    }.getOrDefault("<unavailable>")

    private fun modelFile(): File = VehicleEnergyStore.modelFile(requireNotNull(context))

    private fun forecastFile(): File = VehicleEnergyStore.forecastFile(requireNotNull(context))

    private fun runAdminCall(block: () -> Bundle): Bundle = try {
        block()
    } catch (error: Throwable) {
        Bundle().apply {
            putString(
                VehicleEnergyContract.extraError,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun normalizedPath(uri: Uri): String =
        uri.path.orEmpty().trimStart('/')

    private fun firstBlob(values: ContentValues?, vararg keys: String): ByteArray? {
        if (values == null) return null
        for (key in keys) {
            val value = values.getAsByteArray(key)
            if (value != null) return value
        }
        return null
    }

    companion object {
        private const val tag = "ArdaMapsEnergy"
        private const val carEnergyPermission = "android.car.permission.CAR_ENERGY"
        private val adminPermission = "${BuildConfig.APPLICATION_ID}.permission.MANAGE_ARDA_PLATFORM"
        private const val mimeProtobuf = "application/x-protobuf"
        private const val uidRoot = 0
        private const val uidShell = 2000
        private const val perUserRange = 100_000
        private const val maxInputBytes = 4 * 1024 * 1024
    }
}
