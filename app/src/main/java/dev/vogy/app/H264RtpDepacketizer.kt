package dev.vogy.app

import java.io.ByteArrayOutputStream

/**
 * Turns an H.264 RTP stream (RFC 6184) into complete access units — pure Kotlin, no Android.
 *
 * Handles single NAL units, STAP-A aggregates and FU-A fragments. An access unit is emitted on the
 * RTP marker bit or when the timestamp changes. SPS/PPS are reported separately (they become the
 * MP4's codec config) instead of being repeated in every frame. A lost packet only drops the NAL
 * it belonged to.
 */
class H264RtpDepacketizer(
    /** Annex-B frame (4-byte start codes), its 90 kHz RTP timestamp, and whether it holds an IDR. */
    private val onAccessUnit: (frame: ByteArray, rtpTimestamp: Long, isKeyframe: Boolean) -> Unit,
    private val onParameterSet: (nal: ByteArray) -> Unit
) {
    companion object {
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        private const val STAP_A = 24
        private const val FU_A = 28
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    /** RTP payload type of the H.264 track, or -1 to accept any. */
    var payloadType = -1

    private val accessUnit = ByteArrayOutputStream(256 * 1024)
    private var accessUnitTimestamp = -1L
    private var accessUnitHasIdr = false
    private var fragment: ByteArrayOutputStream? = null
    private var lastSequence = -1

    fun reset() {
        accessUnit.reset()
        accessUnitTimestamp = -1L
        accessUnitHasIdr = false
        fragment = null
        lastSequence = -1
    }

    fun push(packet: ByteArray, length: Int) {
        if (length < 12 || (packet[0].toInt() ushr 6) and 0x03 != 2) return
        if (payloadType >= 0 && (packet[1].toInt() and 0x7F) != payloadType) return

        val csrcCount = packet[0].toInt() and 0x0F
        val hasExtension = (packet[0].toInt() and 0x10) != 0
        val hasPadding = (packet[0].toInt() and 0x20) != 0
        val marker = (packet[1].toInt() and 0x80) != 0
        val sequence = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val timestamp = ((packet[4].toLong() and 0xFF) shl 24) or ((packet[5].toLong() and 0xFF) shl 16) or
            ((packet[6].toLong() and 0xFF) shl 8) or (packet[7].toLong() and 0xFF)

        var offset = 12 + csrcCount * 4
        if (hasExtension) {
            if (offset + 4 > length) return
            val words = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            offset += 4 + words * 4
        }
        val end = if (hasPadding) length - (packet[length - 1].toInt() and 0xFF) else length
        if (offset >= end) return

        if (lastSequence >= 0 && sequence != ((lastSequence + 1) and 0xFFFF)) fragment = null
        lastSequence = sequence

        if (accessUnitTimestamp >= 0 && timestamp != accessUnitTimestamp) flush()
        accessUnitTimestamp = timestamp
        depacketize(packet, offset, end)
        if (marker) flush()
    }

    private fun depacketize(p: ByteArray, start: Int, end: Int) {
        when (p[start].toInt() and 0x1F) {
            in 1..23 -> addNal(p, start, end - start)
            STAP_A -> {
                var i = start + 1
                while (i + 2 <= end) {
                    val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (size == 0 || i + size > end) break
                    addNal(p, i, size)
                    i += size
                }
            }
            FU_A -> {
                if (end - start < 2) return
                val indicator = p[start].toInt() and 0xFF
                val header = p[start + 1].toInt() and 0xFF
                if (header and 0x80 != 0) {
                    fragment = ByteArrayOutputStream(64 * 1024).apply {
                        write((indicator and 0xE0) or (header and 0x1F))
                    }
                }
                val buffer = fragment ?: return // middle of a NAL whose start was lost
                buffer.write(p, start + 2, end - start - 2)
                if (header and 0x40 != 0) {
                    fragment = null
                    val nal = buffer.toByteArray()
                    addNal(nal, 0, nal.size)
                }
            }
        }
    }

    private fun addNal(data: ByteArray, offset: Int, size: Int) {
        if (size <= 0) return
        when (data[offset].toInt() and 0x1F) {
            NAL_SPS, NAL_PPS -> {
                onParameterSet(data.copyOfRange(offset, offset + size))
                return
            }
            NAL_IDR -> accessUnitHasIdr = true
        }
        accessUnit.write(START_CODE)
        accessUnit.write(data, offset, size)
    }

    private fun flush() {
        if (accessUnit.size() > 0) {
            onAccessUnit(accessUnit.toByteArray(), accessUnitTimestamp, accessUnitHasIdr)
        }
        accessUnit.reset()
        accessUnitHasIdr = false
    }
}

/** Minimal H.264 SPS parser: just enough to read the coded picture size. */
internal object H264Sps {

    fun dimensions(sps: ByteArray): Pair<Int, Int>? = runCatching {
        val r = BitReader(unescape(sps), startByte = 1) // skip NAL header
        val profileIdc = r.bits(8)
        r.bits(16) // constraint flags + level_idc
        r.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        if (profileIdc in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.bits(1)
            r.ue(); r.ue() // bit depths
            r.bits(1)
            if (r.bits(1) == 1) { // seq_scaling_matrix_present
                repeat(if (chromaFormatIdc != 3) 8 else 12) { i ->
                    if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.bits(1)
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (r.bits(1) == 1) { // frame_cropping
            val cropUnitX = if (chromaFormatIdc == 0) 1 else 2
            val cropUnitY = (if (chromaFormatIdc == 1) 2 else 1) * (2 - frameMbsOnly)
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            width -= (left + right) * cropUnitX
            height -= (top + bottom) * cropUnitY
        }
        width to height
    }.getOrNull()

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00). */
    private fun unescape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        for (b in data) {
            if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var pos = startByte * 8
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val bit = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) zeros++
            return (1 shl zeros) - 1 + bits(zeros)
        }
        fun se(): Int {
            val v = ue()
            return if (v and 1 == 1) (v + 1) / 2 else -(v / 2)
        }
    }
}
