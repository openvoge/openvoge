package dev.vogy.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.google.android.material.color.MaterialColors
import java.util.Locale

/**
 * Builds the MapLibre style for the MAP section from `assets/map/openvoge_neo.json`.
 *
 * The asset is a template: `{{name}}` placeholders are replaced with the light or dark palette,
 * the user's accent color and the label language. The base map stays neutral: the accent is kept
 * for what's the rider's own (position, route) plus fuel stations, so nothing competes with a route.
 * Tiles, fonts and data come from OpenFreeMap / OpenStreetMap: no account or API key needed.
 */
object NeoMapStyle {

    private const val TEMPLATE_ASSET = "map/openvoge_neo.json"

    /** Colors that depend only on light/dark mode. */
    val LIGHT: Map<String, String> = mapOf(
        "land" to "#ECE8DF",
        "wood" to "#CFDDB0",
        "grass" to "#DAE5BF",
        "park" to "#D3E3AE",
        "sand" to "#E8DDBF",
        "water" to "#8FC6EE",
        "waterLabel" to "#1D4F7A",
        "building" to "#DDD6C8",
        "buildingLine" to "#B5AC9B",
        "road" to "#FFFFFF",
        "roadMajor" to "#FFFFFF",
        "roadMotorway" to "#FFE9A8",
        "casing" to "#000000",
        "rail" to "#6B6B6B",
        "boundary" to "#4A4A4A",
        "label" to "#000000",
        "labelMuted" to "#4A4A4A",
        "halo" to "#ECE8DF",
    )

    val DARK: Map<String, String> = mapOf(
        "land" to "#18191E",
        "wood" to "#1D2620",
        "grass" to "#1E2722",
        "park" to "#1F2B22",
        "sand" to "#24231E",
        "water" to "#1B3A57",
        "waterLabel" to "#8CB8DE",
        "building" to "#262A33",
        "buildingLine" to "#0E0F12",
        "road" to "#555B6B",
        "roadMajor" to "#7A8194",
        "roadMotorway" to "#A9AFBD",
        "casing" to "#000000",
        "rail" to "#6B7080",
        "boundary" to "#8A8F9C",
        "label" to "#F2F0EA",
        "labelMuted" to "#A3A6AE",
        "halo" to "#18191E",
    )

    /** Placeholders filled at runtime rather than from a palette. */
    val DYNAMIC_KEYS: Set<String> = setOf("accent", "lang", "fuelFallback")

    /** Style JSON for this activity's theme (night mode + accent overlay) and app language. */
    fun build(context: Context): String {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = MaterialColors.getColor(context, R.attr.neoAccent, Color.BLACK)
        val language = context.resources.configuration.locales[0].language
        val template = context.assets.open(TEMPLATE_ASSET).bufferedReader().use { it.readText() }
        return fill(
            template,
            (if (night) DARK else LIGHT) + mapOf(
                "accent" to hexColor(accent),
                "lang" to if (language == "es") "es" else "en",
                "fuelFallback" to jsonEscape(context.getString(R.string.map_fuel_fallback)),
            )
        )
    }

    /** Replaces every `{{key}}` with its value. Values must already be JSON-string safe. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            values[key] ?: error("Map style placeholder {{$key}} has no value")
        }

    fun placeholders(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    fun hexColor(color: Int): String = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)

    private fun jsonEscape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private val PLACEHOLDER = Regex("""\{\{(\w+)\}\}""")
}
