package dev.vogy.app

import kotlin.math.max

/**
 * Decides, fix by fix, what navigation should do: keep guiding, reroute, or call it arrived.
 * Pure Kotlin on top of [RouteProgressTracker], so the rules are unit-tested with real routes.
 *
 * - **Off route:** a few fixes in a row too far from the route line mean a missed turn: reroute.
 * - **Arrival** is being close to the end of the route, measured straight, not along the route
 *   (riding past the end along another road must not count as arriving).
 * - **Arrived, then confirmed or overshot:** stopping near the destination finishes. Riding on
 *   and away from it means the rider missed the place: reroute back to it, like any map app.
 */
class NavigationMonitor(val route: MotoRoute) {

    companion object {
        const val OFF_ROUTE_MIN_METERS = 35.0
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVAL_RADIUS_METERS = 30.0
        /** Beyond the arrival radius by this much, still moving: the rider passed the place. */
        const val OVERSHOOT_METERS = 50.0
        const val STOPPED_SPEED_MPS = 2.0
        /** Stopped (or barely moving) near the destination this long: done. */
        const val ARRIVAL_CONFIRM_MS = 4_000L
        /** Arrived but neither stopped nor left: done anyway. */
        const val ARRIVAL_TIMEOUT_MS = 30_000L
        /** GPS accuracy counted in the arrival distances, at most. */
        private const val MAX_ACCURACY_SLACK_METERS = 25.0
    }

    /** One position fix: where, how sure, how fast, when (ms, any monotonic clock). */
    data class Fix(val point: GeoPoint, val accuracyMeters: Double, val speedMps: Double, val timeMs: Long)

    enum class Status {
        /** On the route: guide normally. */
        GUIDING,
        /** Left the route, or rode past the destination: get a new route from here. */
        REROUTE,
        /** Near the destination, waiting to see whether the rider stops or rides on. */
        ARRIVED,
        /** Arrival confirmed: navigation is over. */
        FINISHED,
    }

    data class Result(val progress: NavProgress, val status: Status)

    val tracker = RouteProgressTracker(route)
    private val destination = route.points.last()
    private var offRouteFixes = 0
    private var arrivedAt: Long? = null
    private var slowSince: Long? = null

    /** Whether the last fix was being guided normally: estimates between fixes only make sense then. */
    var isGuiding: Boolean = true
        private set

    fun onFix(fix: Fix): Result {
        val progress = tracker.update(fix.point)
        val toDestination = fix.point.distanceTo(destination)
        val slack = fix.accuracyMeters.coerceIn(0.0, MAX_ACCURACY_SLACK_METERS)
        val status = when (val since = arrivedAt) {
            null -> beforeArrival(fix, progress, toDestination, slack)
            else -> afterArrival(fix, since, toDestination, slack)
        }
        isGuiding = status == Status.GUIDING
        return Result(progress, status)
    }

    private fun beforeArrival(fix: Fix, progress: NavProgress, toDestination: Double, slack: Double): Status {
        if (toDestination <= ARRIVAL_RADIUS_METERS + slack) {
            arrivedAt = fix.timeMs
            offRouteFixes = 0
            slowSince = if (fix.speedMps < STOPPED_SPEED_MPS) fix.timeMs else null
            return Status.ARRIVED
        }
        val limit = max(OFF_ROUTE_MIN_METERS, fix.accuracyMeters * 2.0)
        offRouteFixes = if (progress.offRouteMeters > limit) offRouteFixes + 1 else 0
        return if (offRouteFixes >= OFF_ROUTE_FIXES) Status.REROUTE else Status.GUIDING
    }

    private fun afterArrival(fix: Fix, since: Long, toDestination: Double, slack: Double): Status {
        if (toDestination > ARRIVAL_RADIUS_METERS + OVERSHOOT_METERS + slack) {
            // Rode past the place (even if now stopped further on to look around): start over towards it
            arrivedAt = null
            slowSince = null
            return Status.REROUTE
        }
        // Only a stop near the place counts as getting there
        val near = toDestination <= ARRIVAL_RADIUS_METERS + slack
        slowSince = if (near && fix.speedMps < STOPPED_SPEED_MPS) slowSince ?: fix.timeMs else null
        val stoppedLongEnough = slowSince?.let { fix.timeMs - it >= ARRIVAL_CONFIRM_MS } ?: false
        return if (stoppedLongEnough || fix.timeMs - since >= ARRIVAL_TIMEOUT_MS) Status.FINISHED else Status.ARRIVED
    }
}
