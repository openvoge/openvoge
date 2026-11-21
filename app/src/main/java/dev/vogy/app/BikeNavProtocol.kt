package dev.vogy.app

import java.util.Calendar
import kotlin.math.abs

/**
 * Turn-by-turn frames for the VOGE dashboard (TFT), byte for byte as the official app sends them
 * over the modern telemetry characteristic. Every frame is 20 bytes: `01 <cmd> … <xor> 04`, where
 * the xor covers all 20 bytes with byte 18 still zero. Numbers are big-endian; distances are always
 * meters (the bike's km/mi setting only affects its own display).
 *
 * - A guidance update is a burst of five frames, [FRAME_SPACING_MS] apart, at most one burst
 *   every [MIN_BURST_INTERVAL_MS]: status (0x6A), next road (0x6B + 0x6C), current road (0x6D + 0x6E).
 * - Navigation starts with an empty burst and ends with a single status frame whose
 *   "navigation off" byte is set. There is no handshake: the dashboard just follows the frames.
 */
object BikeNavProtocol {

    const val FRAME_SPACING_MS = 100L
    const val MIN_BURST_INTERVAL_MS = 800L

    private const val STATUS = 0x6A
    private const val NEXT_ROAD = 0x6B
    private const val CURRENT_ROAD = 0x6D
    /** Each road name travels as two 15-byte halves of its UTF-8 bytes. */
    private const val NAME_HALF_BYTES = 15
    private const val NAME_MAX_BYTES = 2 * NAME_HALF_BYTES

    // Arrows the dashboard can draw (status byte 10)
    const val STRAIGHT = 1
    const val UTURN_LEFT = 2
    const val UTURN_RIGHT = 3
    const val LEFT = 4
    const val RIGHT = 5
    const val SLIGHT_LEFT = 6
    const val SLIGHT_RIGHT = 7
    const val SHARP_LEFT = 8
    const val SHARP_RIGHT = 9

    // Road flag (status byte 14)
    const val ROAD_NORMAL = 0
    const val ROAD_ROUNDABOUT = 4

    // Valhalla maneuver types this mapping cares about
    private const val ROUNDABOUT_ENTER = 26
    private const val ROUNDABOUT_EXIT = 27
    private val DESTINATION_TYPES = 4..6

    /** What the dashboard shows for one update. */
    data class Guidance(
        val distanceToManeuverMeters: Int,
        val durationRemainingSeconds: Int,
        val distanceRemainingMeters: Int,
        val direction: Int,
        val roadFlag: Int,
        /** Where the roundabout exit is, 1–12 in 30° steps; 0 when not at a roundabout. */
        val roundaboutSector: Int,
        val nextRoad: String,
        val currentRoad: String,
    ) {
        companion object {
            /** The official app's "nothing yet" values, used for the opening burst and the end frame. */
            val EMPTY = Guidance(0, 0, 0, 0, ROAD_NORMAL, 0, "", "")
        }
    }

    // ── Frames ───────────────────────────────────────────────────────────────

    /** One full update: status, next road, current road. Send [FRAME_SPACING_MS] apart. */
    fun burst(guidance: Guidance, now: Calendar, chineseSystemLanguage: Boolean): List<ByteArray> =
        listOf(statusFrame(guidance, now, ended = false)) +
            roadFrames(NEXT_ROAD, guidance.nextRoad, chineseSystemLanguage) +
            roadFrames(CURRENT_ROAD, guidance.currentRoad, chineseSystemLanguage)

    /** Tells the dashboard navigation is over. Sent once, immediately. */
    fun endFrame(now: Calendar): ByteArray = statusFrame(Guidance.EMPTY, now, ended = true)

    fun statusFrame(guidance: Guidance, now: Calendar, ended: Boolean): ByteArray {
        val frame = newFrame(STATUS)
        putUInt24(frame, 2, guidance.distanceToManeuverMeters)
        putUInt16(frame, 5, guidance.durationRemainingSeconds)
        putUInt24(frame, 7, guidance.distanceRemainingMeters)
        frame[10] = guidance.direction.toByte()
        // Arrival time: 24-hour hour, minute, and a separate PM flag
        val arrival = (now.clone() as Calendar).apply { add(Calendar.SECOND, guidance.durationRemainingSeconds) }
        frame[11] = arrival.get(Calendar.HOUR_OF_DAY).toByte()
        frame[12] = arrival.get(Calendar.MINUTE).toByte()
        frame[13] = (if (arrival.get(Calendar.AM_PM) == Calendar.PM) 1 else 0).toByte()
        frame[14] = guidance.roadFlag.toByte()
        frame[15] = (if (ended) 1 else 0).toByte()
        frame[16] = guidance.roundaboutSector.toByte()
        return withChecksum(frame)
    }

    /** A road name as two frames ([firstCommand] and the next one), 15 UTF-8 bytes each. */
    fun roadFrames(firstCommand: Int, name: String, chineseSystemLanguage: Boolean): List<ByteArray> {
        val bytes = utf8Prefix(name, NAME_MAX_BYTES)
        return (0..1).map { half ->
            val frame = newFrame(firstCommand + half)
            frame[2] = (if (chineseSystemLanguage) 1 else 0).toByte()
            val from = half * NAME_HALF_BYTES
            val to = minOf(bytes.size, from + NAME_HALF_BYTES)
            // Halves split raw bytes like the official app does; the dashboard joins them back
            if (from < to) bytes.copyInto(frame, destinationOffset = 3, startIndex = from, endIndex = to)
            withChecksum(frame)
        }
    }

    /** The UTF-8 bytes of [text], cut to at most [maxBytes] without splitting a character. */
    fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
        val all = text.toByteArray(Charsets.UTF_8)
        if (all.size <= maxBytes) return all
        var end = maxBytes
        // Back off continuation bytes (10xxxxxx) so the last character stays whole
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
        return all.copyOf(end)
    }

    private fun newFrame(command: Int) = ByteArray(20).apply {
        this[0] = 0x01
        this[1] = command.toByte()
        this[19] = 0x04
    }

    private fun withChecksum(frame: ByteArray): ByteArray {
        frame[18] = 0
        var xor = 0
        for (b in frame) xor = xor xor (b.toInt() and 0xFF)
        frame[18] = xor.toByte()
        return frame
    }

    private fun putUInt24(frame: ByteArray, at: Int, value: Int) {
        val v = value.coerceIn(0, 0xFFFFFF)
        frame[at] = (v shr 16).toByte()
        frame[at + 1] = (v shr 8).toByte()
        frame[at + 2] = v.toByte()
    }

    private fun putUInt16(frame: ByteArray, at: Int, value: Int) {
        val v = value.coerceIn(0, 0xFFFF)
        frame[at] = (v shr 8).toByte()
        frame[at + 1] = v.toByte()
    }

    // ── From a route to what the dashboard shows ────────────────────────────

    fun guidance(route: MotoRoute, progress: NavProgress, destinationName: String): Guidance {
        val maneuvers = route.maneuvers
        val next = maneuvers[progress.nextManeuverIndex]
        val current = maneuvers[(progress.nextManeuverIndex - 1).coerceAtLeast(0)]
        val roundaboutDegrees = roundaboutExitDegrees(maneuvers, progress.nextManeuverIndex)
        return Guidance(
            distanceToManeuverMeters = progress.distanceToManeuverMeters.toInt(),
            durationRemainingSeconds = progress.durationRemainingSeconds.toInt(),
            distanceRemainingMeters = progress.distanceRemainingMeters.toInt(),
            direction = direction(maneuvers, progress.nextManeuverIndex),
            roadFlag = if (roundaboutDegrees != null) ROAD_ROUNDABOUT else ROAD_NORMAL,
            roundaboutSector = roundaboutDegrees?.let(::roundaboutSector) ?: 0,
            nextRoad = if (next.type in DESTINATION_TYPES) destinationName else roadName(next.streetNames),
            currentRoad = roadName(current.streetNames),
        )
    }

    /** The dashboard arrow for maneuver [index] (Valhalla types → the 1–9 codes the TFT draws). */
    fun direction(maneuvers: List<Maneuver>, index: Int): Int {
        val maneuver = maneuvers[index]
        return when (maneuver.type) {
            9, 18, 20, 23, 37 -> SLIGHT_RIGHT   // slight right, ramp/exit/keep/merge right
            16, 19, 21, 24, 38 -> SLIGHT_LEFT
            10 -> RIGHT
            15 -> LEFT
            11 -> SHARP_RIGHT
            14 -> SHARP_LEFT
            12 -> UTURN_RIGHT
            13 -> UTURN_LEFT
            ROUNDABOUT_ENTER, ROUNDABOUT_EXIT ->
                roundaboutExitDegrees(maneuvers, index)?.let { arrowForTurn(180.0 - it) } ?: STRAIGHT
            else -> STRAIGHT                    // start, continue, merge, arrive…
        }
    }

    /**
     * How far around the roundabout the exit is, the way Mapbox reports it (90 ≈ right,
     * 180 = straight on, 270 ≈ left, 360 = full turn), from the heading entering the
     * roundabout and the heading leaving it. Null when [index] isn't a roundabout maneuver.
     */
    fun roundaboutExitDegrees(maneuvers: List<Maneuver>, index: Int): Double? {
        val maneuver = maneuvers[index]
        val (enter, exit) = when (maneuver.type) {
            ROUNDABOUT_ENTER -> maneuver to maneuvers.drop(index + 1).firstOrNull { it.type == ROUNDABOUT_EXIT }
            ROUNDABOUT_EXIT -> maneuvers.take(index).lastOrNull { it.type == ROUNDABOUT_ENTER } to maneuver
            else -> return null
        }
        val before = enter?.bearingBefore ?: return null
        val after = exit?.bearingAfter ?: return null
        val turnClockwise = normalizeTurn((after - before).toDouble())
        return (180.0 - turnClockwise).let { if (it <= 0.0) it + 360.0 else it }
    }

    /** The official app's 30° buckets: 15–45° → 1 … 345–360° → 12, 0 below 15°. */
    fun roundaboutSector(exitDegrees: Double): Int =
        if (exitDegrees < 15.0) 0 else (((exitDegrees - 15.0) / 30.0).toInt() + 1).coerceAtMost(12)

    /** Arrow for a heading change in degrees, clockwise positive. */
    private fun arrowForTurn(turnClockwise: Double): Int {
        val turn = normalizeTurn(turnClockwise)
        val size = abs(turn)
        return when {
            size < 20 -> STRAIGHT
            size < 60 -> if (turn > 0) SLIGHT_RIGHT else SLIGHT_LEFT
            size < 120 -> if (turn > 0) RIGHT else LEFT
            size < 165 -> if (turn > 0) SHARP_RIGHT else SHARP_LEFT
            else -> UTURN_LEFT  // right-hand traffic turns back to the left
        }
    }

    /** -180 < turn ≤ 180. */
    private fun normalizeTurn(degrees: Double): Double {
        var turn = degrees % 360.0
        if (turn > 180.0) turn -= 360.0
        if (turn <= -180.0) turn += 360.0
        return turn
    }

    /** "Avenida Los Próceres" over its reference "T-7" when Valhalla lists both. */
    fun roadName(streetNames: List<String>): String =
        streetNames.firstOrNull { ' ' in it } ?: streetNames.firstOrNull().orEmpty()
}
