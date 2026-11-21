package dev.vogy.app

import android.content.Context
import android.content.res.Resources
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Turn-by-turn navigation, alive for the whole process so it keeps going with the screen off.
 *
 * Fixes come from [NavigationService] (real GPS) or from the built-in simulation, which rides
 * the route by itself so the dashboard can be tested without moving. Each fix updates the
 * rider's progress, reroutes after leaving the route, detects arrival and feeds the bike's
 * dashboard through [dashboard].
 */
object NavigationSession {

    /** Where navigation frames go: the bike link, while there is one. */
    interface Dashboard {
        /** A bike is connected, whether or not its link carries navigation. */
        val isConnected: Boolean
        /** Connected on the link the dashboard listens to for navigation. */
        val isReady: Boolean
        fun send(frames: List<ByteArray>)
    }

    data class State(
        val destination: Place,
        val route: MotoRoute,
        val progress: NavProgress?,
        /** The latest fix moved onto the route, for the map's arrow. */
        val position: Location?,
        val simulated: Boolean,
        val rerouting: Boolean,
        val arrived: Boolean,
    ) {
        val nextManeuver: Maneuver? get() = progress?.let { route.maneuvers.getOrNull(it.nextManeuverIndex) }
    }

    private const val LOG_TAG = "NAV"
    private const val SIMULATION_SPEED_MPS = 12.5   // 45 km/h
    private const val SIMULATION_TICK_MS = 1_000L
    private const val OFF_ROUTE_MIN_METERS = 35.0
    private const val OFF_ROUTE_FIXES = 3
    private const val REROUTE_COOLDOWN_MS = 15_000L
    private const val ARRIVED_SHOWN_MS = 8_000L
    /**
     * The dashboard gets a burst every second whether or not a fix arrived, like the official app
     * (Mapbox reports progress every second). Without it the TFT drops the arrow between slow fixes.
     */
    private const val GUIDANCE_TICK_MS = 1_000L
    /** Between fixes progress is estimated from the last speed, for at most this long. */
    private const val MAX_DEAD_RECKONING_SECONDS = 3.0
    private const val MIN_MOVING_SPEED_MPS = 1.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Set by whoever owns the bike link; frames are dropped while it's null or not ready. */
    var dashboard: Dashboard? = null

    private var tracker: RouteProgressTracker? = null
    private var language = "en-US"
    private var simulationJob: Job? = null
    private var rerouteJob: Job? = null
    private var finishJob: Job? = null
    private var tickerJob: Job? = null
    /** The last real (or simulated) fix and when it arrived, for estimating progress until the next. */
    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var lastBurstAt = 0L
    /** The dashboard got its opening burst on the current link. */
    private var dashboardPrimed = false

    val isActive: Boolean get() = _state.value?.arrived == false

    fun start(context: Context, destination: Place, route: MotoRoute, simulate: Boolean) {
        stopInternal(sendEnd = isActive)
        language = RouteFormat.valhallaLanguage(context.resources.configuration.locales[0])
        tracker = RouteProgressTracker(route)
        offRouteFixes = 0
        lastRerouteAt = 0
        lastBurstAt = 0
        dashboardPrimed = false
        lastFix = null
        _state.value = State(destination, route, progress = null, position = null, simulated = simulate, rerouting = false, arrived = false)
        primeDashboard()
        ContextCompat.startForegroundService(context, NavigationService.startIntent(context, simulate))
        if (simulate) simulationJob = scope.launch { simulate(route) }
        tickerJob = scope.launch {
            while (true) {
                delay(GUIDANCE_TICK_MS)
                tick()
            }
        }
    }

    /** The rider ended navigation. */
    fun stop(context: Context) {
        stopInternal(sendEnd = isActive)
        context.stopService(NavigationService.stopIntent(context))
    }

    /** A real GPS fix from [NavigationService]. Ignored while simulating. */
    fun onLocation(location: Location) {
        val current = _state.value ?: return
        if (current.simulated || current.arrived) return
        handleFix(location)
    }

    private fun handleFix(location: Location) {
        val current = _state.value ?: return
        val routeTracker = tracker ?: return
        val progress = routeTracker.update(GeoPoint(location.latitude, location.longitude))

        if (progress.arrived) {
            finish(current.copy(progress = progress, position = onRoute(location, progress)))
            return
        }

        lastFix = location
        lastFixAt = SystemClock.elapsedRealtime()
        val offRouteLimit = maxOf(OFF_ROUTE_MIN_METERS, location.accuracy * 2.0)
        offRouteFixes = if (progress.offRouteMeters > offRouteLimit) offRouteFixes + 1 else 0
        val shownPosition = if (offRouteFixes == 0) onRoute(location, progress) else location
        _state.value = current.copy(progress = progress, position = shownPosition)

        if (offRouteFixes >= OFF_ROUTE_FIXES) reroute(location)
        // The dashboard is fed by the steady tick, not by fixes: see GUIDANCE_TICK_MS
    }

    /**
     * Once a second: move the progress on from the last fix at the rider's speed (so distances
     * count down smoothly between fixes) and send the dashboard its burst.
     */
    private fun tick() {
        val current = _state.value ?: return
        if (current.arrived) return
        var progress = current.progress
        val fix = lastFix
        val routeTracker = tracker
        if (progress != null && fix != null && routeTracker != null && offRouteFixes == 0 && !current.rerouting) {
            val seconds = ((SystemClock.elapsedRealtime() - lastFixAt) / 1000.0).coerceAtMost(MAX_DEAD_RECKONING_SECONDS)
            val speed = if (fix.hasSpeed()) fix.speed.toDouble() else 0.0
            if (speed >= MIN_MOVING_SPEED_MPS && seconds > 0.0) {
                // Estimates never finish the route or pass a turn: only real fixes do that
                progress = routeTracker.estimate(speed * seconds)
                _state.value = current.copy(progress = progress, position = onRoute(fix, progress))
            }
        }
        sendGuidance(current.route, progress, current.destination)
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    /** One burst for the dashboard; before the first fix it keeps the opening (empty) guidance. */
    private fun sendGuidance(route: MotoRoute, progress: NavProgress?, destination: Place) {
        val link = dashboard?.takeIf { it.isReady } ?: run {
            dashboardPrimed = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBurstAt < BikeNavProtocol.MIN_BURST_INTERVAL_MS) return
        if (!dashboardPrimed) {
            // A bike that connected mid-route gets the same opening burst as at the start
            primeDashboard()
            return
        }
        lastBurstAt = now
        val guidance = progress?.let { BikeNavProtocol.guidance(route, it, destination.name) } ?: BikeNavProtocol.Guidance.EMPTY
        link.send(BikeNavProtocol.burst(guidance, Calendar.getInstance(), chineseSystemLanguage()))
    }

    private fun primeDashboard() {
        val link = dashboard?.takeIf { it.isReady } ?: return
        link.send(BikeNavProtocol.burst(BikeNavProtocol.Guidance.EMPTY, Calendar.getInstance(), chineseSystemLanguage()))
        dashboardPrimed = true
        lastBurstAt = SystemClock.elapsedRealtime()
    }

    private fun sendEndFrame() {
        dashboard?.takeIf { it.isReady }?.send(listOf(BikeNavProtocol.endFrame(Calendar.getInstance())))
    }

    /** Like the official app: the phone's system language, not the app's. */
    private fun chineseSystemLanguage(): Boolean =
        Resources.getSystem().configuration.locales[0].language == Locale.CHINESE.language

    // ── Rerouting, arrival, stopping ────────────────────────────────────────

    private fun reroute(location: Location) {
        if (rerouteJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MS) return
        lastRerouteAt = now
        val current = _state.value ?: return
        _state.value = current.copy(rerouting = true)
        val heading = if (location.hasBearing() && location.speed > 2f) location.bearing.toInt() else null
        rerouteJob = scope.launch {
            try {
                val route = OpenMapServices.motoRoutes(
                    GeoPoint(location.latitude, location.longitude), current.destination.point, language, heading
                ).first()
                val newTracker = RouteProgressTracker(route)
                // Progress must describe the new route's maneuvers right away, never the old one's
                val fix = lastFix ?: location
                val progress = newTracker.update(GeoPoint(fix.latitude, fix.longitude))
                tracker = newTracker
                offRouteFixes = 0
                _state.value = _state.value?.copy(
                    route = route, progress = progress, position = onRoute(fix, progress), rerouting = false
                )
                Log.i(LOG_TAG, "Rerouted: ${route.lengthMeters.toInt()} m")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep guiding on the old route; try again after the cooldown
                Log.w(LOG_TAG, "Reroute failed: $e")
                _state.value = _state.value?.copy(rerouting = false)
            }
        }
    }

    private fun finish(arrivedState: State) {
        sendEndFrame()
        simulationJob?.cancel()
        rerouteJob?.cancel()
        tickerJob?.cancel()
        _state.value = arrivedState.copy(arrived = true, rerouting = false)
        NavigationService.stopSelfIfRunning()
        finishJob = scope.launch {
            delay(ARRIVED_SHOWN_MS)
            if (_state.value?.arrived == true) _state.value = null
        }
    }

    private fun stopInternal(sendEnd: Boolean) {
        if (sendEnd) sendEndFrame()
        simulationJob?.cancel()
        rerouteJob?.cancel()
        finishJob?.cancel()
        tickerJob?.cancel()
        tracker = null
        lastFix = null
        _state.value = null
    }

    // ── Simulation ───────────────────────────────────────────────────────────

    /** Rides [route] at a steady motorcycle pace, one fix per second. */
    private suspend fun simulate(route: MotoRoute) {
        val points = route.points
        var segment = 0
        var offset = 0.0
        while (scope.isActive && _state.value?.arrived == false) {
            val a = points[segment]
            val b = points[minOf(segment + 1, points.lastIndex)]
            val length = a.distanceTo(b)
            val t = if (length > 0) (offset / length).coerceIn(0.0, 1.0) else 1.0
            handleFix(Location("openvoge-simulation").apply {
                latitude = a.lat + t * (b.lat - a.lat)
                longitude = a.lon + t * (b.lon - a.lon)
                bearing = bearingBetween(a, b).toFloat()
                speed = SIMULATION_SPEED_MPS.toFloat()
                accuracy = 4f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            delay(SIMULATION_TICK_MS)
            // Advance along the polyline, carrying the leftover onto the following segments
            offset += SIMULATION_SPEED_MPS * SIMULATION_TICK_MS / 1000.0
            while (segment < points.lastIndex - 1 && offset > points[segment].distanceTo(points[segment + 1])) {
                offset -= points[segment].distanceTo(points[segment + 1])
                segment++
            }
            if (segment >= points.lastIndex - 1 && offset >= points[segment].distanceTo(points[points.lastIndex])) {
                offset = points[segment].distanceTo(points[points.lastIndex])
            }
        }
    }

    private fun onRoute(location: Location, progress: NavProgress) = Location(location).apply {
        latitude = progress.snapped.lat
        longitude = progress.snapped.lon
        bearing = progress.routeBearing.toFloat()
    }

    private fun bearingBetween(a: GeoPoint, b: GeoPoint): Double {
        val x = (b.lon - a.lon) * kotlin.math.cos(Math.toRadians(a.lat))
        val y = b.lat - a.lat
        return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360.0) % 360.0
    }
}
