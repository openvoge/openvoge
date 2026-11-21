package dev.vogy.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the camera's live H.264 stream straight to MP4 — no decoding, no re-encoding.
 *
 * RTP packets tapped from [RtspProxy] are turned into access units by [H264RtpDepacketizer]
 * and handed to [MediaMuxer] as-is, so the cost is a memory copy per packet. Files are split into [SEGMENT_DURATION_US] segments that always start on a keyframe,
 * like a dashcam: a crash or power loss costs at most the segment in progress.
 *
 * Thread-safety: [onRtpPacket] runs on the proxy's UDP thread; [start]/[stop] may come from the
 * main thread. All state is guarded by [lock].
 */
class StreamRecorder(private val context: Context) {

    companion object {
        private const val TAG = "StreamRecorder"
        private const val SEGMENT_DURATION_US = 5L * 60 * 1_000_000
        private const val RTP_CLOCK_HZ = 90_000L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private val lock = Any()
    @Volatile var isRecording = false
        private set

    /** RTP payload type of the H.264 track (from the SDP), or -1 to accept any. */
    var h264PayloadType: Int
        get() = depacketizer.payloadType
        set(value) = synchronized(lock) { depacketizer.payloadType = value }

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private val depacketizer = H264RtpDepacketizer(
        onAccessUnit = ::onAccessUnit,
        onParameterSet = ::rememberParameterSet
    )

    // Current output segment
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var lastRtpTs = 0L
    private var extendedTs = 0L
    private var segmentUri: Uri? = null
    private var segmentFile: File? = null
    private var segmentFd: ParcelFileDescriptor? = null
    private var segmentSamples = 0

    /** Parameter sets advertised in the SDP (`sprop-parameter-sets`), used until in-band ones arrive. */
    fun setSdpParameterSets(spropParameterSets: String?) {
        val parts = spropParameterSets?.split(',') ?: return
        synchronized(lock) {
            parts.mapNotNull { runCatching { Base64.decode(it.trim(), Base64.DEFAULT) }.getOrNull() }
                .forEach { nal -> rememberParameterSet(nal) }
        }
    }

    fun start() {
        synchronized(lock) {
            if (isRecording) return
            isRecording = true
            resetAssembly()
        }
        Log.i(TAG, "Recording armed; waiting for a keyframe")
    }

    fun stop() {
        synchronized(lock) {
            if (!isRecording) return
            isRecording = false
            finishSegment()
            resetAssembly()
        }
        Log.i(TAG, "Recording stopped")
    }

    /** A new RTSP session began (reconnect): close the current file, keep recording into a new one. */
    fun onStreamRestarted() {
        synchronized(lock) {
            finishSegment()
            resetAssembly()
        }
    }

    /** Called for every RTP packet on the video port. Cheap no-op while not recording. */
    fun onRtpPacket(packet: ByteArray, length: Int) {
        if (!isRecording) return
        synchronized(lock) {
            if (isRecording) depacketizer.push(packet, length)
        }
    }

    private fun rememberParameterSet(nal: ByteArray) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1F) {
            H264RtpDepacketizer.NAL_SPS -> sps = nal
            H264RtpDepacketizer.NAL_PPS -> pps = nal
        }
    }

    // ─── Muxing ─────────────────────────────────────────────────────────────

    private fun onAccessUnit(frame: ByteArray, rtpTs: Long, isKeyframe: Boolean) {
        if (muxer == null) {
            // Segments only start on a keyframe with known parameter sets
            if (!isKeyframe || !startSegment()) return
            lastRtpTs = rtpTs
            extendedTs = 0L
        }

        // Extend the 32-bit RTP clock so wrap-around doesn't break timestamps
        val delta = (rtpTs - lastRtpTs) and 0xFFFFFFFFL
        extendedTs += if (delta > 0x7FFFFFFFL) 0 else delta
        lastRtpTs = rtpTs
        val ptsUs = extendedTs * 1_000_000L / RTP_CLOCK_HZ

        if (isKeyframe && ptsUs >= SEGMENT_DURATION_US) {
            finishSegment()
            if (!startSegment()) return
            extendedTs = 0L
            writeSample(frame, 0L, true)
            return
        }
        writeSample(frame, ptsUs, isKeyframe)
    }

    private fun writeSample(frame: ByteArray, ptsUs: Long, isKeyframe: Boolean) {
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo().apply {
            set(0, frame.size, ptsUs, if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            mux.writeSampleData(trackIndex, ByteBuffer.wrap(frame), info)
            segmentSamples++
        } catch (e: Exception) {
            Log.w(TAG, "writeSampleData failed: $e")
        }
    }

    private fun startSegment(): Boolean {
        val spsNal = sps ?: return false
        val ppsNal = pps ?: return false
        val (width, height) = H264Sps.dimensions(spsNal) ?: (1280 to 720)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + spsNal))
            setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + ppsNal))
        }
        return try {
            val newMuxer = openOutput() ?: return false
            trackIndex = newMuxer.addTrack(format)
            newMuxer.start()
            muxer = newMuxer
            segmentSamples = 0
            Log.i(TAG, "Segment started ${width}x$height")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start segment: $e")
            closeOutput(keep = false)
            false
        }
    }

    private fun finishSegment() {
        val mux = muxer ?: return
        muxer = null
        val keep = segmentSamples > 0
        try {
            if (keep) mux.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer stop failed: $e")
        } finally {
            runCatching { mux.release() }
        }
        closeOutput(keep)
        Log.i(TAG, "Segment finished samples=$segmentSamples kept=$keep")
    }

    private fun openOutput(): MediaMuxer? {
        val name = "OpenVOGE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Gallery: Movies/OpenVOGE, no storage permission needed
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OpenVOGE")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val fd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
            segmentUri = uri
            segmentFd = fd
            MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            // App-specific Movies folder (no permission on API 24–28)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val file = File(dir, name)
            segmentFile = file
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun closeOutput(keep: Boolean) {
        runCatching { segmentFd?.close() }
        segmentFd = null
        segmentUri?.let { uri ->
            if (keep) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
        segmentUri = null
        segmentFile?.let { if (!keep) it.delete() }
        segmentFile = null
    }

    private fun resetAssembly() = depacketizer.reset()
}
