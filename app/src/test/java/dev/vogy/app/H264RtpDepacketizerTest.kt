package dev.vogy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class H264RtpDepacketizerTest {

    private data class Frame(val data: ByteArray, val ts: Long, val key: Boolean)

    private val frames = mutableListOf<Frame>()
    private val parameterSets = mutableListOf<ByteArray>()
    private lateinit var depacketizer: H264RtpDepacketizer
    private var seq = 100

    @Before
    fun setUp() {
        depacketizer = H264RtpDepacketizer(
            onAccessUnit = { data, ts, key -> frames += Frame(data, ts, key) },
            onParameterSet = { parameterSets += it }
        )
    }

    private fun rtp(payload: ByteArray, ts: Long, marker: Boolean, pt: Int = 96, sequence: Int = seq++): ByteArray {
        val header = byteArrayOf(
            0x80.toByte(),
            ((if (marker) 0x80 else 0) or pt).toByte(),
            (sequence shr 8).toByte(), sequence.toByte(),
            (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(),
            0, 0, 0, 1 // SSRC
        )
        return header + payload
    }

    private fun push(packet: ByteArray) = depacketizer.push(packet, packet.size)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { out.write(byteArrayOf(0, 0, 0, 1)); out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun singleNalUnitBecomesOneFrame() {
        val slice = byteArrayOf(0x41, 1, 2, 3) // non-IDR slice
        push(rtp(slice, ts = 3000, marker = true))

        assertEquals(1, frames.size)
        assertArrayEquals(annexB(slice), frames[0].data)
        assertEquals(3000L, frames[0].ts)
        assertFalse(frames[0].key)
    }

    @Test
    fun stapAExtractsParameterSetsAndKeepsIdrInFrame() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0x65, 9, 9, 9)
        val stap = ByteArrayOutputStream().apply {
            write(0x18) // STAP-A
            listOf(sps, pps, idr).forEach { nal ->
                write(nal.size shr 8); write(nal.size); write(nal)
            }
        }.toByteArray()

        push(rtp(stap, ts = 9000, marker = true))

        assertEquals(2, parameterSets.size)
        assertArrayEquals(sps, parameterSets[0])
        assertArrayEquals(pps, parameterSets[1])
        assertEquals(1, frames.size)
        assertArrayEquals(annexB(idr), frames[0].data)
        assertTrue(frames[0].key)
    }

    @Test
    fun fuAFragmentsAreReassembled() {
        val idrBody = ByteArray(3000) { (it % 251).toByte() }
        val nalHeader = 0x65 // nri=3, type=5
        val indicator = (nalHeader and 0xE0) or 28
        val chunks = idrBody.toList().chunked(1200).map { it.toByteArray() }
        chunks.forEachIndexed { i, chunk ->
            val fuHeader = (if (i == 0) 0x80 else 0) or (if (i == chunks.lastIndex) 0x40 else 0) or (nalHeader and 0x1F)
            push(rtp(byteArrayOf(indicator.toByte(), fuHeader.toByte()) + chunk, ts = 12000, marker = i == chunks.lastIndex))
        }

        assertEquals(1, frames.size)
        assertArrayEquals(annexB(byteArrayOf(nalHeader.toByte()) + idrBody), frames[0].data)
        assertTrue(frames[0].key)
    }

    @Test
    fun lostFragmentDropsOnlyThatNal() {
        val indicator = (0x65 and 0xE0) or 28
        push(rtp(byteArrayOf(indicator.toByte(), 0x85.toByte(), 1, 2), ts = 15000, marker = false)) // start
        seq++ // middle fragment lost
        push(rtp(byteArrayOf(indicator.toByte(), 0x45, 5, 6), ts = 15000, marker = true)) // end
        val next = byteArrayOf(0x41, 7)
        push(rtp(next, ts = 18000, marker = true))

        assertEquals(1, frames.size)
        assertArrayEquals(annexB(next), frames[0].data)
    }

    @Test
    fun timestampChangeClosesFrameWithoutMarker() {
        push(rtp(byteArrayOf(0x41, 1), ts = 1000, marker = false))
        push(rtp(byteArrayOf(0x41, 2), ts = 4000, marker = true))

        assertEquals(listOf(1000L, 4000L), frames.map { it.ts })
    }

    @Test
    fun otherPayloadTypesAreIgnored() {
        depacketizer.payloadType = 96
        push(rtp(byteArrayOf(0x41, 1), ts = 1000, marker = true, pt = 97))
        assertTrue(frames.isEmpty())
    }

    // ─── SPS parsing ────────────────────────────────────────────────────────

    /** Builds a baseline-profile SPS for the given size (with cropping when not a multiple of 16). */
    private fun baselineSps(width: Int, height: Int): ByteArray {
        val w = BitWriter()
        w.bits(66, 8); w.bits(0, 8); w.bits(31, 8) // profile, constraints, level
        w.ue(0) // sps id
        w.ue(0) // log2_max_frame_num - 4
        w.ue(0) // poc type 0
        w.ue(0) // log2_max_poc_lsb - 4
        w.ue(1) // max refs
        w.bits(0, 1)
        val mbW = (width + 15) / 16
        val mbH = (height + 15) / 16
        w.ue(mbW - 1); w.ue(mbH - 1)
        w.bits(1, 1) // frame_mbs_only
        w.bits(1, 1) // direct_8x8
        val cropRight = (mbW * 16 - width) / 2
        val cropBottom = (mbH * 16 - height) / 2
        if (cropRight > 0 || cropBottom > 0) {
            w.bits(1, 1); w.ue(0); w.ue(cropRight); w.ue(0); w.ue(cropBottom)
        } else {
            w.bits(0, 1)
        }
        w.bits(0, 1) // vui
        w.bits(1, 1) // stop bit
        return byteArrayOf(0x67) + w.toByteArray()
    }

    @Test
    fun spsDimensions720p() {
        assertEquals(1280 to 720, H264Sps.dimensions(baselineSps(1280, 720)))
    }

    @Test
    fun spsDimensions1080pWithCropping() {
        assertEquals(1920 to 1080, H264Sps.dimensions(baselineSps(1920, 1080)))
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun bits(value: Int, n: Int) = (n - 1 downTo 0).forEach { bits += (value shr it) and 1 }
        fun ue(value: Int) {
            val v = value + 1
            val len = 32 - Integer.numberOfLeadingZeros(v)
            bits(0, len - 1)
            bits(v, len)
        }
        fun toByteArray(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            return bits.chunked(8).map { byte -> byte.fold(0) { acc, b -> (acc shl 1) or b }.toByte() }.toByteArray()
        }
    }
}
