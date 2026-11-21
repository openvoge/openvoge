package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeoMapStyleTest {

    // Gradle runs unit tests from the module directory
    private val template = File("src/main/assets/map/openvoge_neo.json").readText()

    private val dynamicValues = mapOf(
        "accent" to "#C8F031",
        "lang" to "es",
        "fuelFallback" to "Gasolinera"
    )

    @Test
    fun everyPlaceholderHasAValueInBothThemes() {
        val needed = NeoMapStyle.placeholders(template)
        assertTrue("template should use placeholders", needed.isNotEmpty())
        for ((theme, palette) in mapOf("light" to NeoMapStyle.LIGHT, "dark" to NeoMapStyle.DARK)) {
            val missing = needed - palette.keys - NeoMapStyle.DYNAMIC_KEYS
            assertTrue("$theme palette is missing $missing", missing.isEmpty())
        }
        assertEquals(NeoMapStyle.DYNAMIC_KEYS, dynamicValues.keys)
    }

    @Test
    fun lightAndDarkDefineTheSameColors() {
        assertEquals(NeoMapStyle.LIGHT.keys, NeoMapStyle.DARK.keys)
    }

    @Test
    fun filledStyleHasNoPlaceholdersLeft() {
        for (palette in listOf(NeoMapStyle.LIGHT, NeoMapStyle.DARK)) {
            val filled = NeoMapStyle.fill(template, palette + dynamicValues)
            assertFalse(filled.contains("{{"))
            assertTrue(filled.contains("\"name:es\""))
            assertTrue(filled.contains("\"#C8F031\""))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun unknownPlaceholderFailsLoudly() {
        NeoMapStyle.fill("{\"a\": \"{{nope}}\"}", emptyMap())
    }

    @Test
    fun hexColorDropsAlpha() {
        assertEquals("#C8F031", NeoMapStyle.hexColor(0xFFC8F031.toInt()))
        assertEquals("#2B50E6", NeoMapStyle.hexColor(0x802B50E6.toInt()))
    }
}
