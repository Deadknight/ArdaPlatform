package com.github.deadknight.ardaplatform

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Minimal protobuf wire editor for the OEM vehicle-energy model.
 *
 * The Maps payload is intentionally treated as opaque bytes except for the
 * validated battery path:
 *
 *   root.field1.field3.field1 = battery level Wh
 *   root.field1.field4.field1 = battery capacity Wh
 *
 * Unknown fields and their original byte representation are preserved.
 */
object VehicleEnergyProto {
    private const val maxInputBytes = 4 * 1024 * 1024

    data class BatteryInfo(
        val levelWh: Long,
        val capacityWh: Long,
    ) {
        val percent: Int
            get() = if (capacityWh <= 0L) 0 else {
                ((levelWh * 100.0) / capacityWh)
                    .roundToInt()
                    .coerceIn(0, 100)
            }
    }

    fun decodeApiHex(responseBody: String): ByteArray {
        var normalized = responseBody.trim()
        if (normalized.startsWith('"') && normalized.endsWith('"') && normalized.length >= 2) {
            normalized = decodeJsonString(normalized)
        }
        normalized = normalized
            .removePrefix("0x")
            .removePrefix("0X")
            .filterNot(Char::isWhitespace)

        require(normalized.isNotEmpty()) { "Energy model response is empty" }
        require(normalized.length % 2 == 0) { "Energy model hex has an odd length" }
        require(normalized.length / 2 <= maxInputBytes) {
            "Energy model exceeds $maxInputBytes bytes"
        }

        return ByteArray(normalized.length / 2) { index ->
            val offset = index * 2
            val high = normalized[offset].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex at offset $offset")
            val low = normalized[offset + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex at offset ${offset + 1}")
            ((high shl 4) or low).toByte()
        }
    }

    /** Extract SensorBatch field 23 only when it is the sole top-level field. */
    fun normalizeModelPayload(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Energy model payload is empty" }
        require(payload.size <= maxInputBytes) { "Energy model payload is too large" }

        val fields = scanFields(payload)
        if (fields.size == 1) {
            val only = fields.single()
            if (only.number == 23 && only.wireType == wireLengthDelimited) {
                return only.payload(payload)
            }
        }
        return payload
    }

    fun inspectBattery(model: ByteArray): BatteryInfo {
        val root1 = requireLengthDelimited(model, 1, "root field 1")
        val levelMessage = requireLengthDelimited(root1, 3, "battery level message")
        val capacityMessage = requireLengthDelimited(root1, 4, "battery capacity message")
        val levelWh = requireVarint(levelMessage, 1, "battery level")
        val capacityWh = requireVarint(capacityMessage, 1, "battery capacity")

        require(capacityWh > 0L) { "Battery capacity must be greater than zero" }
        require(levelWh >= 0L) { "Battery level must not be negative" }
        require(levelWh <= capacityWh * 2L) {
            "Battery level is implausibly larger than capacity"
        }
        return BatteryInfo(levelWh, capacityWh)
    }

    fun setBatteryPercent(model: ByteArray, percent: Int): Pair<ByteArray, BatteryInfo> {
        require(percent in 0..100) { "Battery percentage must be between 0 and 100" }
        val current = inspectBattery(model)
        val requestedLevel = (current.capacityWh * percent.toLong()) / 100L

        val patchedRoot = replaceLengthDelimited(model, 1) { root1 ->
            replaceLengthDelimited(root1, 3) { levelMessage ->
                replaceVarint(levelMessage, 1, requestedLevel)
            }
        }
        val updated = inspectBattery(patchedRoot)
        return patchedRoot to updated
    }

    private fun requireLengthDelimited(
        message: ByteArray,
        fieldNumber: Int,
        label: String,
    ): ByteArray {
        val field = scanFields(message).firstOrNull {
            it.number == fieldNumber && it.wireType == wireLengthDelimited
        } ?: throw IOException("Missing $label")
        return field.payload(message)
    }

    private fun requireVarint(
        message: ByteArray,
        fieldNumber: Int,
        label: String,
    ): Long {
        val field = scanFields(message).firstOrNull {
            it.number == fieldNumber && it.wireType == wireVarint
        } ?: throw IOException("Missing $label")
        return readVarint(message, field.valueStart).value
    }

    private fun replaceLengthDelimited(
        message: ByteArray,
        fieldNumber: Int,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        val fields = scanFields(message)
        val target = fields.firstOrNull {
            it.number == fieldNumber && it.wireType == wireLengthDelimited
        } ?: throw IOException("Missing length-delimited field $fieldNumber")

        val replacement = transform(target.payload(message))
        val output = ByteArrayOutputStream(message.size + replacement.size + 16)
        output.write(message, 0, target.keyEnd)
        output.write(encodeVarint(replacement.size.toLong()))
        output.write(replacement)
        output.write(message, target.end, message.size - target.end)
        return output.toByteArray()
    }

    private fun replaceVarint(
        message: ByteArray,
        fieldNumber: Int,
        replacement: Long,
    ): ByteArray {
        require(replacement >= 0L) { "Negative protobuf varints are not supported" }
        val fields = scanFields(message)
        val target = fields.firstOrNull {
            it.number == fieldNumber && it.wireType == wireVarint
        } ?: throw IOException("Missing varint field $fieldNumber")

        val output = ByteArrayOutputStream(message.size + 10)
        output.write(message, 0, target.valueStart)
        output.write(encodeVarint(replacement))
        output.write(message, target.end, message.size - target.end)
        return output.toByteArray()
    }

    private data class WireField(
        val number: Int,
        val wireType: Int,
        val start: Int,
        val keyEnd: Int,
        val valueStart: Int,
        val payloadStart: Int,
        val payloadLength: Int,
        val end: Int,
    ) {
        fun payload(message: ByteArray): ByteArray =
            message.copyOfRange(payloadStart, payloadStart + payloadLength)
    }

    private fun scanFields(message: ByteArray): List<WireField> {
        val fields = ArrayList<WireField>()
        var position = 0
        while (position < message.size) {
            val start = position
            val key = readVarint(message, position)
            position = key.next
            val keyEnd = position
            val number = (key.value ushr 3).toInt()
            val wireType = (key.value and 0x07).toInt()
            if (number <= 0) throw IOException("Invalid protobuf field number")

            val valueStart = position
            var payloadStart = valueStart
            val payloadLength: Int
            when (wireType) {
                wireVarint -> {
                    position = readVarint(message, position).next
                    payloadLength = position - valueStart
                }

                wireFixed64 -> {
                    ensureRemaining(message, position, 8)
                    position += 8
                    payloadLength = 8
                }

                wireLengthDelimited -> {
                    val length = readVarint(message, position)
                    position = length.next
                    require(length.value in 0..Int.MAX_VALUE.toLong()) {
                        "Invalid protobuf length ${length.value}"
                    }
                    payloadStart = position
                    payloadLength = length.value.toInt()
                    ensureRemaining(message, position, payloadLength)
                    position += payloadLength
                }

                wireFixed32 -> {
                    ensureRemaining(message, position, 4)
                    position += 4
                    payloadLength = 4
                }

                else -> throw IOException("Unsupported protobuf wire type $wireType")
            }

            fields += WireField(
                number = number,
                wireType = wireType,
                start = start,
                keyEnd = keyEnd,
                valueStart = valueStart,
                payloadStart = payloadStart,
                payloadLength = payloadLength,
                end = position,
            )
        }
        return fields
    }

    private data class VarintResult(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, offset: Int): VarintResult {
        var position = offset
        var value = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= bytes.size) throw IOException("Truncated protobuf varint")
            val current = bytes[position++].toInt() and 0xff
            value = value or ((current and 0x7f).toLong() shl shift)
            if ((current and 0x80) == 0) return VarintResult(value, position)
            shift += 7
        }
        throw IOException("Protobuf varint is too long")
    }

    private fun encodeVarint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream(10)
        while (true) {
            if ((remaining and -128L) == 0L) {
                output.write(remaining.toInt())
                return output.toByteArray()
            }
            output.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }

    private fun ensureRemaining(bytes: ByteArray, offset: Int, count: Int) {
        if (count < 0 || offset < 0 || offset > bytes.size || count > bytes.size - offset) {
            throw IOException("Truncated protobuf value")
        }
    }

    private fun decodeJsonString(value: String): String {
        val output = StringBuilder(value.length)
        var index = 1
        val end = value.length - 1
        while (index < end) {
            val current = value[index++]
            if (current != '\\') {
                output.append(current)
                continue
            }
            if (index >= end) throw IllegalArgumentException("Invalid JSON string")
            when (val escaped = value[index++]) {
                '"', '\\', '/' -> output.append(escaped)
                'b' -> output.append('\b')
                'f' -> output.append('\u000C')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    if (index + 4 > end) throw IllegalArgumentException("Invalid JSON unicode escape")
                    val hex = value.substring(index, index + 4)
                    output.append(hex.toInt(16).toChar())
                    index += 4
                }
                else -> throw IllegalArgumentException("Invalid JSON escape \\$escaped")
            }
        }
        return output.toString()
    }

    private const val wireVarint = 0
    private const val wireFixed64 = 1
    private const val wireLengthDelimited = 2
    private const val wireFixed32 = 5
}
