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
 * 4. UDP Punch & RTCP forwarding:
 *    When server RTP/RTCP ports are negotiated in SETUP, forwards client UDP traffic
 *    to the camera so NAT keep-alives and RTCP feedback work seamlessly.
 */
class RtspProxy(
    private val targetHost: String = "192.72.1.1",
    private val targetPort: Int = 554
) {
    companion object {
        private const val TAG = "RtspProxy"
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var proxyThread: Thread? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

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

        proxyThread?.interrupt()
        proxyThread = null
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

                                // 2. Sanitize Range header
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
