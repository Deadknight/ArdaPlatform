package com.github.deadknight.ardaplatform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Small dependency-free client for the existing OpenCharge VEM generator API. */
class VehicleEnergyApi(
    private val baseUrl: String = "https://aap.opencharge.top/",
) {
    data class VariantData(
        val name: String,
        val maximumRegenWh: Float,
        val dragCoefficient: Float,
    )

    suspend fun getMakers(): List<String> = withContext(Dispatchers.IO) {
        parseNameList(request("makers.php"))
    }

    suspend fun getModels(maker: String): List<String> = withContext(Dispatchers.IO) {
        parseNameList(
            request(
                "maker_models.php",
                mapOf("maker" to maker),
            ),
        )
    }

    suspend fun getVariants(maker: String, model: String): List<String> =
        withContext(Dispatchers.IO) {
            parseNameList(
                request(
                    "maker_model_variants.php",
                    mapOf("maker" to maker, "model" to model),
                ),
            )
        }

    suspend fun getVariantData(variant: String): VariantData = withContext(Dispatchers.IO) {
        val json = JSONObject(
            request(
                "variant_data.php",
                mapOf("variant" to variant),
            ),
        )
        VariantData(
            name = json.optString("name", variant),
            maximumRegenWh = json.getDouble("maxRegenWh").toFloat(),
            dragCoefficient = json.getDouble("cd").toFloat(),
        )
    }

    suspend fun getProto(variantJson: String): ByteArray = withContext(Dispatchers.IO) {
        val response = request(
            path = "get_proto.php",
            method = "POST",
            body = variantJson,
        )
        VehicleEnergyProto.decodeApiHex(response)
    }

    private fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): String {
        val normalizedBase = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val fullUrl = buildString {
            append(normalizedBase)
            append(path)
            if (queryString.isNotEmpty()) {
                append('?')
                append(queryString)
            }
        }

        val connection = URI(fullUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", "ArdaPlatform/${BuildConfig.VERSION_NAME}")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                throw IOException("VEM API HTTP $responseCode: ${response.take(240)}")
            }
            if (response.isBlank()) {
                throw IOException("VEM API returned an empty response")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNameList(body: String): List<String> {
        val list = JSONObject(body).getJSONArray("list")
        return buildList {
            for (index in 0 until list.length()) {
                val name = list.getJSONObject(index).optString("name").trim()
                if (name.isNotEmpty()) add(name)
            }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
