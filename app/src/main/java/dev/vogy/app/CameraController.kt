package dev.vogy.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Manages the full lifecycle of the dashcam connection, mirroring the sequence
 * that the original VOGE app uses (reverse-engineered from its APK):
 *
 *  1. Bind process to Wi-Fi network (ensures UDP/RTSP packets are not routed to cellular)
 *  2. Discover camera on HTTP port 80 (static IP 192.72.1.1, gateway fallback)
 *  3. HTTP init sequence — SD card check, MJPEG status, firmware version, cammenu.xml
 *  4. Dynamic RTSP stream discovery — queries Camera.Preview.RTSP.av to resolve /liveRTSP/av1 or /liveRTSP/v1
 *  5. Status socket — opens TCP connection to port 2020 for camera events (GBK encoded)
 *  6. Heartbeat — periodic HTTP GET (Camera.Menu.HeartBeat) to prevent camera timeout
 *  7. RTSP stream handover to ExoPlayer
 *  8. Connection monitor + auto-reconnect on disconnect
 */
class CameraController(private val context: Context, private val listener: Listener) {

    companion object {
        private const val TAG = "CameraController"

        // Network targets
        private const val CAMERA_HOST = "192.72.1.1"
        private const val HTTP_PORT = 80
        private const val STATUS_PORT = 2020
        private const val RTSP_PORT = 554
        private const val DEFAULT_RTSP_PATH = "/liveRTSP/v1"
        private const val CGI = "/cgi-bin/Config.cgi"

        // Timeouts & intervals
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HTTP_READ_TIMEOUT_MS = 8_000
        private const val RETRY_DELAY_MS = 4_000L
        private const val MONITOR_INTERVAL_MS = 4_000L
        private const val HEARTBEAT_INTERVAL_SEC = 10L
    }

    // ─── Public callback interface ─────────────────────────────────────────────

    interface Listener {
        /** Arbitrary status text for the connection-state label. */
        fun onStatusChanged(message: String)
        /** Camera is reachable and init has completed. */
        fun onCameraConnected(host: String, port: Int)
        /** A decoded JPEG frame from an MJPEG stream. */
        fun onMjpegFrame(bitmap: Bitmap)
        /** RTSP URL is ready for ExoPlayer. */
        fun onRtspAvailable(url: String)
        /** Camera is no longer reachable. */
        fun onCameraDisconnected()
        /** Dashcam reports video recording state (started/stopped). */
        fun onRecordingStateChanged(isRecording: Boolean) {}
    }

    // ─── State ────────────────────────────────────────────────────────────────

    @Volatile private var stopped = false
    @Volatile private var running = false
    @Volatile private var statusSocket: Socket? = null
    @Volatile private var activeHost: String? = null
    private var rtspProxy: RtspProxy? = null

    private var streamExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // streamExecutor is held by the connection monitor loop while connected, so commands get their own thread
    private var commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mjpegReader = MjpegReader(CAMERA_HOST, STATUS_PORT)

    private var heartbeatPool: ScheduledThreadPoolExecutor? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /** Starts connecting; no-op if a session is already running (the activity may re-attach to it). */
    fun start() {
        if (running) return
        running = true
        stopped = false
        if (streamExecutor.isShutdown) streamExecutor = Executors.newSingleThreadExecutor()
        if (commandExecutor.isShutdown) commandExecutor = Executors.newSingleThreadExecutor()
        scheduleConnect(0L)
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        running = false
        stopped = true
        activeHost = null
        rtspProxy?.stop()
        rtspProxy = null
        stopStatusSocket()
        mjpegReader.stop()
        cancelHeartbeat()
        unbindNetwork()
        mainHandler.removeCallbacksAndMessages(null)
        streamExecutor.shutdown()
        commandExecutor.shutdown()
    }

    // ─── Video Recording Controls ─────────────────────────────────────────────

    /**
     * @param allowToggle allow the "value=record" fallback, which toggles recording. Must be false
     * when re-asserting a recording that may already be running, or it would switch it off.
     */
    fun startRecording(allowToggle: Boolean = true, callback: ((Boolean) -> Unit)? = null) {
        if (commandExecutor.isShutdown) return
        commandExecutor.execute {
            val host = activeHost ?: discoverHost() ?: CAMERA_HOST
            val ok = sendRecordCommand(host, start = true, allowToggle = allowToggle)
            mainHandler.post { callback?.invoke(ok) }
        }
    }

    fun stopRecording(callback: ((Boolean) -> Unit)? = null) {
        if (commandExecutor.isShutdown) return
        commandExecutor.execute {
            val host = activeHost ?: discoverHost() ?: CAMERA_HOST
            val ok = sendRecordCommand(host, start = false, allowToggle = true)
            mainHandler.post { callback?.invoke(ok) }
        }
    }

    private fun sendRecordCommand(host: String, start: Boolean, allowToggle: Boolean): Boolean {
        val cmd = if (start) "start" else "stop"
        val r1 = httpGet(host, "$CGI?action=set&property=Video&value=$cmd")
        val r2 = httpGet(host, "$CGI?action=set&property=Camera.Record.Video&value=$cmd")
        val r3 = if (allowToggle && r1 == null && r2 == null) {
            httpGet(host, "$CGI?action=set&property=Video&value=record")
        } else null
        Log.d(TAG, "sendRecordCommand($cmd): r1=$r1, r2=$r2, r3=$r3")
        return r1 != null || r2 != null || r3 != null
    }

    // ─── Connection orchestration ──────────────────────────────────────────────

    private fun scheduleConnect(delayMs: Long) {
        if (stopped) return
        mainHandler.postDelayed({
            if (!stopped) streamExecutor.execute { connectAndStream() }
        }, delayMs)
    }

    /**
     * Main connection flow — runs entirely on [streamExecutor].
     * Replicates the exact communication pattern of the original VOGE app.
     */
    private fun connectAndStream() {
        if (stopped) return
        postStatus(context.getString(R.string.camera_status_searching))

        // ── Step 1: Bind process to Wi-Fi network (crucial on Android 12-15) ──
        bindToWifiNetwork()

        // ── Step 2: Discover camera host IP ──────────────────────────────────
        val host = discoverHost()
        if (host == null) {
            activeHost = null
            unbindNetwork()
            postDisconnected()
            if (!stopped) scheduleConnect(RETRY_DELAY_MS)
            return
        }
        activeHost = host

        // ── Step 3: HTTP init sequence (mirrors original app endpoints) ──────
        postStatus(context.getString(R.string.camera_status_starting))
        val initOk = runHttpInit(host)
        if (!initOk || stopped) {
            activeHost = null
            unbindNetwork()
            postDisconnected()
            if (!stopped) scheduleConnect(RETRY_DELAY_MS)
            return
        }

        // ── Step 4: Resolve RTSP stream URL dynamically ──────────────────────
        val rtspPath = queryRtspStreamPath(host)
        Log.i(TAG, "Selected RTSP path: $rtspPath on host $host")

        // ── Step 5: Notify UI — camera connected ─────────────────────────────
        postConnected(host, RTSP_PORT)

        // ── Step 6: Start heartbeat (Camera.Menu.HeartBeat every 10s) ─────────
        startHeartbeat(host)

        // ── Step 7: Open raw status socket on port 2020 (non-blocking) ───────
        startStatusSocket(host)

        // ── Step 8: Start RTSP streaming via local sanitizing proxy ───────────
        postStatus(context.getString(R.string.camera_status_video))
        rtspProxy?.stop()
        val proxy = RtspProxy(targetHost = host, targetPort = RTSP_PORT).also { rtspProxy = it }
        val localPort = proxy.start()
        val proxyUrl = "rtsp://127.0.0.1:$localPort$rtspPath"
        Log.i(TAG, "RTSP stream routed via proxy: $proxyUrl -> $host:$RTSP_PORT$rtspPath")
        postRtspAvailable(proxyUrl)

        // ── Step 9: Monitor connection health ────────────────────────────────
        while (!stopped) {
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!isWifiConnected()) {
                Log.d(TAG, "Camera Wi-Fi disconnected — disconnecting")
                break
            }
        }

        // ── Step 10: Cleanup & reconnect ─────────────────────────────────────
        activeHost = null
        rtspProxy?.stop()
        rtspProxy = null
        stopStatusSocket()
        cancelHeartbeat()
        unbindNetwork()
        if (!stopped) {
            postDisconnected()
            scheduleConnect(RETRY_DELAY_MS)
        }
    }

    // ─── Network binding (Android Wi-Fi routing without Internet) ─────────────

    private fun bindToWifiNetwork(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val bound = cm.bindProcessToNetwork(network)
                    Log.d(TAG, "Bound process to Wi-Fi network: $network (success=$bound)")
                    return bound
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error binding process to Wi-Fi: ${e.message}")
            false
        }
    }

    private fun unbindNetwork() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.bindProcessToNetwork(null)
            Log.d(TAG, "Unbound process from Wi-Fi network")
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding network: ${e.message}")
        }
    }

    // ─── Camera discovery ─────────────────────────────────────────────────────

    private fun discoverHost(): String? {
        if (isHostReachable(CAMERA_HOST, HTTP_PORT)) return CAMERA_HOST
        val gw = getGatewayIp()
        if (gw != null && gw != CAMERA_HOST && isHostReachable(gw, HTTP_PORT)) return gw
        Log.d(TAG, "Camera not reachable on $CAMERA_HOST:$HTTP_PORT or gateway")
        return null
    }

    // ─── HTTP init sequence ───────────────────────────────────────────────────

    private fun runHttpInit(host: String): Boolean {
        val sd = httpGet(host, "$CGI?action=get&property=Camera.Menu.SD0")
        if (stopped) return false
        if (sd == null) {
            val xml = httpGet(host, "/cammenu.xml")
            if (xml == null) {
                Log.d(TAG, "Host $host is not a VOGE camera (endpoints rejected)")
                return false
            }
        }

        httpGet(host, "$CGI?action=get&property=Camera.Preview.MJPEG.status.*")
        if (stopped) return false

        val fw = httpGet(host, "$CGI?action=get&property=Camera.Menu.FWversion")
        Log.d(TAG, "Camera firmware: $fw")
        if (stopped) return false

        httpGet(host, "/cammenu.xml")
        return true
    }

    /**
     * Resolves the RTSP stream URL using the Novatek cgi-bin command
     * (matching original app's a0/f.smali implementation).
     */
    private fun queryRtspStreamPath(host: String): String {
        val resp = httpGet(host, "$CGI?action=get&property=Camera.Preview.RTSP.av")
        Log.d(TAG, "Camera.Preview.RTSP.av query response: $resp")
        if (!resp.isNullOrBlank() && resp.contains("Camera.Preview.RTSP.av=")) {
            val value = resp.substringAfter("Camera.Preview.RTSP.av=").trim().lines().firstOrNull()?.trim()
            when (value) {
                "1" -> return "/liveRTSP/av1"
                "2" -> return "/liveRTSP/v1"
                "3" -> return "/liveRTSP/av2"
                "4", "4/5" -> return "/liveRTSP/av4"
                "5" -> return "/liveRTSP/av5"
                else -> if (!value.isNullOrBlank()) return "/liveRTSP/av1"
            }
        }
        return DEFAULT_RTSP_PATH
    }

    // ─── Status socket on port 2020 ───────────────────────────────────────────

    /**
     * Connects to port 2020 to read status events (POWER, WIFI, VideoRecord)
     * and maintain camera session, mirroring m4/d$b.smali in the original app.
     * Note: on MS8336 dashcams port 2020 may be closed; runs asynchronously.
     */
    private fun startStatusSocket(host: String) {
        Thread({
            try {
                val s = Socket()
                statusSocket = s
                s.connect(InetSocketAddress(host, STATUS_PORT), 1_500)
                val reader = s.getInputStream().bufferedReader(Charset.forName("GBK"))
                Log.d(TAG, "Connected to camera status socket on port $STATUS_PORT")
                while (!stopped && s.isConnected && !s.isClosed) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "Camera event: $line")
                    if (line.contains("VideoRecord=1") || line.contains("Record=1")) {
                        mainHandler.post { listener.onRecordingStateChanged(true) }
                    } else if (line.contains("VideoRecord=0") || line.contains("Record=0")) {
                        mainHandler.post { listener.onRecordingStateChanged(false) }
                    }
                }
            } catch (_: Exception) {
                // Port 2020 not available on this camera model
            } finally {
                stopStatusSocket()
            }
        }, "StatusSocket").apply { isDaemon = true; start() }
    }

    private fun stopStatusSocket() {
        try { statusSocket?.close() } catch (_: Exception) {}
        statusSocket = null
    }

    // ─── Heartbeat ────────────────────────────────────────────────────────────

    private fun startHeartbeat(host: String) {
        val pool = ScheduledThreadPoolExecutor(1).also { heartbeatPool = it }
        heartbeatFuture = pool.scheduleWithFixedDelay(
            {
                if (!stopped) {
                    httpGet(host, "$CGI?action=get&property=Camera.Menu.HeartBeat")
                }
            },
            HEARTBEAT_INTERVAL_SEC,
            HEARTBEAT_INTERVAL_SEC,
            TimeUnit.SECONDS,
        )
    }

    private fun cancelHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
        heartbeatPool?.shutdownNow()
        heartbeatPool = null
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private fun httpGet(host: String, path: String): String? {
        if (stopped) return null
        return try {
            val conn = URL("http://$host$path").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = HTTP_READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Lavf/58.29.100")
            conn.instanceFollowRedirects = false

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun isHostReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getGatewayIp(): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gateway = wm.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        return Formatter.formatIpAddress(gateway)
    }

    // ─── Main-thread dispatchers ──────────────────────────────────────────────

    private fun postStatus(msg: String) =
        mainHandler.post { if (!stopped) listener.onStatusChanged(msg) }

    private fun postConnected(host: String, port: Int) =
        mainHandler.post { if (!stopped) listener.onCameraConnected(host, port) }

    private fun postRtspAvailable(url: String) =
        mainHandler.post { if (!stopped) listener.onRtspAvailable(url) }

    private fun postDisconnected() =
        mainHandler.post { if (!stopped) listener.onCameraDisconnected() }
}
