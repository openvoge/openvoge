package dev.vogy.app

import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener

/**
 * Which way the location dot points: where the bike is going while riding (GPS course), where
 * the phone faces while stopped (compass). The phone's compass alone is a poor guide on a
 * motorcycle: the engine and frame throw it off, and in a pocket it follows the pocket.
 */
class RiderHeading {

    companion object {
        /** Start trusting the GPS course from about 11 km/h… */
        const val START_USING_COURSE_MPS = 3.0f
        /** …and keep it down to about 5 km/h, so it doesn't flip back and forth at a crawl. */
        const val STOP_USING_COURSE_MPS = 1.5f
        /** An older fix says nothing about where the bike points now. */
        const val MAX_COURSE_AGE_MS = 5_000L
    }

    var usingCourse = false
        private set

    /** [course] and [speedMps] are null when the fix doesn't have them. */
    fun choose(compassHeading: Float, course: Float?, speedMps: Float?, fixAgeMs: Long): Float {
        val threshold = if (usingCourse) STOP_USING_COURSE_MPS else START_USING_COURSE_MPS
        usingCourse = course != null && speedMps != null && fixAgeMs <= MAX_COURSE_AGE_MS && speedMps >= threshold
        return if (usingCourse && course != null) course else compassHeading
    }
}

/**
 * MapLibre compass engine for the location dot: wraps the phone's own [sensorCompass] and swaps
 * in the GPS course from [lastFix] while riding. The course is also pushed once a second, so the
 * dot turns with the bike even on phones without compass sensors.
 */
class RiderHeadingCompassEngine(
    private val sensorCompass: CompassEngine,
    private val lastFix: () -> Location?,
) : CompassEngine, CompassListener {

    private val listeners = mutableListOf<CompassListener>()
    private val heading = RiderHeading()
    private val handler = Handler(Looper.getMainLooper())
    private val courseTick = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) return
            val current = currentHeading(sensorCompass.lastHeading)
            if (heading.usingCourse) notifyHeading(current)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun addCompassListener(listener: CompassListener) {
        if (listeners.isEmpty()) {
            sensorCompass.addCompassListener(this)
            handler.postDelayed(courseTick, 1_000L)
        }
        if (listener !in listeners) listeners += listener
    }

    override fun removeCompassListener(listener: CompassListener) {
        listeners -= listener
        if (listeners.isEmpty()) {
            sensorCompass.removeCompassListener(this)
            handler.removeCallbacks(courseTick)
        }
    }

    override fun getLastHeading(): Float = currentHeading(sensorCompass.lastHeading)

    override fun getLastAccuracySensorStatus(): Int =
        if (heading.usingCourse) SensorManager.SENSOR_STATUS_ACCURACY_HIGH else sensorCompass.lastAccuracySensorStatus

    override fun onCompassChanged(userHeading: Float) = notifyHeading(currentHeading(userHeading))

    override fun onCompassAccuracyChange(compassStatus: Int) {
        val status = if (heading.usingCourse) SensorManager.SENSOR_STATUS_ACCURACY_HIGH else compassStatus
        listeners.toList().forEach { it.onCompassAccuracyChange(status) }
    }

    private fun currentHeading(compassHeading: Float): Float {
        val fix = lastFix()
        val ageMs = fix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 } ?: Long.MAX_VALUE
        return heading.choose(
            compassHeading,
            course = fix?.takeIf { it.hasBearing() }?.bearing,
            speedMps = fix?.takeIf { it.hasSpeed() }?.speed,
            fixAgeMs = ageMs,
        )
    }

    private fun notifyHeading(value: Float) = listeners.toList().forEach { it.onCompassChanged(value) }
}
