package dev.vogy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while the motorcycle camera is recording with the screen off.
 *
 * The camera records to its own SD card; the phone only has to keep the session alive
 * (heartbeat + Wi-Fi link). This foreground service holds a partial wake lock and a
 * Wi-Fi lock so Android doesn't freeze the process or drop the camera network, and
 * shows an ongoing "Recording" notification with a Stop action.
 */
class CameraRecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "camera_recording"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "dev.vogy.app.action.START_RECORDING_SESSION"
        private const val ACTION_STOP = "dev.vogy.app.action.STOP_RECORDING"
        private const val EXTRA_STARTED_AT = "started_at"

        fun start(context: Context, startedAtMillis: Long) {
            val intent = Intent(context, CameraRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STARTED_AT, startedAtMillis)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraRecordingService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Notification "Stop" is an explicit user stop, even with no activity alive
            CameraSession.stopRecordingByUser(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(startedAt),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun buildNotification(startedAt: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.camera_recording_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopRecording = PendingIntent.getService(
            this, 1,
            Intent(this, CameraRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_neo)
            .setContentTitle(getString(R.string.camera_recording_notification_title))
            .setContentText(getString(R.string.camera_recording_notification_text))
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.camera_btn_stop), stopRecording)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenVOGE:CameraRecording")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock?.isHeld != true) {
            // Low-latency locks only apply with the screen on; high-perf keeps the radio awake with it off
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "OpenVOGE:CameraWifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }
}
