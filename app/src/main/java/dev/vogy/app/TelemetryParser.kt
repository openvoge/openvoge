package dev.vogy.app

import java.nio.charset.Charset

object TelemetryParser {
    fun extractTelemetrySnapshots(bytes: ByteArray): List<TelemetrySnapshot> {
        val snapshots = mutableListOf<TelemetrySnapshot>()

        snapshots += extractBinaryTelemetryFrames(bytes)
        if (snapshots.isNotEmpty()) {
            return snapshots
        }

        val raw = try {
            bytes.toString(Charset.forName("UTF-8")).trim()
        } catch (_: Exception) {
            ""
        }

        snapshots += extractBinaryTelemetryFrames(decodeHexPayload(raw))
        if (snapshots.isNotEmpty()) {
            return snapshots
        }

        val rpm = Regex("""(?:rpm|revs?)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val speed = Regex("""(?:speed|kmh|km/h)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fuel = Regex("""(?:fuel|gas|gasoline)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (rpm != null || speed != null || fuel != null) {
            return listOf(TelemetrySnapshot(rpm = rpm, speedKmh = speed, fuelPercent = fuel, raw = raw))
        }

        val parts = raw.split(',', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val numbers = parts.mapNotNull { it.toIntOrNull() }
        if (numbers.isNotEmpty()) {
            return listOf(TelemetrySnapshot(
                rpm = numbers.getOrNull(0),
                speedKmh = numbers.getOrNull(1),
                fuelPercent = numbers.getOrNull(2),
                raw = raw
            ))
        }

        return emptyList()
    }

    private fun extractBinaryTelemetryFrames(bytes: ByteArray): List<TelemetrySnapshot> {
        if (bytes.isEmpty()) {
            return emptyList()
        }

        if (bytes.size == 20) {
            return parseBinaryTelemetryFrame(bytes)?.let(::listOf).orEmpty()
        }

        return buildList {
            for (startIndex in 0..(bytes.size - 20)) {
                parseBinaryTelemetryFrame(bytes.copyOfRange(startIndex, startIndex + 20))?.let(::add)
            }
        }
    }

    private fun parseBinaryTelemetryFrame(bytes: ByteArray): TelemetrySnapshot? {
        if (bytes.size != 20) {
            return null
        }

        val commandId = bytes[1].toHexByte()
        if (commandId !in setOf("1A", "4A", "4B", "4C")) {
            return null
        }

        val rawHex = bytes.joinToString(" ") { it.toHexByte() }
        return when (commandId) {
            "4A" -> TelemetrySnapshot(
                commandId = commandId,
                rpm = readUInt(bytes, 3, 2),
                speedKmh = unsignedInt(bytes[2]),
                fuelPercent = unsignedInt(bytes[11]),
                meanSpeed = unsignedInt(bytes[6]).toString(),
                maxSpeed = unsignedInt(bytes[12]).toString(),
                voltage = formatVoltage(unsignedInt(bytes[9]), unsignedInt(bytes[16])),
                temperature = formatTemperature(unsignedInt(bytes[10]), unsignedInt(bytes[16])),
                gears = unsignedInt(bytes[5]),
                raw = rawHex
            )
            "4B" -> TelemetrySnapshot(
                commandId = commandId,
                totalKm = formatDistance(bytes, 2, 3),
                driveKm = formatDistance(bytes, 5, 3),
                driveKmTimeMinutes = readUInt(bytes, 8, 2),
                raw = rawHex
            )
            "4C" -> TelemetrySnapshot(
                commandId = commandId,
                naviState = unsignedInt(bytes[2]),
                phoneState = unsignedInt(bytes[3]),
                raw = rawHex
            )
            "1A" -> TelemetrySnapshot(
                commandId = commandId,
                phoneState = when (unsignedInt(bytes[2])) {
                    2 -> 1
                    3 -> 2
                    else -> 0
                },
                naviState = when (unsignedInt(bytes[2])) {
                    4 -> 2
                    5 -> 1
                    else -> 0
                },
                raw = rawHex
            )
            else -> null
        }
    }

    private fun decodeHexPayload(raw: String): ByteArray {
        val hexPairs = Regex("(?i)[0-9a-f]{2}")
            .findAll(raw)
            .map { it.value }
            .toList()
        if (hexPairs.size < 20) {
            return ByteArray(0)
        }

        return ByteArray(hexPairs.size) { index ->
            hexPairs[index].toInt(16).toByte()
        }
    }

    private fun readUInt(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (index in 0 until length) {
            value = (value shl 8) or unsignedInt(bytes[offset + index])
        }
        return value
    }

    private fun formatDistance(bytes: ByteArray, offset: Int, length: Int): String {
        val rawValue = readUInt(bytes, offset, length)
        return if (rawValue == 0) {
            "0"
        } else {
            String.format(java.util.Locale.ROOT, "%.1f", rawValue / 10.0)
        }
    }

    private fun formatVoltage(rawValue: Int, version: Int): String {
        val formatted = if (version >= 2) rawValue / 10.0 else rawValue.toDouble()
        return String.format(java.util.Locale.ROOT, "%.1f", formatted)
    }

    private fun formatTemperature(rawValue: Int, version: Int): String {
        return when {
            rawValue > 250 -> rawValue.toString()
            version >= 2 -> (rawValue - 60).toString()
            else -> (rawValue - 30).toString()
        }
    }

    private fun unsignedInt(byte: Byte): Int {
        return byte.toInt() and 0xFF
    }
}
