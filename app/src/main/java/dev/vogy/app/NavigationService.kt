package dev.vogy.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps navigation running with the screen off or the app in the background: GPS fixes go to
 * [NavigationSession], a partial wake lock keeps the dashboard frames on time, and an ongoing
 * notification shows the next maneuver with an End action. Simulated rides skip the GPS.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 4310
        private const val ACTION_STOP = "dev.vogy.app.action.STOP_NAVIGATION"
        private const val EXTRA_SIMULATED = "simulated"
        private const val FIX_INTERVAL_MS = 1_000L
        private const val LOG_TAG = "NAV"

        @Volatile
        private var running: NavigationService? = null

        fun startIntent(context: Context, simulated: Boolean): Intent =
            Intent(context, NavigationService::class.java).putExtra(EXTRA_SIMULATED, simulated)

        fun stopIntent(context: Context): Intent = Intent(context, NavigationService::class.java)

        /** Arrival: the session is done, the service goes away. */
        fun stopSelfIfRunning() {
            running?.stopSelf()
        }
    }

    private val scope = MainScope()
    private var stateJob: Job? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val locationListener = LocationListener { NavigationSession.onLocation(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Notification "End" is an explicit stop, even with no activity alive
            NavigationSession.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (NavigationSession.state.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(NavigationSession.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: SecurityException) {
            // No location permission: navigation still works while the app is on screen
            Log.w(LOG_TAG, "Can't run in the foreground: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        val simulated = intent?.getBooleanExtra(EXTRA_SIMULATED, false) ?: false
        if (simulated) stopLocationUpdates() else startLocationUpdates()

        stateJob?.cancel()
        stateJob = scope.launch {
            NavigationSession.state.collect { state ->
                if (state == null) stopSelf()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        stopLocationUpdates()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        locationManager = manager
        // One provider only, so fixes don't alternate between sources: fused when the platform has it
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        try {
            manager.requestLocationUpdates(provider, FIX_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No location updates from $provider: $e")
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenVOGE:Navigation")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun buildNotification(state: NavigationSession.State?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val end = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unit = SpeedUnit.fromString(
            getSharedPreferences("ui_prefs", MODE_PRIVATE).getString("speed_unit", SpeedUnit.KMH.name)
        )
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val maneuver = state?.nextManeuver
        val progress = state?.progress
        val title = when {
            state == null -> getString(R.string.nav_channel)
            state.arrived -> getString(R.string.nav_arrived, state.destination.name)
            state.rerouting -> getString(R.string.nav_rerouting)
            progress == null || maneuver == null -> state.destination.name
            else -> "${RouteFormat.distance(progress.distanceToManeuverMeters, unit, locale)} · ${maneuver.instruction}"
        }
        val text = progress?.let {
            "${RouteFormat.duration(it.durationRemainingSeconds)} · ${RouteFormat.distance(it.distanceRemainingMeters, unit, locale)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_map_neo)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (state?.simulated == true) getString(R.string.nav_simulation) else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.nav_end), end)
            .build()
    }
}
