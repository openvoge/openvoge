package dev.vogy.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fixtures in src/test/resources/map are real responses captured around Mérida, Venezuela. */
class OpenMapServicesTest {

    private fun fixture(name: String) = File("src/test/resources/map/$name").readText(Charsets.UTF_8)

    private val origin = GeoPoint(8.5897, -71.1450)
    private val destination = GeoPoint(8.6260, -71.1430)

    @Test
    fun decodesGooglePolylineExample() {
        // From Google's encoded polyline documentation (precision 5)
        val points = OpenMapServices.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-9)
        assertEquals(-120.2, points[0].lon, 1e-9)
        assertEquals(40.7, points[1].lat, 1e-9)
        assertEquals(-120.95, points[1].lon, 1e-9)
        assertEquals(43.252, points[2].lat, 1e-9)
        assertEquals(-126.453, points[2].lon, 1e-9)
    }

    @Test
    fun parsesValhallaRouteWithAlternatives() {
        val routes = OpenMapServices.parseValhalla(fixture("valhalla_route.json"))
        assertEquals(3, routes.size)
        val best = routes.first()
        assertEquals(6_123.0, best.lengthMeters, 1.0)
        assertTrue(best.durationSeconds > 0)
        // The decoded shape runs from the origin to the destination
        assertTrue(best.points.first().distanceTo(origin) < 300)
        assertTrue(best.points.last().distanceTo(destination) < 300)
        assertTrue(best.maneuvers.first().instruction.startsWith("Conduzca"))
        assertTrue(best.maneuvers.all { it.beginShapeIndex in best.points.indices })
        assertEquals("Paseo de La Feria", best.maneuvers.first().streetNames.first())
    }

    @Test
    fun mapsValhallaErrors() {
        assertEquals(
            OpenMapServices.RouteException.Reason.TOO_FAR,
            OpenMapServices.valhallaError(fixture("valhalla_no_route.json"))?.reason
        )
        assertEquals(
            OpenMapServices.RouteException.Reason.NO_ROAD_NEARBY,
            OpenMapServices.valhallaError("""{"error_code":171,"error":"No suitable edges near location"}""")?.reason
        )
        assertEquals(
            OpenMapServices.RouteException.Reason.NO_ROUTE,
            OpenMapServices.valhallaError("""{"error_code":170,"error":"Locations are in unconnected regions"}""")?.reason
        )
        assertNull(OpenMapServices.valhallaError("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun buildsMotorcycleRequest() {
        val request = JSONObject(OpenMapServices.valhallaRequest(origin, destination, "es-ES"))
        assertEquals("motorcycle", request.getString("costing"))
        assertEquals(2, request.getInt("alternates"))
        assertEquals("es-ES", request.getString("language"))
        assertEquals(8.6260, request.getJSONArray("locations").getJSONObject(1).getDouble("lat"), 1e-9)
    }

    @Test
    fun parsesPhotonSearch() {
        val places = OpenMapServices.parsePhoton(fixture("photon_search.json"))
        assertTrue(places.isNotEmpty())
        assertTrue(places.all { it.name.isNotBlank() })
        assertTrue(places.first().name.contains("Farmacia", ignoreCase = true))
        // Results are biased to the rider's position: the first one is in town
        assertTrue(places.first().point.distanceTo(origin) < 5_000)
    }

    @Test
    fun parsesPhotonReverse() {
        val place = OpenMapServices.parsePhoton(fixture("photon_reverse.json")).single()
        assertTrue(place.name.isNotBlank())
        assertTrue(place.detail.contains("Mérida") || place.detail.isNotBlank())
    }

    @Test
    fun parsesNominatimFuelNearestFirstWithFallbackNames() {
        val stations = OpenMapServices.parseNominatimFuel(fixture("nominatim_fuel.json"), origin, "Gasolinera")
        // The fixture has 17 stations; the sheet shows the 10 closest
        assertEquals(10, stations.size)
        assertTrue(stations.all { it.isFuel })
        val distances = stations.map { it.point.distanceTo(origin) }
        assertEquals(distances.sorted(), distances)
        // Unnamed stations still get a readable label and an address line
        val unnamed = stations.filter { it.name == "Gasolinera" }
        assertTrue(unnamed.isNotEmpty())
        assertTrue(unnamed.all { it.detail.isNotBlank() })
        // The name isn't repeated in the address line
        assertTrue(stations.none { it.detail.startsWith(it.name + ",") })
    }

    @Test
    fun httpErrorsAreServiceErrorsNotConnectionErrors() {
        val error: Exception = OpenMapServices.ServiceException("nominatim.openstreetmap.org", 502)
        assertTrue(error is java.io.IOException)
        assertTrue(error.message!!.contains("502"))
    }

    @Test
    fun distanceIsHaversine() {
        // One degree of latitude is ~111.2 km
        assertEquals(111_195.0, GeoPoint(0.0, 0.0).distanceTo(GeoPoint(1.0, 0.0)), 50.0)
    }
}
