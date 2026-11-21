package dev.vogy.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transparent local RTSP proxy that bridges ExoPlayer and dashcams (AIT/MStar/Novatek)
 * running non-standard RTSP implementations.
 *
 * Specific fixes applied:
 * 1. Sanitizes `Session` header format:
 *    The camera sends `Session: <id>; timeout = 60` (spaces around `=`).
 *    Media3's strict RFC regex expects `timeout=(\d+)` without spaces.
 *    The proxy rewrites `; timeout = 60` -> `;timeout=60` so Media3 parses it cleanly.
 * 2. Sanitizes `Range` header:
 *    Fixes non-compliant `npt=0Z-` -> `npt=0.000-`.
 * 3. Bidirectional URI translation:
 *    Rewrites `127.0.0.1:<port>` <-> `targetHost` in requests, responses, and SDP bodies.
 * 4. RTP relay + recording tap:
 *    SETUP's `client_port` is rewritten so the camera sends RTP/RTCP to sockets owned by the
 *    proxy. Packets are forwarded to ExoPlayer's ports (when [forwardToPlayer]) and video
 *    packets are handed to [recorder]: one camera session feeds both preview and recording.
 */
class RtspProxy(
    private val targetHost: String = "192.72.1.1",
    private val targetPort: Int = 554,
    /** Receives the H.264 RTP stream while recording. */
    private val recorder: StreamRecorder? = null
) {
    companion object {
        private const val TAG = "RtspProxy"
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var proxyThread: Thread? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val relays = ConcurrentHashMap.newKeySet<RtpRelay>()

    /** Off while nothing is on screen: the player isn't decoding, so don't send it packets. */
    @Volatile var forwardToPlayer = true

    /** Proxy RTP port -> ExoPlayer's original client_port pair, while a SETUP is in flight. */
    private val pendingSetups = ConcurrentHashMap<Int, Pair<Int, Int>>()

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        val ss = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        isRunning.set(true)

        proxyThread = Thread({
            Log.i(TAG, "RTSP Proxy listening on 127.0.0.1:${ss.localPort} -> $targetHost:$targetPort")
            while (isRunning.get() && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    Log.i(TAG, "ExoPlayer connected to RTSP Proxy")
                    Thread({ handleConnection(client) }, "RtspProxy-Session").start()
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w(TAG, "Proxy accept error: ${e.message}")
                    break
                }
            }
        }, "RtspProxy-Listener").apply {
            isDaemon = true
            start()
        }

        return ss.localPort
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        activeSockets.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        relays.forEach { it.close() }
        relays.clear()
        pendingSetups.clear()

        proxyThread?.interrupt()
        proxyThread = null
    }

    // ─── RTP relay ───────────────────────────────────────────────────────────

    private val clientPortRegex = Regex("client_port=(\\d+)-(\\d+)")

    /** `client_port=A-B` (ExoPlayer) -> `client_port=P-(P+1)` (proxy relay sockets). */
    private fun interceptSetupTransport(header: String): String {
        if (header.contains("TCP", ignoreCase = true)) return header // interleaved: nothing to relay
        val match = clientPortRegex.find(header) ?: return header
        val playerRtp = match.groupValues[1].toInt()
        val playerRtcp = match.groupValues[2].toInt()
        val relay = openRelay(playerRtp, playerRtcp) ?: return header
        relays.add(relay)
        pendingSetups[relay.rtpPort] = playerRtp to playerRtcp
        return header.replace(match.value, "client_port=${relay.rtpPort}-${relay.rtpPort + 1}")
    }

    private fun restoreSetupTransport(header: String): String {
        val match = clientPortRegex.find(header) ?: return header
        val original = pendingSetups.remove(match.groupValues[1].toInt()) ?: return header
        return header.replace(match.value, "client_port=${original.first}-${original.second}")
    }

    /** Pulls the H.264 payload type and parameter sets out of the DESCRIBE answer. */
    private fun readSdp(sdp: String) {
        val rtpmap = Regex("a=rtpmap:(\\d+)\\s+H264/", RegexOption.IGNORE_CASE).find(sdp)
        rtpmap?.let { recorder?.h264PayloadType = it.groupValues[1].toInt() }
        val sprop = Regex("sprop-parameter-sets=([^;\\s]+)").find(sdp)?.groupValues?.get(1)
        recorder?.setSdpParameterSets(sprop)
        Log.i(TAG, "SDP: H264 pt=${rtpmap?.groupValues?.get(1)} sprop=${sprop != null}")
    }

    /** Binds the even/odd UDP port pair RTP requires. */
    private fun openRelay(playerRtp: Int, playerRtcp: Int): RtpRelay? {
        repeat(10) {
            val rtp = runCatching { DatagramSocket(0) }.getOrNull() ?: return@repeat
            if (rtp.localPort % 2 != 0) {
                rtp.close()
                return@repeat
            }
            val rtcp = runCatching { DatagramSocket(rtp.localPort + 1) }.getOrNull()
            if (rtcp == null) {
                rtp.close()
                return@repeat
            }
            rtp.receiveBufferSize = 1 shl 20 // absorb keyframe bursts
            return RtpRelay(rtp, rtcp, playerRtp, playerRtcp)
        }
        return null
    }

    /**
     * The UDP pair the camera streams to. Each packet is forwarded to ExoPlayer and, for RTP,
     * offered to the recorder (a no-op unless recording). Threads block in receive(): no CPU idle.
     */
    private inner class RtpRelay(
        private val rtpSocket: DatagramSocket,
        private val rtcpSocket: DatagramSocket,
        playerRtpPort: Int,
        playerRtcpPort: Int
    ) {
        val rtpPort: Int get() = rtpSocket.localPort
        private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

        init {
            pump(rtpSocket, playerRtpPort, tapToRecorder = true)
            pump(rtcpSocket, playerRtcpPort, tapToRecorder = false)
        }

        private fun pump(socket: DatagramSocket, playerPort: Int, tapToRecorder: Boolean) {
            Thread({
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                val outgoing = DatagramPacket(buffer, 0, loopback, playerPort)
                while (!socket.isClosed) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)
                        if (tapToRecorder) recorder?.onRtpPacket(buffer, packet.length)
                        if (forwardToPlayer) {
                            outgoing.length = packet.length
                            socket.send(outgoing)
                        }
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            }, "RtpRelay-$playerPort").apply {
                isDaemon = true
                start()
            }
        }

        fun close() {
            runCatching { rtpSocket.close() }
            runCatching { rtcpSocket.close() }
        }
    }

    private fun handleConnection(clientSocket: Socket) {
        activeSockets.add(clientSocket)
        var targetSocket: Socket? = null
        try {
            targetSocket = Socket(targetHost, targetPort)
            activeSockets.add(targetSocket)

            targetSocket.soTimeout = 0 // Keep persistent without timing out
            clientSocket.soTimeout = 0

            val clientIn = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.ISO_8859_1))
            val clientOut = OutputStreamWriter(clientSocket.getOutputStream(), Charsets.ISO_8859_1)

            val targetIn = BufferedReader(InputStreamReader(targetSocket.getInputStream(), Charsets.ISO_8859_1))
            val targetOut = OutputStreamWriter(targetSocket.getOutputStream(), Charsets.ISO_8859_1)

            val sessionActive = AtomicBoolean(true)
            val proxyPort = serverSocket?.localPort ?: 0

            // ─── Direction 1: Client (ExoPlayer) -> Target (Camera) ───────────────
            val clientToTarget = Thread({
                try {
                    var contentLength = 0
                    val headers = mutableListOf<String>()

                    while (sessionActive.get()) {
                        val line = clientIn.readLine() ?: break
                        if (line.isEmpty()) {
                            // End of request headers
                            val bodyStr: String? = if (contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val r = clientIn.read(buf, read, contentLength - read)
                                    if (r == -1) break
                                    read += r
                                }
                                val rawBody = String(buf, 0, read)
                                if (rawBody.contains("127.0.0.1:$proxyPort")) {
                                    rawBody.replace("127.0.0.1:$proxyPort", targetHost)
                                } else {
                                    rawBody
                                }
                            } else {
                                null
                            }

                            for (h in headers) {
                                var rewritten = h
                                if (rewritten.contains("127.0.0.1:$proxyPort")) {
                                    rewritten = rewritten.replace("127.0.0.1:$proxyPort", targetHost)
                                }
                                if (rewritten.startsWith("Content-Length:", ignoreCase = true) && bodyStr != null) {
                                    rewritten = "Content-Length: ${bodyStr.toByteArray(Charsets.ISO_8859_1).size}"
                                }
                                if (rewritten.startsWith("Transport:", ignoreCase = true)) {
                                    rewritten = interceptSetupTransport(rewritten)
                                }
                                Log.d(TAG, "C->S: $rewritten")
                                targetOut.write(rewritten + "\r\n")
                            }
                            targetOut.write("\r\n")
                            if (bodyStr != null) {
                                targetOut.write(bodyStr)
                            }
                            targetOut.flush()

                            headers.clear()
                            contentLength = 0
                        } else {
                            headers.add(line)
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (sessionActive.get()) Log.d(TAG, "Client->Target ended: ${e.message}")
                } finally {
                    sessionActive.set(false)
                }
            }, "RtspProxy-C2T")

            // ─── Direction 2: Target (Camera) -> Client (ExoPlayer) ───────────────
            val targetToClient = Thread({
                try {
                    var contentLength = 0
                    val headers = mutableListOf<String>()

                    while (sessionActive.get()) {
                        val line = targetIn.readLine() ?: break
                        if (line.isEmpty()) {
                            // End of response headers
                            val bodyStr: String? = if (contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val r = targetIn.read(buf, read, contentLength - read)
                                    if (r == -1) break
                                    read += r
                                }
                                val rawBody = String(buf, 0, read)
                                if (rawBody.startsWith("v=")) readSdp(rawBody)
                                if (rawBody.contains(targetHost)) {
                                    rawBody.replace(targetHost, "127.0.0.1:$proxyPort")
                                } else {
                                    rawBody
                                }
                            } else {
                                null
                            }

                            for (h in headers) {
                                var rewritten = h

                                // 1. Sanitize Session header: "Session: ...; timeout = 60" -> "Session: ...;timeout=60"
                                if (rewritten.startsWith("Session:", ignoreCase = true)) {
                                    val cleaned = rewritten.replace(Regex(";\\s*timeout\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE), ";timeout=$1")
                                    if (cleaned != rewritten) {
                                        Log.i(TAG, "Fixed Session header: $rewritten -> $cleaned")
                                        rewritten = cleaned
                                    }
                                }

                                // 2. Point the SETUP reply back at ExoPlayer's own ports
                                if (rewritten.startsWith("Transport:", ignoreCase = true)) {
                                    rewritten = restoreSetupTransport(rewritten)
                                }

                                // 3. Sanitize Range header
                                if (rewritten.startsWith("Range:", ignoreCase = true)) {
                                    rewritten = rewritten.replace("npt=0Z-", "npt=0.000-")
                                }

                                // 3. Rewrite Content-Length if body was modified
                                if (rewritten.startsWith("Content-Length:", ignoreCase = true) && bodyStr != null) {
                                    rewritten = "Content-Length: ${bodyStr.toByteArray(Charsets.ISO_8859_1).size}"
                                } else if (rewritten.contains(targetHost)) {
                                    rewritten = rewritten.replace(targetHost, "127.0.0.1:$proxyPort")
                                }

                                Log.d(TAG, "S->C: $rewritten")
                                clientOut.write(rewritten + "\r\n")
                            }
                            clientOut.write("\r\n")
                            if (bodyStr != null) {
                                clientOut.write(bodyStr)
                            }
                            clientOut.flush()

                            headers.clear()
                            contentLength = 0
                        } else {
                            headers.add(line)
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (sessionActive.get()) Log.d(TAG, "Target->Client ended: ${e.message}")
                } finally {
                    sessionActive.set(false)
                }
            }, "RtspProxy-T2C")

            clientToTarget.start()
            targetToClient.start()

            clientToTarget.join()
            targetToClient.join()
        } catch (e: Exception) {
            Log.e(TAG, "Proxy error: ${e.message}", e)
        } finally {
            activeSockets.remove(clientSocket)
            targetSocket?.let { activeSockets.remove(it) }
            try { clientSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
        }
    }
}
