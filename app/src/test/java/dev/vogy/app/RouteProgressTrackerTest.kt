package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteProgressTrackerTest {

    /** Real 6.1 km motorcycle route across Mérida, 8 maneuvers. */
    private val route = OpenMapServices.parseValhalla(
        File("src/test/resources/map/valhalla_route.json").readText(Charsets.UTF_8)
    ).first()

    @Test
    fun atTheStartTheFirstTurnIsNext() {
        val progress = RouteProgressTracker(route).update(route.points.first())
        assertEquals(1, progress.nextManeuverIndex)
        assertEquals(route.maneuvers[0].lengthMeters, progress.distanceToManeuverMeters, 15.0)
        assertEquals(route.lengthMeters, progress.distanceRemainingMeters, route.lengthMeters * 0.02)
        assertEquals(route.durationSeconds, progress.durationRemainingSeconds, route.durationSeconds * 0.02)
        assertFalse(progress.arrived)
    }

    @Test
    fun ridingTheRouteOnlyMovesForward() {
        val tracker = RouteProgressTracker(route)
        var lastRemaining = Double.MAX_VALUE
        var lastManeuver = 0
        // Ride every shape point with ~4 m of GPS noise; along a north-south street that noise
        // also moves the fix back and forth along the road, so allow that much backwards jitter
        route.points.forEachIndexed { i, point ->
            val noisy = GeoPoint(point.lat + if (i % 2 == 0) 0.00004 else -0.00004, point.lon)
            val progress = tracker.update(noisy)
            assertTrue("remaining went up at point $i", progress.distanceRemainingMeters <= lastRemaining + 10.0)
            assertTrue("maneuver went back at point $i", progress.nextManeuverIndex >= lastManeuver)
            assertTrue("noise read as off-route at point $i", progress.offRouteMeters < 10.0)
            lastRemaining = progress.distanceRemainingMeters
            lastManeuver = progress.nextManeuverIndex
        }
        assertEquals(route.maneuvers.lastIndex, lastManeuver)
    }

    @Test
    fun justPastATurnTheFollowingOneIsNext() {
        val tracker = RouteProgressTracker(route)
        val turn = route.maneuvers[2]
        // Ride up to the turn, then two shape points past it
        for (i in 0..turn.beginShapeIndex + 2) tracker.update(route.points[i])
        val progress = tracker.update(route.points[turn.beginShapeIndex + 2])
        assertEquals(3, progress.nextManeuverIndex)
    }

    @Test
    fun theEndOfTheRouteIsArrival() {
        val tracker = RouteProgressTracker(route)
        route.points.forEach { tracker.update(it) }
        val progress = tracker.update(route.points.last())
        assertTrue(progress.arrived)
        assertEquals(0.0, progress.durationRemainingSeconds, 0.0)
    }

    @Test
    fun leavingTheRouteIsMeasured() {
        val tracker = RouteProgressTracker(route)
        val start = route.points.first()
        // ~220 m north-west of the start, nowhere near the first leg
        val progress = tracker.update(GeoPoint(start.lat + 0.0015, start.lon - 0.0015))
        assertTrue(progress.offRouteMeters > 100.0)
    }

    @Test
    fun estimatesCountDownBetweenFixes() {
        val tracker = RouteProgressTracker(route)
        val fix = tracker.update(route.points.first())
        // One second at ~36 km/h
        val estimate = tracker.estimate(10.0)
        assertEquals(fix.nextManeuverIndex, estimate.nextManeuverIndex)
        assertEquals(fix.distanceToManeuverMeters - 10.0, estimate.distanceToManeuverMeters, 0.5)
        assertEquals(fix.distanceRemainingMeters - 10.0, estimate.distanceRemainingMeters, 0.5)
        assertTrue(estimate.durationRemainingSeconds < fix.durationRemainingSeconds)
        // Estimates are relative to the last fix, not cumulative
        assertEquals(estimate.distanceRemainingMeters, tracker.estimate(10.0).distanceRemainingMeters, 0.01)
    }

    @Test
    fun estimatesNeverPassTheNextTurn() {
        val tracker = RouteProgressTracker(route)
        val fix = tracker.update(route.points.first())
        // Far more than the ~240 m to the first turn: a stale speed must not skip the turn
        val estimate = tracker.estimate(2_000.0)
        assertEquals(fix.nextManeuverIndex, estimate.nextManeuverIndex)
        assertEquals(3.0, estimate.distanceToManeuverMeters, 0.5)
        // A real fix past the turn does move on
        val turn = route.maneuvers[fix.nextManeuverIndex]
        for (i in 0..turn.beginShapeIndex + 2) tracker.update(route.points[i])
        assertTrue(tracker.estimate(0.0).nextManeuverIndex > fix.nextManeuverIndex)
    }

    @Test
    fun estimatedPositionStaysOnTheRoute() {
        val tracker = RouteProgressTracker(route)
        tracker.update(route.points.first())
        val estimate = tracker.estimate(50.0)
        // The estimated point is a point of the route: snapping it back gives the same progress
        val check = RouteProgressTracker(route).update(estimate.snapped)
        assertTrue(check.offRouteMeters < 1.0)
        assertEquals(estimate.distanceRemainingMeters, check.distanceRemainingMeters, 1.0)
    }

    @Test
    fun snapsOntoTheRouteLine() {
        val tracker = RouteProgressTracker(route)
        val a = route.points[3]
        val b = route.points[4]
        val midpoint = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
        val progress = tracker.update(midpoint)
        assertTrue(progress.snapped.distanceTo(midpoint) < 1.0)
        assertTrue(progress.routeBearing in 0.0..360.0)
    }
}
