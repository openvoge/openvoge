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
