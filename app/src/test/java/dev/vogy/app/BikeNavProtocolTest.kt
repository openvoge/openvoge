package dev.vogy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class BikeNavProtocolTest {

    private fun at(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 21, hour, minute, 0)
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    private fun xorOf(frame: ByteArray): Int = frame.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }

    @Test
    fun endFrameMatchesTheOfficialApp() {
        // 01 6A 00×9 HH MM PM 00 01 00 00 CK 04, with CK = 0x6E ^ HH ^ MM ^ PM
        val frame = BikeNavProtocol.endFrame(at(14, 37))
        val expected = ByteArray(20).apply {
            this[0] = 0x01; this[1] = 0x6A
            this[11] = 14; this[12] = 37; this[13] = 1
            this[15] = 1
            this[18] = (0x6E xor 14 xor 37 xor 1).toByte()
            this[19] = 0x04
        }
        assertArrayEquals(expected, frame)
    }

    @Test
    fun openingBurstMatchesTheOfficialApp() {
        val frames = BikeNavProtocol.burst(BikeNavProtocol.Guidance.EMPTY, at(9, 5), chineseSystemLanguage = false)
        assertEquals(listOf(0x6A, 0x6B, 0x6C, 0x6D, 0x6E), frames.map { it.u(1) })
        // Status: CK = 0x6F ^ HH ^ MM ^ PM, everything else zero
        val status = frames[0]
        assertEquals(9, status.u(11))
        assertEquals(5, status.u(12))
        assertEquals(0, status.u(13))
        assertEquals(0x6F xor 9 xor 5, status.u(18))
        // Empty names: zero payload, CK = 0x6E / 0x69 / 0x68 / 0x6B
        assertEquals(listOf(0x6E, 0x69, 0x68, 0x6B), frames.drop(1).map { it.u(18) })
        frames.drop(1).forEach { frame -> (2..17).forEach { assertEquals(0, frame.u(it)) } }
    }

    @Test
    fun everyFrameIs20BytesWithValidChecksum() {
        val guidance = BikeNavProtocol.Guidance(250, 911, 6123, BikeNavProtocol.LEFT, 0, 0, "Calle 26 Campo Elías", "Paseo de La Feria")
        BikeNavProtocol.burst(guidance, at(10, 0), chineseSystemLanguage = false).forEach { frame ->
            assertEquals(20, frame.size)
            assertEquals(0x01, frame.u(0))
            assertEquals(0x04, frame.u(19))
            // XOR of all bytes, checksum included, is zero exactly when byte 18 is the XOR of the rest
            assertEquals(0, xorOf(frame))
        }
    }

    @Test
    fun statusFieldsAreBigEndianAtTheirOffsets() {
        val guidance = BikeNavProtocol.Guidance(
            distanceToManeuverMeters = 0x012345,
            durationRemainingSeconds = 0x0ABC,
            distanceRemainingMeters = 0x0A0B0C,
            direction = BikeNavProtocol.SLIGHT_RIGHT,
            roadFlag = BikeNavProtocol.ROAD_ROUNDABOUT,
            roundaboutSector = 3,
            nextRoad = "", currentRoad = "",
        )
        val frame = BikeNavProtocol.statusFrame(guidance, at(10, 0), ended = false)
        assertEquals(listOf(0x01, 0x23, 0x45), (2..4).map { frame.u(it) })
        assertEquals(listOf(0x0A, 0xBC), (5..6).map { frame.u(it) })
        assertEquals(listOf(0x0A, 0x0B, 0x0C), (7..9).map { frame.u(it) })
        assertEquals(7, frame.u(10))
        assertEquals(4, frame.u(14))
        assertEquals(0, frame.u(15))
        assertEquals(3, frame.u(16))
        assertEquals(0, frame.u(17))
    }

    @Test
    fun arrivalTimeIs24HourWithPmFlag() {
        // 23:50 + 20 min = 00:10, AM
        val pastMidnight = BikeNavProtocol.statusFrame(BikeNavProtocol.Guidance.EMPTY.copy(durationRemainingSeconds = 1_200), at(23, 50), ended = false)
        assertEquals(listOf(0, 10, 0), (11..13).map { pastMidnight.u(it) })
        // 12:55 + 10 min = 13:05, PM
        val afternoon = BikeNavProtocol.statusFrame(BikeNavProtocol.Guidance.EMPTY.copy(durationRemainingSeconds = 600), at(12, 55), ended = false)
        assertEquals(listOf(13, 5, 1), (11..13).map { afternoon.u(it) })
    }

    @Test
    fun valuesTooBigForTheirFieldsAreClamped() {
        val frame = BikeNavProtocol.statusFrame(
            BikeNavProtocol.Guidance.EMPTY.copy(durationRemainingSeconds = 100_000, distanceRemainingMeters = 20_000_000),
            at(10, 0), ended = false
        )
        assertEquals(listOf(0xFF, 0xFF), (5..6).map { frame.u(it) })
        assertEquals(listOf(0xFF, 0xFF, 0xFF), (7..9).map { frame.u(it) })
    }

    @Test
    fun roadNamesSplitIntoTwoHalvesThatJoinBack() {
        val name = "Avenida Los Próceres" // 21 UTF-8 bytes, "ó" straddles the halves
        val frames = BikeNavProtocol.roadFrames(0x6B, name, chineseSystemLanguage = false)
        assertEquals(listOf(0x6B, 0x6C), frames.map { it.u(1) })
        val joined = frames.flatMap { it.copyOfRange(3, 18).toList() }.toByteArray()
        assertEquals(name, joined.toString(Charsets.UTF_8).trimEnd('\u0000'))
        assertEquals(1, BikeNavProtocol.roadFrames(0x6D, name, chineseSystemLanguage = true)[0].u(2))
    }

    @Test
    fun longNamesAreCutAtACharacterBoundary() {
        val name = "Avenida Libertador Simón Bolívar Mérida" // 42 bytes
        val prefix = BikeNavProtocol.utf8Prefix(name, 30)
        assertTrue(prefix.size <= 30)
        val decoded = prefix.toString(Charsets.UTF_8)
        assertTrue(name.startsWith(decoded))
        assertTrue(!decoded.contains('�'))
    }

    @Test
    fun turnsMapToDashboardArrows() {
        fun arrow(type: Int) = BikeNavProtocol.direction(listOf(maneuver(type)), 0)
        assertEquals(BikeNavProtocol.RIGHT, arrow(10))
        assertEquals(BikeNavProtocol.LEFT, arrow(15))
        assertEquals(BikeNavProtocol.SLIGHT_RIGHT, arrow(9))
        assertEquals(BikeNavProtocol.SLIGHT_LEFT, arrow(21))
        assertEquals(BikeNavProtocol.SHARP_RIGHT, arrow(11))
        assertEquals(BikeNavProtocol.SHARP_LEFT, arrow(14))
        assertEquals(BikeNavProtocol.UTURN_LEFT, arrow(13))
        assertEquals(BikeNavProtocol.UTURN_RIGHT, arrow(12))
        assertEquals(BikeNavProtocol.STRAIGHT, arrow(8))
        assertEquals(BikeNavProtocol.STRAIGHT, arrow(4))
    }

    @Test
    fun roundaboutExitsUseTheMapboxDegreesConvention() {
        fun roundabout(before: Int, after: Int) = listOf(
            maneuver(26, bearingBefore = before),
            maneuver(27, bearingAfter = after),
        )
        // Heading north: exit east (right), north (straight on), west (left)
        assertEquals(90.0, BikeNavProtocol.roundaboutExitDegrees(roundabout(0, 90), 0)!!, 0.01)
        assertEquals(180.0, BikeNavProtocol.roundaboutExitDegrees(roundabout(0, 0), 0)!!, 0.01)
        assertEquals(270.0, BikeNavProtocol.roundaboutExitDegrees(roundabout(0, 270), 1)!!, 0.01)
        assertEquals(3, BikeNavProtocol.roundaboutSector(90.0))
        assertEquals(6, BikeNavProtocol.roundaboutSector(180.0))
        assertEquals(9, BikeNavProtocol.roundaboutSector(270.0))
        assertEquals(12, BikeNavProtocol.roundaboutSector(360.0))
        assertEquals(0, BikeNavProtocol.roundaboutSector(10.0))
        assertEquals(BikeNavProtocol.RIGHT, BikeNavProtocol.direction(roundabout(0, 90), 0))
        assertEquals(BikeNavProtocol.STRAIGHT, BikeNavProtocol.direction(roundabout(0, 0), 0))
    }

    @Test
    fun guidanceForARealRoute() {
        val route = OpenMapServices.parseValhalla(
            File("src/test/resources/map/valhalla_route.json").readText(Charsets.UTF_8)
        ).first()
        val progress = RouteProgressTracker(route).update(route.points.first())
        val guidance = BikeNavProtocol.guidance(route, progress, "Farmacia")
        // First turn: left onto Calle 26 Campo Elías, while riding Paseo de La Feria
        assertEquals(BikeNavProtocol.LEFT, guidance.direction)
        assertEquals("Calle 26 Campo Elías", guidance.nextRoad)
        assertEquals("Paseo de La Feria", guidance.currentRoad)
        assertEquals(BikeNavProtocol.ROAD_NORMAL, guidance.roadFlag)

        // At the end the next "road" is the destination itself
        val end = RouteProgressTracker(route).let { tracker ->
            route.points.forEach { tracker.update(it) }
            tracker.update(route.points[route.points.size - 2])
        }
        assertEquals("Farmacia", BikeNavProtocol.guidance(route, end, "Farmacia").nextRoad)
    }

    @Test
    fun prefersRealNamesOverRoadReferences() {
        assertEquals("Avenida Los Próceres", BikeNavProtocol.roadName(listOf("T-7", "L-4", "Avenida Los Próceres")))
        assertEquals("T-7", BikeNavProtocol.roadName(listOf("T-7")))
        assertEquals("", BikeNavProtocol.roadName(emptyList()))
    }

    private fun maneuver(type: Int, bearingBefore: Int? = null, bearingAfter: Int? = null) = Maneuver(
        type = type, instruction = "", lengthMeters = 0.0, durationSeconds = 0.0,
        streetNames = emptyList(), beginShapeIndex = 0, roundaboutExitCount = null,
        bearingBefore = bearingBefore, bearingAfter = bearingAfter,
    )
}
