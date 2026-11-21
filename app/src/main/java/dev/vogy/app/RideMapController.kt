package dev.vogy.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.LocationManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The MAP section: a MapLibre map with the OpenVOGE style, the rider's position, destination
 * search, motorcycle routes and turn-by-turn navigation.
 *
 * The MapView is only created the first time the section is shown, so riders who never open
 * the map don't pay for the GL surface or the tile downloads. Once created it follows the
 * activity lifecycle on its own. Navigation itself lives in [NavigationSession]: this class only
 * shows it, so it survives the screen turning off or the activity being recreated.
 */
class RideMapController(
    private val activity: AppCompatActivity,
    /** The MAP section root: the controller finds its own views inside it. */
    section: View,
    /** Asks the activity to run its location permission flow. */
    private val requestLocationPermission: () -> Unit,
    /** Applies the app's dotted neo-brutalist background to a bottom sheet. */
    decorateSheet: (View) -> Unit,
    /** Called right before navigation starts (e.g. to ask for the notification permission). */
    private val onNavigationStarting: () -> Unit,
) : DefaultLifecycleObserver {

    private companion object {
        const val FOLLOW_ZOOM = 15.5
        const val NAV_ZOOM = 17.0
        /** While navigating the arrow sits low on the map so more road ahead is visible. */
        const val NAV_ARROW_FROM_TOP = 0.35
        const val FOLLOW_TRANSITION_MS = 750L
        const val FIT_ROUTE_MS = 800
        const val MAX_ZOOM = 19.0
    }

    private val container: FrameLayout = section.findViewById(R.id.mapContainer)
    private val locateButton: ImageButton = section.findViewById(R.id.btnMapLocate)
    private val searchBar: View = section.findViewById(R.id.mapSearchBar)
    private val navBanner: View = section.findViewById(R.id.navBanner)
    private val navManeuverIcon: ImageView = section.findViewById(R.id.ivNavManeuver)
    private val navDistance: TextView = section.findViewById(R.id.tvNavDistance)
    private val navInstruction: TextView = section.findViewById(R.id.tvNavInstruction)
    private val routeCard: View = section.findViewById(R.id.routeCard)
    private val routeTitle: TextView = section.findViewById(R.id.tvRouteTitle)
    private val routeDetail: TextView = section.findViewById(R.id.tvRouteDetail)
    private val routeStatus: TextView = section.findViewById(R.id.tvRouteStatus)
    private val routeOptions: LinearLayout = section.findViewById(R.id.layoutRouteOptions)
    private val routeActions: View = section.findViewById(R.id.layoutRouteActions)
    private val routeClear: View = section.findViewById(R.id.btnRouteClear)
    private val navigatingRow: View = section.findViewById(R.id.layoutNavigating)
    private val navSummary: TextView = section.findViewById(R.id.tvNavSummary)
    private val navDashStatus: TextView = section.findViewById(R.id.tvNavDashStatus)
    private val density = activity.resources.displayMetrics.density

    private val searchSheet = DestinationSearchSheet(
        activity = activity,
        decorate = decorateSheet,
        origin = ::riderPosition,
        distanceUnit = { distanceUnit },
        onPicked = ::routeTo,
    )

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var routeLayers: RouteLayers? = null
    private var isFollowing = false
    private var hasCenteredOnRider = false
    /** Following started before the first GPS fix: MapLibre drops the zoom then, so redo it on the fix. */
    private var zoomOnFirstFix = false

    // Route planning state
    private var destination: Place? = null
    private var routes: List<MotoRoute> = emptyList()
    private var selectedRoute = 0
    private var routeMessage: String? = null
    private var routeJob: Job? = null
    private var distanceUnit = SpeedUnit.KMH

    // Navigation display state
    private var navigation: NavigationSession.State? = null
    private var shownNavRoute: MotoRoute? = null
    /** The map's own GPS engine is off while navigating: the session feeds the arrow instead. */
    private var puckFedBySession = false

    private val navigating: Boolean get() = navigation != null

    init {
        locateButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onLocateTapped()
        }
        searchBar.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            searchSheet.show()
        }
        routeClear.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            clearRoute()
        }
        section.findViewById<View>(R.id.btnRouteStart).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            startNavigation(simulate = false)
        }
        section.findViewById<View>(R.id.btnRouteSimulate).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            startNavigation(simulate = true)
        }
        section.findViewById<View>(R.id.btnNavEnd).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            NavigationSession.stop(activity)
        }
        renderLocateButton()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationSession.state.collect(::showNavigation)
            }
        }
    }

    /** Call every time the MAP section becomes visible. */
    fun onSectionShown() {
        if (mapView == null) createMap() else showRider(requestIfMissing = false)
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) showRider(requestIfMissing = false)
    }

    /** Distances on the route card follow the dashboard's KM/H or MP/H setting. */
    fun setDistanceUnit(unit: SpeedUnit) {
        if (unit == distanceUnit) return
        distanceUnit = unit
        renderRouteCard()
        navigation?.let(::renderNavigation)
    }

    // ── Map setup ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createMap() {
        MapLibre.getInstance(activity)
        // TextureView so the map is clipped by the card's rounded corners like any other view
        val view = MapView(activity, MapLibreMapOptions.createFromAttributes(activity).textureMode(true))
        // The page is a NestedScrollView: don't let it steal vertical drags from the map
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        container.addView(
            view, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        mapView = view
        view.onCreate(null)
        // Replays ON_START / ON_RESUME into the MapView, then keeps it in sync
        activity.lifecycle.addObserver(this)
        view.getMapAsync(::onMapReady)
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        map = readyMap
        readyMap.uiSettings.apply {
            setLogoEnabled(false)
            setTiltGesturesEnabled(false)
            // OSM / OpenMapTiles attribution is required: keep the (i) button, styled in ink
            setAttributionTintColor(ContextCompat.getColor(activity, R.color.neo_ink))
            setAttributionGravity(Gravity.BOTTOM or Gravity.START)
            setAttributionMargins(dp(14), 0, 0, dp(14))
            // Below the search bar / maneuver banner
            setCompassGravity(Gravity.TOP or Gravity.END)
            setCompassMargins(0, dp(118), dp(16), 0)
        }
        readyMap.setMaxZoomPreference(MAX_ZOOM)
        // Start at street level where the rider last was: if the first fix lands on the same spot
        // the tracking camera doesn't move, so the pending first-fix zoom below would never run
        lastKnownPosition()?.let {
            readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), FOLLOW_ZOOM))
        }
        // The first fix moves the tracking camera to the rider: that's when the pending zoom can run
        readyMap.addOnCameraMoveListener {
            val locationComponent = readyMap.locationComponent
            if (zoomOnFirstFix && locationComponent.isLocationComponentActivated &&
                locationComponent.lastKnownLocation != null
            ) {
                zoomOnFirstFix = false
                mapView?.post { followRider() }
            }
        }
        // Long-press anywhere: route there. The fix for places no search engine knows.
        readyMap.addOnMapLongClickListener { latLng ->
            if (navigating) return@addOnMapLongClickListener false
            mapView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dropPin(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        // Tap an alternative route line to pick it
        readyMap.addOnMapClickListener { latLng ->
            if (navigating) return@addOnMapClickListener false
            val index = routeLayers?.routeIndexAt(readyMap, latLng, touchRadiusPx = 14 * density)
            if (index != null && index != selectedRoute) {
                selectRoute(index)
                true
            } else {
                false
            }
        }
        readyMap.setStyle(Style.Builder().fromJson(NeoMapStyle.build(activity))) { loaded ->
            style = loaded
            routeLayers = RouteLayers(
                loaded,
                accentColor = MaterialColors.getColor(activity, R.attr.neoAccent, Color.BLACK),
            )
            showRider(requestIfMissing = true)
            // Navigation may have started before the map existed (e.g. the activity was recreated)
            shownNavRoute = null
            navigation?.let(::renderNavigation)
        }
    }

    // ── Rider position ───────────────────────────────────────────────────────

    /** Turns on the position puck; the first time it also starts following the rider. */
    @SuppressLint("MissingPermission")
    private fun showRider(requestIfMissing: Boolean) {
        val readyMap = map ?: return
        val loadedStyle = style ?: return
        if (!hasLocationPermission()) {
            if (requestIfMissing) requestLocationPermission()
            return
        }
        val locationComponent = readyMap.locationComponent
        if (!locationComponent.isLocationComponentActivated) {
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(activity, loadedStyle)
                    .locationComponentOptions(puckOptions())
                    // Android's own location providers: no Google Play services needed
                    .useDefaultLocationEngine(true)
                    .build()
            )
            locationComponent.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() = setFollowing(false)
                override fun onCameraTrackingChanged(currentMode: Int) = setFollowing(currentMode != CameraMode.NONE)
            })
            // The dot's beam: the bike's course while riding, the phone's compass while stopped
            locationComponent.compassEngine?.let { phoneCompass ->
                locationComponent.setCompassEngine(
                    RiderHeadingCompassEngine(phoneCompass) {
                        locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
                    }
                )
            }
        }
        locationComponent.setLocationComponentEnabled(true)
        if (navigating) return
        // Dot with a heading beam; the map itself stays north-up
        locationComponent.setRenderMode(RenderMode.COMPASS)
        if (!hasCenteredOnRider && destination == null) {
            hasCenteredOnRider = true
            followRider()
        }
    }

    @SuppressLint("MissingPermission")
    private fun followRider() {
        val readyMap = map ?: return
        val locationComponent = readyMap.locationComponent
        if (!locationComponent.isLocationComponentActivated) return
        if (navigating) {
            followRiderNavigating()
            return
        }
        zoomOnFirstFix = locationComponent.lastKnownLocation == null
        locationComponent.setCameraMode(
            CameraMode.TRACKING,
            FOLLOW_TRANSITION_MS,
            max(readyMap.cameraPosition.zoom, FOLLOW_ZOOM),
            null,
            null,
            null
        )
    }

    /**
     * Heading-up and close in, with the arrow low on the map: the view while riding a route.
     * Kept flat on purpose: MapLibre drops a tracking tilt now and then, and a view that flips
     * between 3D and flat mid-ride is worse than a steady flat one.
     */
    private fun followRiderNavigating() {
        val locationComponent = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        locationComponent.setRenderMode(RenderMode.GPS)
        // Padding is ignored while the camera mode is still transitioning: apply it after
        val settle = {
            if (navigating && locationComponent.isLocationComponentActivated) {
                locationComponent.paddingWhileTracking(doubleArrayOf(0.0, container.height * NAV_ARROW_FROM_TOP, 0.0, 0.0), FOLLOW_TRANSITION_MS)
            }
        }
        locationComponent.setCameraMode(
            CameraMode.TRACKING_GPS, FOLLOW_TRANSITION_MS, NAV_ZOOM, null, 0.0,
            object : OnLocationCameraTransitionListener {
                override fun onLocationCameraTransitionFinished(cameraMode: Int) = settle()
                override fun onLocationCameraTransitionCanceled(cameraMode: Int) = settle()
            }
        )
    }

    private fun stopFollowing() {
        val locationComponent = map?.locationComponent ?: return
        if (locationComponent.isLocationComponentActivated) locationComponent.setCameraMode(CameraMode.NONE)
        setFollowing(false)
    }

    private fun onLocateTapped() {
        when {
            !hasLocationPermission() -> requestLocationPermission()
            map == null || style == null -> Unit
            else -> {
                showRider(requestIfMissing = false)
                followRider()
            }
        }
    }

    private fun setFollowing(following: Boolean) {
        // The rider panned away before the first fix: don't yank the camera back when it arrives
        if (!following) zoomOnFirstFix = false
        if (isFollowing == following) return
        isFollowing = following
        renderLocateButton()
    }

    /** Accent while the camera follows the rider, white once they pan away. */
    private fun renderLocateButton() {
        locateButton.setBackgroundResource(
            if (isFollowing) R.drawable.bg_neo_box_lime else R.drawable.bg_neo_box_white
        )
        val ink = if (isFollowing) MaterialColors.getColor(locateButton, R.attr.neoOnAccent) else Color.BLACK
        locateButton.imageTintList = ColorStateList.valueOf(ink)
        locateButton.isSelected = isFollowing
    }

    private fun puckOptions(): LocationComponentOptions =
        LocationComponentOptions.builder(activity)
            .foregroundDrawable(R.drawable.ic_map_puck_neo)
            .backgroundDrawable(R.drawable.ic_map_puck_none)
            .gpsDrawable(R.drawable.ic_map_puck_nav_neo)
            .bearingDrawable(R.drawable.ic_map_puck_heading_neo)
            .accuracyColor(MaterialColors.getColor(activity, R.attr.neoAccent, Color.BLACK))
            .accuracyAlpha(0.18f)
            .elevation(0f)
            .enableStaleState(false)
            .build()

    /** Where the rider is now: the live fix when the map has one, else Android's last known. */
    @SuppressLint("MissingPermission")
    private fun riderPosition(): GeoPoint? {
        val locationComponent = map?.locationComponent
        val live = locationComponent
            ?.takeIf { it.isLocationComponentActivated }
            ?.lastKnownLocation
            ?.let { GeoPoint(it.latitude, it.longitude) }
        return live ?: lastKnownPosition()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownPosition(): GeoPoint? {
        if (!hasLocationPermission()) return null
        val locationManager = activity.getSystemService(LocationManager::class.java) ?: return null
        return locationManager.getProviders(true)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ── Destination and routes ──────────────────────────────────────────────

    /** A long-press on the map: route there now, put a readable name on it when Photon answers. */
    private fun dropPin(point: GeoPoint) {
        val pin = Place(
            name = activity.getString(R.string.map_dropped_pin),
            detail = String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon),
            point = point,
        )
        routeTo(pin)
        activity.lifecycleScope.launch {
            val nearby = runCatching { OpenMapServices.reverseGeocode(point) }.getOrNull() ?: return@launch
            if (destination?.point != point) return@launch
            destination = pin.copy(detail = listOf(nearby.name, nearby.detail).filter { it.isNotBlank() }.joinToString(", "))
            renderRouteCard()
        }
    }

    private fun routeTo(place: Place) {
        if (navigating) return
        routeJob?.cancel()
        destination = place
        routes = emptyList()
        selectedRoute = 0
        routeLayers?.clear()
        routeLayers?.showDestination(place.point)
        stopFollowing()

        val origin = riderPosition()
        if (origin == null) {
            routeMessage = activity.getString(R.string.map_route_no_origin)
            renderRouteCard()
            fitCamera(listOf(place.point))
            return
        }
        routeMessage = activity.getString(R.string.map_route_calculating)
        renderRouteCard()
        fitCamera(listOf(origin, place.point))

        val language = RouteFormat.valhallaLanguage(activity.resources.configuration.locales[0])
        routeJob = activity.lifecycleScope.launch {
            try {
                val found = OpenMapServices.motoRoutes(origin, place.point, language)
                routes = found
                routeMessage = null
                routeLayers?.showRoutes(found, selectedRoute)
                // Include the rider: routes start on the nearest road, which may be off to one side
                fitCamera(found.flatMap { it.points } + origin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenMapServices.RouteException) {
                routeMessage = activity.getString(
                    when (e.reason) {
                        OpenMapServices.RouteException.Reason.NO_ROAD_NEARBY -> R.string.map_route_error_no_road
                        OpenMapServices.RouteException.Reason.TOO_FAR -> R.string.map_route_error_too_far
                        OpenMapServices.RouteException.Reason.NO_ROUTE -> R.string.map_route_error_none
                    }
                )
            } catch (e: Exception) {
                routeMessage = activity.getString(
                    if (e is OpenMapServices.ServiceException) R.string.map_service_unavailable
                    else R.string.map_route_error_network
                )
            }
            renderRouteCard()
        }
    }

    private fun selectRoute(index: Int) {
        if (index !in routes.indices) return
        selectedRoute = index
        routeLayers?.showRoutes(routes, index)
        renderRouteCard()
    }

    private fun clearRoute() {
        routeJob?.cancel()
        destination = null
        routes = emptyList()
        routeMessage = null
        routeLayers?.clear()
        renderRouteCard()
    }

    private fun renderRouteCard() {
        if (navigating) return
        val place = destination
        routeCard.visibility = if (place == null) View.GONE else View.VISIBLE
        navigatingRow.visibility = View.GONE
        routeClear.visibility = View.VISIBLE
        if (place == null) return
        routeTitle.text = place.name
        routeDetail.text = place.detail
        routeDetail.visibility = if (place.detail.isBlank()) View.GONE else View.VISIBLE
        routeStatus.text = routeMessage
        routeStatus.visibility = if (routeMessage == null) View.GONE else View.VISIBLE
        routeActions.visibility = if (routes.isEmpty()) View.GONE else View.VISIBLE

        routeOptions.removeAllViews()
        routeOptions.visibility = if (routes.isEmpty()) View.GONE else View.VISIBLE
        val locale = activity.resources.configuration.locales[0]
        val clock = DateFormat.getTimeFormat(activity)
        val onAccent = MaterialColors.getColor(routeOptions, R.attr.neoOnAccent)
        val ink = ContextCompat.getColor(activity, R.color.neo_ink)
        routes.forEachIndexed { index, route ->
            val option = activity.layoutInflater.inflate(R.layout.item_route_option, routeOptions, false)
            val selected = index == selectedRoute
            val duration = RouteFormat.duration(route.durationSeconds)
            val distance = RouteFormat.distance(route.lengthMeters, distanceUnit, locale)
            val arrival = clock.format(Date(System.currentTimeMillis() + (route.durationSeconds * 1000).toLong()))
            option.findViewById<TextView>(R.id.tvRouteOptionTime).apply {
                text = duration
                setTextColor(if (selected) onAccent else ink)
            }
            option.findViewById<TextView>(R.id.tvRouteOptionDistance).apply {
                text = distance
                setTextColor(if (selected) onAccent else ink)
            }
            option.findViewById<TextView>(R.id.tvRouteOptionArrival).apply {
                text = arrival
                setTextColor(if (selected) onAccent else ink)
            }
            option.setBackgroundResource(if (selected) R.drawable.bg_nav_tab_active else R.drawable.bg_route_option_idle)
            option.isSelected = selected
            option.contentDescription = activity.getString(R.string.map_route_option, index + 1, duration, distance, arrival)
            option.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                selectRoute(index)
            }
            if (index > 0) {
                (option.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            }
            routeOptions.addView(option)
        }
    }

    /** Frames [points] below the search bar and clear of the locate button. */
    private fun fitCamera(points: List<GeoPoint>) {
        val readyMap = map ?: return
        if (points.size < 2 || points.distinct().size < 2) {
            points.firstOrNull()?.let {
                readyMap.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), FOLLOW_ZOOM), FIT_ROUTE_MS)
            }
            return
        }
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        readyMap.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, dp(40), dp(130), dp(40), dp(84)),
            FIT_ROUTE_MS
        )
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun startNavigation(simulate: Boolean) {
        val place = destination ?: return
        val route = routes.getOrNull(selectedRoute) ?: return
        onNavigationStarting()
        NavigationSession.start(activity, place, route, simulate)
    }

    /** Reacts to [NavigationSession]: enters, updates and leaves the navigation view. */
    private fun showNavigation(state: NavigationSession.State?) {
        val wasNavigating = navigating
        navigation = state
        when {
            state != null -> renderNavigation(state)
            wasNavigating -> leaveNavigation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderNavigation(state: NavigationSession.State) {
        // The card and layers describe the route being ridden, not the planning choices
        destination = state.destination
        searchBar.visibility = View.GONE
        navBanner.visibility = View.VISIBLE
        routeCard.visibility = View.VISIBLE
        routeOptions.visibility = View.GONE
        routeActions.visibility = View.GONE
        routeClear.visibility = View.GONE
        routeStatus.visibility = View.GONE
        navigatingRow.visibility = View.VISIBLE

        val locale = activity.resources.configuration.locales[0]
        val progress = state.progress
        val maneuver = state.nextManeuver
        routeTitle.text = state.destination.name
        routeDetail.visibility = View.VISIBLE
        routeDetail.text = buildList {
            if (state.simulated) add(activity.getString(R.string.nav_simulating_badge))
            progress?.let {
                add(activity.getString(
                    R.string.nav_arrival_time,
                    DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + (it.durationRemainingSeconds * 1000).toLong()))
                ))
            }
        }.joinToString(" · ")
        navSummary.text = progress?.let {
            "${RouteFormat.duration(it.durationRemainingSeconds)} · ${RouteFormat.distance(it.distanceRemainingMeters, distanceUnit, locale)}"
        } ?: RouteFormat.duration(state.route.durationSeconds)
        val dashboard = NavigationSession.dashboard
        navDashStatus.text = activity.getString(
            when {
                dashboard?.isReady == true -> R.string.nav_bike_dash_on
                dashboard?.isConnected == true -> R.string.nav_bike_dash_unsupported
                else -> R.string.nav_bike_dash_off
            }
        )

        // Banner: arrow, distance to the maneuver, what to do there
        when {
            state.arrived -> {
                showManeuverIcon(arrival = true, direction = 0, roundabout = false)
                navDistance.text = activity.getString(R.string.nav_arrived_banner)
                navInstruction.text = state.destination.name
            }
            state.rerouting -> {
                navManeuverIcon.setImageResource(R.drawable.ic_search_neo)
                navManeuverIcon.rotation = 0f
                navManeuverIcon.scaleX = 1f
                navDistance.text = activity.getString(R.string.nav_rerouting)
                navInstruction.text = state.destination.name
                // The old route's numbers no longer mean anything
                navSummary.text = "…"
            }
            progress != null && maneuver != null -> {
                val index = progress.nextManeuverIndex
                showManeuverIcon(
                    arrival = maneuver.type in 4..6,
                    direction = BikeNavProtocol.direction(state.route.maneuvers, index),
                    roundabout = BikeNavProtocol.roundaboutExitDegrees(state.route.maneuvers, index) != null,
                )
                navDistance.text = RouteFormat.distance(progress.distanceToManeuverMeters, distanceUnit, locale)
                navInstruction.text = maneuver.instruction
            }
            else -> {
                showManeuverIcon(arrival = false, direction = BikeNavProtocol.STRAIGHT, roundabout = false)
                navDistance.text = RouteFormat.duration(state.route.durationSeconds)
                navInstruction.text = state.destination.name
            }
        }

        // Map: only the route being ridden, the arrow fed by the session, heading-up camera
        val layers = routeLayers ?: return
        if (shownNavRoute !== state.route) {
            shownNavRoute = state.route
            layers.showRoutes(listOf(state.route), 0)
            layers.showDestination(state.destination.point)
        }
        val locationComponent = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        if (!puckFedBySession) {
            puckFedBySession = true
            locationComponent.setLocationEngine(null)
            followRiderNavigating()
        }
        state.position?.let(locationComponent::forceLocationUpdate)
    }

    private fun showManeuverIcon(arrival: Boolean, direction: Int, roundabout: Boolean) {
        var rotation = 0f
        var mirrored = false
        val icon = when {
            arrival -> R.drawable.ic_nav_arrive
            roundabout -> R.drawable.ic_nav_roundabout
            else -> when (direction) {
                BikeNavProtocol.RIGHT -> R.drawable.ic_nav_turn
                BikeNavProtocol.LEFT -> R.drawable.ic_nav_turn.also { mirrored = true }
                BikeNavProtocol.SLIGHT_RIGHT -> R.drawable.ic_nav_straight.also { rotation = 45f }
                BikeNavProtocol.SLIGHT_LEFT -> R.drawable.ic_nav_straight.also { rotation = -45f }
                BikeNavProtocol.SHARP_RIGHT -> R.drawable.ic_nav_straight.also { rotation = 135f }
                BikeNavProtocol.SHARP_LEFT -> R.drawable.ic_nav_straight.also { rotation = -135f }
                BikeNavProtocol.UTURN_LEFT -> R.drawable.ic_nav_uturn
                BikeNavProtocol.UTURN_RIGHT -> R.drawable.ic_nav_uturn.also { mirrored = true }
                else -> R.drawable.ic_nav_straight
            }
        }
        navManeuverIcon.setImageResource(icon)
        navManeuverIcon.rotation = rotation
        navManeuverIcon.scaleX = if (mirrored) -1f else 1f
    }

    /** Navigation ended (arrived, END, or the notification): back to the regular map. */
    private fun leaveNavigation() {
        shownNavRoute = null
        searchBar.visibility = View.VISIBLE
        navBanner.visibility = View.GONE
        val readyMap = map
        val locationComponent = readyMap?.locationComponent?.takeIf { it.isLocationComponentActivated }
        if (readyMap != null && locationComponent != null && puckFedBySession) {
            locationComponent.setLocationEngine(LocationEngineDefault.getDefaultLocationEngine(activity))
            locationComponent.setRenderMode(RenderMode.COMPASS)
            // North-up and flat again, then back to plain following without the navigation offset
            locationComponent.setCameraMode(CameraMode.NONE)
            val flat = CameraPosition.Builder(readyMap.cameraPosition).bearing(0.0).tilt(0.0).zoom(FOLLOW_ZOOM).build()
            val resumeFollowing = {
                if (!navigating && locationComponent.isLocationComponentActivated) {
                    locationComponent.setCameraMode(CameraMode.TRACKING)
                    locationComponent.paddingWhileTracking(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
                }
            }
            readyMap.easeCamera(
                CameraUpdateFactory.newCameraPosition(flat), FOLLOW_TRANSITION_MS.toInt(),
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() = resumeFollowing()
                    override fun onCancel() = resumeFollowing()
                }
            )
        }
        puckFedBySession = false
        clearRoute()
    }

    private fun dp(value: Int) = (value * density).toInt()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) {
        mapView?.onStart()
        // MapLibre resets the tracking padding while stopped: put the riding view back
        if (navigating && isFollowing) mapView?.post { followRiderNavigating() }
    }

    override fun onResume(owner: LifecycleOwner) {
        mapView?.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        mapView?.onPause()
    }

    override fun onStop(owner: LifecycleOwner) {
        mapView?.onStop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        routeJob?.cancel()
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
        routeLayers = null
    }
}
