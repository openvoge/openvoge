package dev.vogy.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * App-scoped owner of the camera connection and of the user's intent to record.
 *
 * Lives outside [MainActivity] so a recording keeps going with the screen off, the app in the
 * background, or the activity closed — only an explicit Stop from the user ends it. While a
 * recording is active, [CameraRecordingService] keeps the process (heartbeat + Wi-Fi) alive.
 *
 * The activity attaches as [listener] while it exists and replays the cached state on attach.
 */
object CameraSession : CameraController.Listener {

    private const val TAG = "CameraSession"

    private var controller: CameraController? = null
    private var appContext: Context? = null

    /** Current UI, if any. Events are cached here and forwarded to it. */
    var listener: CameraController.Listener? = null

    var isConnected = false
        private set
    var lastHost: String? = null
        private set
    var lastPort = 0
        private set
    var lastRtspUrl: String? = null
        private set

    /** True from the user's Record tap until the user's Stop tap — nothing else clears it. */
    var userWantsRecording = false
        private set
    var recordingStartedAtMillis = 0L
        private set

    fun controller(context: Context): CameraController {
        appContext = context.applicationContext
        return controller ?: CameraController(context.applicationContext, this).also { controller = it }
    }

    fun startRecordingByUser(context: Context) {
        if (userWantsRecording) return
        userWantsRecording = true
        recordingStartedAtMillis = System.currentTimeMillis()
        sendStart(context, allowToggle = true)
        CameraRecordingService.start(context.applicationContext, recordingStartedAtMillis)
    }

    /** Stop requested by the user (in-app button or notification action). */
    fun stopRecordingByUser(context: Context) {
        if (!userWantsRecording) return
        userWantsRecording = false
        recordingStartedAtMillis = 0L
        CameraRecordingService.stop(context.applicationContext)
        val ctrl = controller(context)
        ctrl.stopRecording { ok ->
            Log.d(TAG, "Camera stopRecording result: $ok")
            // No screen left to show the stream: release the connection too
            if (listener == null) ctrl.stop()
        }
        listener?.onRecordingStateChanged(false)
    }

    /** Called when the activity goes away; keeps the session only if a recording is in progress. */
    fun onUiDestroyed(ui: CameraController.Listener, context: Context) {
        if (listener === ui) listener = null
        if (!userWantsRecording) controller(context).stop()
    }

    private fun sendStart(context: Context, allowToggle: Boolean) {
        controller(context).startRecording(allowToggle) { ok ->
            Log.d(TAG, "Camera startRecording result: $ok (allowToggle=$allowToggle)")
        }
    }

    // ─── CameraController.Listener: cache + forward ───────────────────────────

    override fun onStatusChanged(message: String) {
        listener?.onStatusChanged(message)
    }

    override fun onCameraConnected(host: String, port: Int) {
        isConnected = true
        lastHost = host
        lastPort = port
        listener?.onCameraConnected(host, port)
        // Link came back while the user still wants to record: make sure the camera is recording
        if (userWantsRecording) appContext?.let { sendStart(it, allowToggle = false) }
    }

    override fun onMjpegFrame(bitmap: Bitmap) {
        listener?.onMjpegFrame(bitmap)
    }

    override fun onRtspAvailable(url: String) {
        lastRtspUrl = url
        listener?.onRtspAvailable(url)
    }

    override fun onCameraDisconnected() {
        isConnected = false
        lastRtspUrl = null
        // Keep userWantsRecording: the controller reconnects on its own and resumes recording
        listener?.onCameraDisconnected()
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        if (!isRecording && userWantsRecording) {
            // Camera stopped without the user asking (e.g. after a reboot): ask it to record again
            Log.w(TAG, "Camera reported recording stopped while user still wants it — restarting")
            appContext?.let { sendStart(it, allowToggle = false) }
            return
        }
        listener?.onRecordingStateChanged(isRecording)
    }
}
