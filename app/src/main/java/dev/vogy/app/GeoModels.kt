package dev.vogy.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position. Kept apart from MapLibre's LatLng so routing and search stay plain Kotlin. */
data class GeoPoint(val lat: Double, val lon: Double) {

    /** Great-circle distance in meters. */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}

/** A destination the rider can pick: a search result, a fuel station or a point on the map. */
data class Place(
    val name: String,
    /** Street, neighbourhood, city… whatever helps tell two results apart. May be empty. */
    val detail: String,
    val point: GeoPoint,
    val isFuel: Boolean = false,
)

/** One turn-by-turn step of a route, as Valhalla describes it. */
data class Maneuver(
    /** Valhalla maneuver type (e.g. 10 = right, 15 = left, 26 = roundabout enter). */
    val type: Int,
    val instruction: String,
    val lengthMeters: Double,
    val durationSeconds: Double,
    val streetNames: List<String>,
    /** Index into [MotoRoute.points] where this maneuver starts. */
    val beginShapeIndex: Int,
    val roundaboutExitCount: Int?,
    /** Heading (0–359°, clockwise from north) arriving at and leaving the maneuver point; null when unknown. */
    val bearingBefore: Int? = null,
    val bearingAfter: Int? = null,
)

data class MotoRoute(
    val points: List<GeoPoint>,
    val lengthMeters: Double,
    val durationSeconds: Double,
    val maneuvers: List<Maneuver>,
)
