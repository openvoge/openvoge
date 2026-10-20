package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedUnitTest {

    @Test
    fun testDefaultProperties() {
        assertEquals("KM/H", SpeedUnit.KMH.badgeText)
        assertEquals("km/h", SpeedUnit.KMH.suffix)
        assertEquals(180, SpeedUnit.KMH.maxGaugeSpeed)

        assertEquals("MP/H", SpeedUnit.MPH.badgeText)
        assertEquals("mp/h", SpeedUnit.MPH.suffix)
        assertEquals(120, SpeedUnit.MPH.maxGaugeSpeed)
    }

    @Test
    fun testConvertFromKmh() {
        assertEquals(0, SpeedUnit.KMH.convertFromKmh(0))
        assertEquals(50, SpeedUnit.KMH.convertFromKmh(50))
        assertEquals(100, SpeedUnit.KMH.convertFromKmh(100))
        assertEquals(180, SpeedUnit.KMH.convertFromKmh(180))

        assertEquals(0, SpeedUnit.MPH.convertFromKmh(0))
        assertEquals(31, SpeedUnit.MPH.convertFromKmh(50))
        assertEquals(62, SpeedUnit.MPH.convertFromKmh(100))
        assertEquals(112, SpeedUnit.MPH.convertFromKmh(180))
    }

    @Test
    fun testConvertFromKmhString() {
        assertEquals("-- km/h", SpeedUnit.KMH.convertFromKmhString(null))
        assertEquals("-- km/h", SpeedUnit.KMH.convertFromKmhString(""))
        assertEquals("50 km/h", SpeedUnit.KMH.convertFromKmhString("50"))

        assertEquals("-- mp/h", SpeedUnit.MPH.convertFromKmhString(null))
        assertEquals("-- mp/h", SpeedUnit.MPH.convertFromKmhString(""))
        assertEquals("31 mp/h", SpeedUnit.MPH.convertFromKmhString("50"))
        assertEquals("62 mp/h", SpeedUnit.MPH.convertFromKmhString("100"))
    }

    @Test
    fun testFromString() {
        assertEquals(SpeedUnit.KMH, SpeedUnit.fromString("KMH"))
        assertEquals(SpeedUnit.KMH, SpeedUnit.fromString("km/h"))
        assertEquals(SpeedUnit.KMH, SpeedUnit.fromString(null))

        assertEquals(SpeedUnit.MPH, SpeedUnit.fromString("MPH"))
        assertEquals(SpeedUnit.MPH, SpeedUnit.fromString("mp/h"))
        assertEquals(SpeedUnit.MPH, SpeedUnit.fromString("MP/H"))
    }
}
