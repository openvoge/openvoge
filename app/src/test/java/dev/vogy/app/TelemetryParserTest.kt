package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryParserTest {

    @Test
    fun parseBinaryTelemetryFrame4A() {
        // Sample 20-byte 0x4A live telemetry frame
        // [0]=0x01, [1]=0x4A, [2]=speed (50 km/h), [3..4]=rpm (0x0B, 0xB8 = 3000),
        // [5]=gear (3), [6]=meanSpeed (45), [9]=voltage (128 -> 12.8V on ver 2),
        // [10]=temp (85 -> 25C on ver 2), [11]=fuel (75%), [12]=maxSpeed (90), [16]=version (2)
        val frame = ByteArray(20).apply {
            this[0] = 0x01
            this[1] = 0x4A.toByte()
            this[2] = 50
            this[3] = 0x0B
            this[4] = 0xB8.toByte()
            this[5] = 3
            this[6] = 45
            this[9] = 128.toByte()
            this[10] = 85
            this[11] = 75
            this[12] = 90
            this[16] = 2
        }

        val snapshots = TelemetryParser.extractTelemetrySnapshots(frame)
        assertEquals(1, snapshots.size)
        val s = snapshots.first()
        assertEquals("4A", s.commandId)
        assertEquals(50, s.speedKmh)
        assertEquals(3000, s.rpm)
        assertEquals(3, s.gears)
        assertEquals("45", s.meanSpeed)
        assertEquals("90", s.maxSpeed)
        assertEquals(75, s.fuelPercent)
        assertEquals("12.8", s.voltage)
        assertEquals("25", s.temperature)
    }

    @Test
    fun parseBinaryTelemetryFrame4B() {
        // [0]=0x01, [1]=0x4B, [2..4]=totalKm (12345 -> 1234.5), [5..7]=driveKm (123 -> 12.3), [8..9]=time (45 min)
        val frame = ByteArray(20).apply {
            this[0] = 0x01
            this[1] = 0x4B.toByte()
            this[2] = 0x00
            this[3] = 0x30
            this[4] = 0x39 // 12345
            this[5] = 0x00
            this[6] = 0x00
            this[7] = 0x7B // 123
            this[8] = 0x00
            this[9] = 45
        }

        val snapshots = TelemetryParser.extractTelemetrySnapshots(frame)
        assertEquals(1, snapshots.size)
        val s = snapshots.first()
        assertEquals("4B", s.commandId)
        assertEquals("1234.5", s.totalKm)
        assertEquals("12.3", s.driveKm)
        assertEquals(45, s.driveKmTimeMinutes)
    }

    @Test
    fun unrecognizedGarbageReturnsEmptyList() {
        // Binary garbage that is neither 20 bytes nor valid text with numbers
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val snapshots = TelemetryParser.extractTelemetrySnapshots(garbage)
        assertTrue("Expected empty list for unrecognized binary bytes", snapshots.isEmpty())
    }

    @Test
    fun textRegexFallback() {
        val text = "rpm: 4500, speed: 65, fuel: 80%".toByteArray(Charsets.UTF_8)
        val snapshots = TelemetryParser.extractTelemetrySnapshots(text)
        assertEquals(1, snapshots.size)
        val s = snapshots.first()
        assertEquals(4500, s.rpm)
        assertEquals(65, s.speedKmh)
        assertEquals(80, s.fuelPercent)
    }

    @Test
    fun vogeKickoffChecksumVerification() {
        // Kickoff frame: 01 4A 00 00 00 00 00 00 00 78 70 50 00 00 00 12 02 00 07 04
        val kickoff = byteArrayOf(
            0x01, 0x4A.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x78, 0x70, 0x50, 0x00, 0x00, 0x00, 0x12, 0x02, 0x00, 0x00, 0x04
        )
        // compute XOR sum
        var xorSum = 0
        for (b in kickoff) {
            xorSum = xorSum xor (b.toInt() and 0xFF)
        }
        assertEquals(0x07, xorSum)
    }
}
