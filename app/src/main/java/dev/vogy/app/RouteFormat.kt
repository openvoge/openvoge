package dev.vogy.app

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Short, glanceable route numbers for the map UI: "18 MIN", "1 H 05", "6,1 KM", "850 M", "3.8 MI". */
object RouteFormat {

    private const val METERS_PER_MILE = 1_609.344
    private const val METERS_PER_FOOT = 0.3048

    fun duration(seconds: Double): String {
        val minutes = (seconds / 60.0).roundToLong().coerceAtLeast(1)
        if (minutes < 60) return "$minutes MIN"
        return String.format(Locale.ROOT, "%d H %02d", minutes / 60, minutes % 60)
    }

    /** Distance in the rider's unit: metric for KM/H, imperial for MP/H. */
    fun distance(meters: Double, unit: SpeedUnit, locale: Locale): String = when (unit) {
        SpeedUnit.KMH -> when {
            meters < 1_000 -> "${(meters / 10).roundToInt() * 10} M"
            meters < 100_000 -> String.format(locale, "%.1f KM", meters / 1_000)
            else -> "${(meters / 1_000).roundToInt()} KM"
        }
        SpeedUnit.MPH -> {
            val miles = meters / METERS_PER_MILE
            when {
                miles < 0.1 -> "${((meters / METERS_PER_FOOT) / 10).roundToInt() * 10} FT"
                miles < 100 -> String.format(locale, "%.1f MI", miles)
                else -> "${miles.roundToInt()} MI"
            }
        }
    }

    /** Valhalla instruction language for the app language. */
    fun valhallaLanguage(locale: Locale): String = if (locale.language == "es") "es-ES" else "en-US"
}
