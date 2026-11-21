package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RouteFormatTest {

    private val spanish = Locale("es", "VE")

    @Test
    fun formatsDurations() {
        assertEquals("1 MIN", RouteFormat.duration(20.0))
        assertEquals("15 MIN", RouteFormat.duration(911.0))
        assertEquals("1 H 05", RouteFormat.duration(3_900.0))
        assertEquals("12 H 00", RouteFormat.duration(43_200.0))
    }

    @Test
    fun formatsMetricDistancesWithLocaleDecimals() {
        assertEquals("850 M", RouteFormat.distance(847.0, SpeedUnit.KMH, spanish))
        assertEquals("6,1 KM", RouteFormat.distance(6_123.0, SpeedUnit.KMH, spanish))
        assertEquals("6.1 KM", RouteFormat.distance(6_123.0, SpeedUnit.KMH, Locale.US))
        assertEquals("150 KM", RouteFormat.distance(150_400.0, SpeedUnit.KMH, spanish))
    }

    @Test
    fun formatsImperialDistances() {
        assertEquals("3.8 MI", RouteFormat.distance(6_123.0, SpeedUnit.MPH, Locale.US))
        assertEquals("160 FT", RouteFormat.distance(50.0, SpeedUnit.MPH, Locale.US))
        assertEquals("124 MI", RouteFormat.distance(200_000.0, SpeedUnit.MPH, Locale.US))
    }

    @Test
    fun picksInstructionLanguage() {
        assertEquals("es-ES", RouteFormat.valhallaLanguage(spanish))
        assertEquals("en-US", RouteFormat.valhallaLanguage(Locale.UK))
    }
}
