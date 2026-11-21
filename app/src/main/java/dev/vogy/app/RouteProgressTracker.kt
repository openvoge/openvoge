package dev.vogy.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the rider is along the route after one position fix. */
data class NavProgress(
    /** Index into [MotoRoute.maneuvers] of the next maneuver to perform. */
    val nextManeuverIndex: Int,
    val distanceToManeuverMeters: Double,
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
    /** Distance from the fix to the route line: large values mean the rider left the route. */
    val offRouteMeters: Double,
    /** The fix moved onto the route line, and the route's heading there (degrees from north). */
    val snapped: GeoPoint,
    val routeBearing: Double,
    val arrived: Boolean,
)

/**
 * Follows a rider along a [MotoRoute]: each fix is snapped onto the route line, searching just
 * behind and ahead of the previous position so a route that doubles back never makes the
 * progress jump. Pure geometry, no Android, so it is unit-tested with real Valhalla routes.
 */
class RouteProgressTracker(val route: MotoRoute) {

    companion object {
        /** Closer than this to the end of the route counts as arrived. */
        const val ARRIVAL_METERS = 25.0
        private const val SEARCH_BACK_SEGMENTS = 3
        private const val SEARCH_AHEAD_SEGMENTS = 60
        /** Beyond this the local window probably lost the rider (GPS gap): search the whole route. */
        private const val WINDOW_LOST_METERS = 60.0
        /** GPS jitter can pull the fix back a few meters past a turn: don't show that turn again. */
        private const val PASSED_TURN_HYSTERESIS_METERS = 20.0
        private const val METERS_PER_DEGREE = 111_195.0
    }

    private class Snap(val segment: Int, val t: Double, val distance: Double)

    private val points = route.points
    private val cumulative = DoubleArray(points.size).also { distances ->
        for (i in 1 until points.size) distances[i] = distances[i - 1] + points[i - 1].distanceTo(points[i])
    }
    private val maneuverStarts = route.maneuvers.map { cumulative[it.beginShapeIndex.coerceIn(points.indices)] }
    private var segment = 0
    private var lastNext = 0

    val totalMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    fun update(position: GeoPoint): NavProgress {
        require(points.size >= 2 && route.maneuvers.isNotEmpty()) { "Route has no geometry" }
        val lastSegment = points.size - 2
        var best = snap(position, max(0, segment - SEARCH_BACK_SEGMENTS), min(lastSegment, segment + SEARCH_AHEAD_SEGMENTS))
        if (best.distance > WINDOW_LOST_METERS) {
            val anywhere = snap(position, 0, lastSegment)
            if (anywhere.distance < best.distance) best = anywhere
        }
        segment = best.segment

        val start = points[best.segment]
        val end = points[best.segment + 1]
        val along = cumulative[best.segment] + best.t * (cumulative[best.segment + 1] - cumulative[best.segment])
        val remaining = (totalMeters - along).coerceAtLeast(0.0)

        // The next maneuver is the first one that starts ahead of the rider; the last is the destination
        val ahead = maneuverStarts.indexOfFirst { it > along + 1.0 }.let { if (it < 0) route.maneuvers.lastIndex else it }
        val justPassed = lastNext > 0 && along > maneuverStarts[lastNext - 1] - PASSED_TURN_HYSTERESIS_METERS
        val next = if (ahead < lastNext && justPassed) lastNext else ahead
        lastNext = next
        val current = (next - 1).coerceAtLeast(0)
        val legStart = maneuverStarts[current]
        val legEnd = maneuverStarts[next]
        val legLeft = if (legEnd > legStart) ((legEnd - along) / (legEnd - legStart)).coerceIn(0.0, 1.0) else 0.0
        val duration = route.maneuvers[current].durationSeconds * legLeft +
            route.maneuvers.subList(next, route.maneuvers.size).sumOf { it.durationSeconds }

        return NavProgress(
            nextManeuverIndex = next,
            distanceToManeuverMeters = (legEnd - along).coerceAtLeast(0.0),
            distanceRemainingMeters = remaining,
            durationRemainingSeconds = if (remaining < ARRIVAL_METERS) 0.0 else duration,
            offRouteMeters = best.distance,
            snapped = GeoPoint(start.lat + best.t * (end.lat - start.lat), start.lon + best.t * (end.lon - start.lon)),
            routeBearing = bearing(start, end),
            arrived = remaining < ARRIVAL_METERS,
        )
    }

    /** Closest point to [p] on segments [from]..[to], in a local flat projection (fine at street scale). */
    private fun snap(p: GeoPoint, from: Int, to: Int): Snap {
        var best = Snap(from, 0.0, Double.MAX_VALUE)
        for (i in from..to) {
            val a = points[i]
            val b = points[i + 1]
            val cosLat = cos(Math.toRadians(a.lat))
            val bx = (b.lon - a.lon) * cosLat * METERS_PER_DEGREE
            val by = (b.lat - a.lat) * METERS_PER_DEGREE
            val px = (p.lon - a.lon) * cosLat * METERS_PER_DEGREE
            val py = (p.lat - a.lat) * METERS_PER_DEGREE
            val lengthSquared = bx * bx + by * by
            val t = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < best.distance) best = Snap(i, t, distance)
        }
        return best
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val x = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val y = b.lat - a.lat
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }
}
