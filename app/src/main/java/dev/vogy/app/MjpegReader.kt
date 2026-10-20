package dev.vogy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connects to a camera's MJPEG endpoint over a raw TCP socket and decodes
 * individual JPEG frames.
 *
 * Supports two common wire formats:
 *  1. HTTP multipart/x-mixed-replace (with a Content-Length per part)
 *  2. Raw JPEG frame stream (scan for FF D8 … FF D9 markers)
 *
 * [start] is a **blocking** call — it runs until [stop] is called or the
 * socket is closed by the remote end.  Call it from a background thread.
 */
class MjpegReader(private val host: String, private val port: Int) {

    companion object {
        private const val TAG = "MjpegReader"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val INITIAL_BUFFER = 65_536
    }

    interface FrameCallback {
        /** Called once when the very first valid JPEG frame is decoded. */
        fun onFirstFrame()
        /** Called for every decoded frame.  Runs on the streaming thread — post to main if needed. */
        fun onFrame(bitmap: Bitmap)
        /** Called on non-fatal errors (e.g., bad HTTP status, format errors). */
        fun onError(message: String)
    }

    @Volatile
    var running = false
        private set

    private var socket: Socket? = null

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Blocking.  Opens the socket, sends a GET request, and parses the MJPEG stream.
     * Returns when the stream ends or [stop] is called.
     */
    fun start(callback: FrameCallback) {
        running = true
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS

            val output = s.getOutputStream()
            val input  = s.getInputStream()

            // Standard HTTP GET — most IP cameras expect this even on non-80 ports
            output.write(
                "GET / HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            output.flush()

            // Parse HTTP response line + headers
            val (statusCode, headers) = readResponseHeaders(input)
            Log.d(TAG, "HTTP $statusCode  headers=$headers")

            if (statusCode != 200) {
                callback.onError("HTTP status $statusCode")
                return
            }

            val contentType = headers["content-type"].orEmpty()

            if (contentType.contains("multipart", ignoreCase = true)) {
                // Standard HTTP MJPEG: multipart/x-mixed-replace; boundary=xxx
                val boundary = Regex("""boundary=([^\r\n;," ]+)""", RegexOption.IGNORE_CASE)
                    .find(contentType)?.groupValues?.get(1)?.trim('"')
                    ?: run {
                        callback.onError("No boundary found in Content-Type: $contentType")
                        return
                    }
                Log.d(TAG, "Multipart boundary: $boundary")
                streamMultipart(input, "--$boundary", callback)
            } else {
                // No HTTP framing — scan raw JPEG SOI/EOI markers
                Log.d(TAG, "No multipart content-type — trying raw JPEG scan")
                streamRawJpeg(input, callback)
            }

        } catch (e: IOException) {
            if (running) callback.onError("IO: ${e.message}")
        } finally {
            running = false
            try { socket?.close() } catch (_: IOException) {}
            socket = null
        }
    }

    /** Thread-safe.  Closes the socket and causes [start] to return. */
    fun stop() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
    }

    // ─── HTTP header parsing ──────────────────────────────────────────────────

    private fun readResponseHeaders(stream: InputStream): Pair<Int, Map<String, String>> {
        var status = 0
        val headers = mutableMapOf<String, String>()
        var lineIdx = 0
        while (running) {
            val line = readAsciiLine(stream) ?: break
            when {
                lineIdx == 0 -> status = line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                line.isEmpty() -> break
                else -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
            }
            lineIdx++
        }
        return Pair(status, headers)
    }

    /**
     * Reads a CRLF- or LF-terminated ASCII/ISO-8859-1 line from [stream].
     * Returns null on EOF.
     */
    private fun readAsciiLine(stream: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = stream.read()
            if (b == -1) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8_192) return sb.toString() // safety valve
        }
    }

    // ─── Multipart MJPEG ─────────────────────────────────────────────────────

    private fun streamMultipart(
        stream: InputStream,
        boundary: String,
        callback: FrameCallback,
    ) {
        var firstFrame = true

        // Fast-forward to the first boundary marker
        if (!skipToLine(stream, boundary)) return

        while (running) {
            // --- Read MIME part headers ---
            val partHeaders = mutableMapOf<String, String>()
            while (running) {
                val line = readAsciiLine(stream) ?: return
                if (line.isEmpty()) break                          // blank line = end of headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    partHeaders[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            if (!running) break

            val contentLength = partHeaders["content-length"]?.toIntOrNull()
            if (contentLength == null || contentLength <= 0) {
                // Malformed part — skip to next boundary
                if (!skipToLine(stream, boundary)) break
                continue
            }

            // --- Read exactly contentLength bytes of JPEG payload ---
            val jpeg = readExactBytes(stream, contentLength) ?: break

            decodeAndDispatch(jpeg, callback, firstFrame)
            if (firstFrame) firstFrame = false

            // --- Skip to next boundary ---
            if (!skipToLine(stream, boundary)) break
        }
    }

    /**
     * Reads lines from [stream] until one **starts with** [target].
     * Returns false on EOF or when [stop] is called.
     */
    private fun skipToLine(stream: InputStream, target: String): Boolean {
        while (running) {
            val line = readAsciiLine(stream) ?: return false
            if (line.startsWith(target)) return true
        }
        return false
    }

    private fun readExactBytes(stream: InputStream, length: Int): ByteArray? {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length && running) {
            val read = stream.read(buf, offset, length - offset)
            if (read == -1) return null
            offset += read
        }
        return if (offset == length) buf else null
    }

    // ─── Raw JPEG stream ─────────────────────────────────────────────────────

    /**
     * Scans the raw byte stream for JPEG SOI (FF D8) and EOI (FF D9) markers,
     * extracts each JPEG frame, and dispatches it as a [Bitmap].
     */
    private fun streamRawJpeg(stream: InputStream, callback: FrameCallback) {
        val accumulator = ByteArrayOutputStream(INITIAL_BUFFER)
        var inJpeg = false
        var prev = -1
        var firstFrame = true

        while (running) {
            val b = stream.read()
            if (b == -1) break

            if (!inJpeg) {
                if (prev == 0xFF && b == 0xD8) {
                    inJpeg = true
                    accumulator.reset()
                    accumulator.write(0xFF)
                    accumulator.write(0xD8)
                }
            } else {
                accumulator.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    // Complete JPEG frame in accumulator
                    val jpeg = accumulator.toByteArray()
                    decodeAndDispatch(jpeg, callback, firstFrame)
                    if (firstFrame) firstFrame = false
                    inJpeg = false
                    accumulator.reset()
                }
            }
            prev = b
        }
    }

    // ─── Bitmap decoding ─────────────────────────────────────────────────────

    private fun decodeAndDispatch(jpeg: ByteArray, callback: FrameCallback, isFirst: Boolean) {
        try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            if (isFirst) callback.onFirstFrame()
            callback.onFrame(bmp)
        } catch (e: Exception) {
            Log.w(TAG, "Frame decode failed: ${e.message}")
        }
    }
}
