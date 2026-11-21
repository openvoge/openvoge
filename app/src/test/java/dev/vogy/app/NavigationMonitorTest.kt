package dev.vogy.app

import dev.vogy.app.NavigationMonitor.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Rides the real 6.1 km Mérida route (fixture) at 10 m/s with one fix per second. */
class NavigationMonitorTest {

    private val route = OpenMapServices.parseValhalla(
        File("src/test/resources/map/valhalla_route.json").readText(Charsets.UTF_8)
    ).first()

    private val cumulative = DoubleArray(route.points.size).also { d ->
        for (i in 1 until route.points.size) d[i] = d[i - 1] + route.points[i - 1].distanceTo(route.points[i])
    }

    private var clock = 0L

    private fun fix(point: GeoPoint, speed: Double = 10.0, accuracy: Double = 5.0) =
        NavigationMonitor.Fix(point, accuracy, speed, clock).also { clock += 1_000 }

    /** The point [meters] along the route, and the route's heading there. */
    private fun alongRoute(meters: Double): Pair<GeoPoint, Double> {
        val i = cumulative.indexOfLast { it <= meters }.coerceIn(0, route.points.size - 2)
        val a = route.points[i]
        val b = route.points[i + 1]
        val t = ((meters - cumulative[i]) / (cumulative[i + 1] - cumulative[i])).coerceIn(0.0, 1.0)
        val heading = Math.toDegrees(atan2((b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat))
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)) to heading
    }

    private fun moved(from: GeoPoint, headingDegrees: Double, meters: Double): GeoPoint {
        val h = Math.toRadians(headingDegrees)
        return GeoPoint(
            from.lat + cos(h) * meters / 111_195.0,
            from.lon + sin(h) * meters / (111_195.0 * cos(Math.toRadians(from.lat))),
        )
    }

    /** Rides from [fromMeters] to [toMeters] along the route, one fix every 10 m. */
    private fun ride(monitor: NavigationMonitor, fromMeters: Double, toMeters: Double): List<Status> =
        generateSequence(fromMeters) { it + 10.0 }.takeWhile { it <= toMeters }
            .map { monitor.onFix(fix(alongRoute(it).first)).status }.toList()

    @Test
    fun ridingToTheEndAndStoppingFinishes() {
        val monitor = NavigationMonitor(route)
        val statuses = ride(monitor, 0.0, cumulative.last())
        assertFalse(Status.REROUTE in statuses)
        assertEquals(Status.ARRIVED, statuses.last())
        // Parked at the destination
        val end = route.points.last()
        val afterStop = (1..5).map { monitor.onFix(fix(end, speed = 0.0)).status }
        assertEquals(Status.FINISHED, afterStop.last())
    }

    @Test
    fun ridingPastTheDestinationReroutesBackToIt() {
        val monitor = NavigationMonitor(route)
        val onRoute = ride(monitor, 0.0, cumulative.last())
        assertEquals(Status.ARRIVED, onRoute.last())
        // Didn't stop: carried on straight for 150 m
        val (end, heading) = alongRoute(cumulative.last() - 1.0)
        val past = (1..15).map { monitor.onFix(fix(moved(end, heading, it * 10.0))).status }
        assertFalse("must not finish while riding away", Status.FINISHED in past)
        assertTrue("must reroute back", Status.REROUTE in past)
    }

    @Test
    fun passingThePlaceAndStoppingFurtherOnStillReroutes() {
        val monitor = NavigationMonitor(route)
        ride(monitor, 0.0, cumulative.last())
        val (end, heading) = alongRoute(cumulative.last() - 1.0)
        // Rode 60 m past at speed, then pulled over 100 m past the place to look around
        (1..6).forEach { monitor.onFix(fix(moved(end, heading, it * 10.0))) }
        val stopped = (1..6).map { monitor.onFix(fix(moved(end, heading, 100.0), speed = 0.0)).status }
        assertFalse("parked 100 m away is not arrival", Status.FINISHED in stopped)
        assertTrue(Status.REROUTE in stopped)
    }

    @Test
    fun missingATurnReroutesSoonAfter() {
        val monitor = NavigationMonitor(route)
        val turnAt = cumulative[route.maneuvers[2].beginShapeIndex]
        assertFalse(Status.REROUTE in ride(monitor, 0.0, turnAt))
        // Straight on instead of turning
        val (corner, heading) = alongRoute(turnAt - 1.0)
        val statuses = (1..15).map { monitor.onFix(fix(moved(corner, heading, it * 10.0))).status }
        val firstReroute = statuses.indexOf(Status.REROUTE)
        assertTrue("never rerouted", firstReroute >= 0)
        // Not before being clearly off the route (35 m), and within a few seconds of that
        assertTrue("rerouted too early at ${firstReroute + 1} fixes", firstReroute + 1 >= 4)
        assertTrue("rerouted too late at ${firstReroute + 1} fixes", firstReroute + 1 <= 8)
    }

    @Test
    fun gpsNoiseOnTheRouteNeverReroutes() {
        val monitor = NavigationMonitor(route)
        var side = 1.0
        generateSequence(0.0) { it + 10.0 }.takeWhile { it <= cumulative.last() - 200 }.forEach { meters ->
            val (point, heading) = alongRoute(meters)
            side = -side
            val noisy = moved(point, heading + 90.0, 12.0 * side)
            val status = monitor.onFix(fix(noisy, accuracy = 12.0)).status
            assertEquals("at $meters m", Status.GUIDING, status)
        }
    }

    @Test
    fun creepingIntoAParkingSpotFinishes() {
        val monitor = NavigationMonitor(route)
        ride(monitor, 0.0, cumulative.last() - 20.0)
        val end = route.points.last()
        val statuses = (1..6).map { monitor.onFix(fix(end, speed = 1.0)).status }
        assertEquals(Status.FINISHED, statuses.last())
        assertFalse(Status.REROUTE in statuses)
    }

    @Test
    fun arrivedAndCirclingNearbyFinishesAfterTheTimeout() {
        val monitor = NavigationMonitor(route)
        ride(monitor, 0.0, cumulative.last())
        val end = route.points.last()
        // Riding around within 20 m of the place, never stopping
        val statuses = (0 until 35).map { i -> monitor.onFix(fix(moved(end, i * 40.0, 20.0), speed = 4.0)).status }
        assertFalse(Status.REROUTE in statuses)
        assertEquals(Status.FINISHED, statuses.last())
    }

    @Test
    fun farFromTheDestinationIsNeverArrival() {
        val monitor = NavigationMonitor(route)
        // The route's last stretch, 200 m out: progress along the route is nearly done, but the rider isn't there
        val status = monitor.onFix(fix(alongRoute(cumulative.last() - 200.0).first)).status
        assertEquals(Status.GUIDING, status)
    }
}
