package dev.vogy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.pow

/**
 * The free OpenStreetMap web services behind the MAP tab. None needs an account or an API key:
 * - **Photon** (komoot): place search as you type, and "what's at this point".
 * - **Nominatim** (OSMF): nearest fuel stations by category. Most stations in OSM have no name,
 *   so typing can't find them.
 * - **Valhalla** (FOSSGIS): motorcycle routes with alternatives and turn-by-turn instructions.
 *
 * They're community-run with fair-use limits: call them once per rider action, never in a loop.
 * Nominatim in particular allows at most one request per second.
 */
object OpenMapServices {

    private const val USER_AGENT = "OpenVOGE (+https://github.com/openvoge/openvoge)"
    private const val PHOTON = "https://photon.komoot.io"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org"
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val NOMINATIM_SPACING_MS = 1_100L

    /** Valhalla answered, but there's no motorcycle route to offer. */
    class RouteException(val reason: Reason) : Exception("No route: $reason") {
        enum class Reason { NO_ROAD_NEARBY, TOO_FAR, NO_ROUTE }
    }

    /** A service answered with an HTTP error (overloaded, down…): not the rider's connection. */
    class ServiceException(host: String, status: Int) : IOException("HTTP $status from $host")

    // ── Network ──────────────────────────────────────────────────────────────

    /** Places matching [query], closest to [near] first when given. */
    suspend fun searchPlaces(query: String, near: GeoPoint?): List<Place> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$PHOTON/api/?q=").append(urlEncode(query)).append("&limit=8&lang=default")
            if (near != null) append("&lat=${near.lat}&lon=${near.lon}&zoom=12")
        }
        parsePhoton(getOk(url))
    }

    /** A readable name for a point picked on the map, or null if Photon knows nothing there. */
    suspend fun reverseGeocode(point: GeoPoint): Place? = withContext(Dispatchers.IO) {
        parsePhoton(getOk("$PHOTON/reverse?lat=${point.lat}&lon=${point.lon}&limit=1&lang=default"))
            .firstOrNull()
            ?.copy(point = point)
    }

    /**
     * Fuel stations around [near], nearest first: about 5 km, then about 25 km if nothing is
     * that close. [language] is a two-letter code for the address lines ("es", "en").
     */
    suspend fun nearestFuel(near: GeoPoint, fallbackName: String, language: String): List<Place> =
        withContext(Dispatchers.IO) {
            for ((attempt, spanDegrees) in listOf(0.05, 0.25).withIndex()) {
                if (attempt > 0) delay(NOMINATIM_SPACING_MS)
                val viewbox = listOf(near.lon - spanDegrees, near.lat + spanDegrees, near.lon + spanDegrees, near.lat - spanDegrees)
                    .joinToString(",")
                val url = "$NOMINATIM/search?q=" + urlEncode("[amenity=fuel]") +
                    "&format=jsonv2&limit=40&bounded=1&viewbox=$viewbox&accept-language=$language"
                val stations = parseNominatimFuel(getOk(url), near, fallbackName)
                if (stations.isNotEmpty()) return@withContext stations
            }
            emptyList()
        }

    /**
     * Motorcycle routes from [from] to [to]: the best one first, then up to two alternatives.
     * [language] is a Valhalla locale for the spoken/written instructions, e.g. "es-ES".
     */
    suspend fun motoRoutes(from: GeoPoint, to: GeoPoint, language: String, headingDegrees: Int? = null): List<MotoRoute> =
        withContext(Dispatchers.IO) {
            val body = valhallaRequest(from, to, language, headingDegrees)
            val (code, text) = request(VALHALLA, body = body, contentType = "application/json")
            if (code in 200..299) parseValhalla(text)
            else throw valhallaError(text) ?: ServiceException(URL(VALHALLA).host, code)
        }

    private fun getOk(url: String): String {
        val (code, text) = request(url)
        if (code !in 200..299) throw ServiceException(URL(url).host, code)
        return text
    }

    /** Plain HttpURLConnection: a GET, or a POST when [body] is given. Returns (status, body). */
    private fun request(url: String, body: String? = null, contentType: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "$contentType; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ── Requests and parsing (pure, unit-tested) ────────────────────────────

    /**
     * [headingDegrees] is the rider's direction of travel when rerouting on the move, so the new
     * route continues ahead instead of starting with a U-turn. Rerouting skips alternatives.
     */
    fun valhallaRequest(from: GeoPoint, to: GeoPoint, language: String, headingDegrees: Int? = null): String {
        val origin = location(from)
        if (headingDegrees != null) origin.put("heading", headingDegrees).put("heading_tolerance", 45)
        return JSONObject()
            .put("locations", JSONArray().put(origin).put(location(to)))
            .put("costing", "motorcycle")
            .put("alternates", if (headingDegrees == null) 2 else 0)
            .put("units", "kilometers")
            .put("language", language)
            .toString()
    }

    private fun location(point: GeoPoint) = JSONObject().put("lat", point.lat).put("lon", point.lon)

    fun parsePhoton(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length())
            .mapNotNull { photonPlace(features.getJSONObject(it)) }
            // Photon often returns the same building as a node and a way
            .distinctBy { "${it.name}|${"%.3f".format(java.util.Locale.ROOT, it.point.lat)}|${"%.3f".format(java.util.Locale.ROOT, it.point.lon)}" }
    }

    private fun photonPlace(feature: JSONObject): Place? {
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val point = GeoPoint(lat = coordinates.getDouble(1), lon = coordinates.getDouble(0))
        val properties = feature.optJSONObject("properties") ?: return null
        val street = listOfNotNull(properties.text("street"), properties.text("housenumber"))
            .joinToString(" ")
            .ifBlank { null }
        val area = properties.text("locality") ?: properties.text("district")
        val name = properties.text("name") ?: street ?: area ?: properties.text("city") ?: return null
        val detail = listOfNotNull(street, area, properties.text("city"), properties.text("state"))
            .filter { it != name }
            .distinct()
            .take(3)
            .joinToString(", ")
        val isFuel = properties.text("osm_key") == "amenity" && properties.text("osm_value") == "fuel"
        return Place(name, detail, point, isFuel)
    }

    /** Nominatim results (jsonv2), nearest first. Unnamed stations get [fallbackName]. */
    fun parseNominatimFuel(json: String, near: GeoPoint, fallbackName: String): List<Place> {
        val results = JSONArray(json)
        return (0 until results.length())
            .map { index ->
                val result = results.getJSONObject(index)
                val name = result.text("name") ?: fallbackName
                // display_name is "Name, street, area, city, …, country": keep the first parts after the name
                val detail = result.optString("display_name")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != name }
                    .take(3)
                    .joinToString(", ")
                // Nominatim sends coordinates as strings
                val point = GeoPoint(result.getString("lat").toDouble(), result.getString("lon").toDouble())
                Place(name, detail, point, isFuel = true)
            }
            .sortedBy { it.point.distanceTo(near) }
            .take(10)
    }

    fun parseValhalla(json: String): List<MotoRoute> {
        val root = JSONObject(json)
        val trips = mutableListOf(root.getJSONObject("trip"))
        root.optJSONArray("alternates")?.let { alternates ->
            for (i in 0 until alternates.length()) trips += alternates.getJSONObject(i).getJSONObject("trip")
        }
        return trips.map(::valhallaTrip)
    }

    private fun valhallaTrip(trip: JSONObject): MotoRoute {
        val metersPerUnit = if (trip.optString("units") == "miles") 1_609.344 else 1_000.0
        val points = mutableListOf<GeoPoint>()
        val maneuvers = mutableListOf<Maneuver>()
        val legs = trip.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val shapeOffset = points.size
            points += decodePolyline(leg.getString("shape"))
            val legManeuvers = leg.getJSONArray("maneuvers")
            for (m in 0 until legManeuvers.length()) {
                val maneuver = legManeuvers.getJSONObject(m)
                val streets = maneuver.optJSONArray("street_names")
                maneuvers += Maneuver(
                    type = maneuver.getInt("type"),
                    instruction = maneuver.optString("instruction"),
                    lengthMeters = maneuver.optDouble("length", 0.0) * metersPerUnit,
                    durationSeconds = maneuver.optDouble("time", 0.0),
                    streetNames = if (streets == null) emptyList() else (0 until streets.length()).map(streets::getString),
                    beginShapeIndex = shapeOffset + maneuver.optInt("begin_shape_index", 0),
                    roundaboutExitCount = maneuver.optInt("roundabout_exit_count", 0).takeIf { it > 0 },
                    bearingBefore = if (maneuver.has("bearing_before")) maneuver.getInt("bearing_before") else null,
                    bearingAfter = if (maneuver.has("bearing_after")) maneuver.getInt("bearing_after") else null,
                )
            }
        }
        val summary = trip.getJSONObject("summary")
        return MotoRoute(
            points = points,
            lengthMeters = summary.getDouble("length") * metersPerUnit,
            durationSeconds = summary.getDouble("time"),
            maneuvers = maneuvers,
        )
    }

    /** Maps a Valhalla error body to a [RouteException]; null if it isn't a Valhalla error. */
    fun valhallaError(json: String): RouteException? {
        val code = runCatching { JSONObject(json).optInt("error_code", -1) }.getOrDefault(-1)
        return when (code) {
            -1 -> null
            171 -> RouteException(RouteException.Reason.NO_ROAD_NEARBY)
            154 -> RouteException(RouteException.Reason.TOO_FAR)
            else -> RouteException(RouteException.Reason.NO_ROUTE)
        }
    }

    /** Decodes a Google-style encoded polyline; Valhalla uses 6 decimal digits of precision. */
    fun decodePolyline(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = 10.0.pow(precision)
        val points = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        fun nextDelta(): Long {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f).toLong() shl shift)
                shift += 5
            } while (byte >= 0x20)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += nextDelta()
            lon += nextDelta()
            points += GeoPoint(lat / factor, lon / factor)
        }
        return points
    }

    /** Trimmed string value, or null when missing, JSON null or blank. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
