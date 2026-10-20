package dev.vogy.app

import kotlin.math.roundToInt

/**
 * Speed measurement units supported by OpenVOGE.
 *
 * Provides display strings, gauge scale endpoints, and conversion utilities
 * between KM/H and MP/H.
 */
enum class SpeedUnit(
    val badgeText: String,
    val suffix: String,
    val maxGaugeSpeed: Int
) {
    KMH(badgeText = "KM/H", suffix = "km/h", maxGaugeSpeed = 180),
    MPH(badgeText = "MP/H", suffix = "mp/h", maxGaugeSpeed = 120);

    fun convertFromKmh(kmh: Int): Int {
        return when (this) {
            KMH -> kmh
            MPH -> (kmh * KMH_TO_MPH).roundToInt()
        }
    }

    fun convertFromKmhString(kmhStr: String?): String {
        if (kmhStr.isNullOrBlank()) return "-- $suffix"
        val num = kmhStr.toDoubleOrNull()
        return if (num != null) {
            val converted = when (this) {
                KMH -> num.roundToInt()
                MPH -> (num * KMH_TO_MPH).roundToInt()
            }
            "$converted $suffix"
        } else {
            "$kmhStr $suffix"
        }
    }

    companion object {
        const val KMH_TO_MPH = 0.621371192

        fun fromString(value: String?): SpeedUnit = when (value?.uppercase()) {
            "MPH", "MP/H" -> MPH
            else -> KMH
        }
    }
}
