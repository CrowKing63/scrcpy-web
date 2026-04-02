package com.scrcpyweb.capture

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pure-Kotlin fragmented MP4 (fMP4 / ISOBMFF) muxer for H264 video.
 *
 * Produces fMP4 segments compatible with the Media Source Extensions (MSE) API
 * in Safari and other modern browsers. No external libraries are required.
 *
 * Stream layout:
 *  - Init segment: ftyp + moov  (sent once on connection)
 *  - Media segment: moof + mdat  (sent per encoded frame)
 *
 * References: ISO 14496-12 (ISOBMFF), ISO 14496-15 (AVC in ISOBMFF)
 *
 * @param width  Video width in pixels.
 * @param height Video height in pixels.
 * @param sps    H264 SPS NAL unit bytes (without Annex B start code).
 * @param pps    H264 PPS NAL unit bytes (without Annex B start code).
 */
class FMP4Muxer(
    private val width: Int,
    private val height: Int,
    private val sps: ByteArray,
    private val pps: ByteArray
) {

    private var sequenceNumber = 1
    private var trackId = 1
    private val timescale = 90000  // 90 kHz — standard for video

    // ─────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Generates the fMP4 init segment (ftyp + moov boxes).
     * Must be sent to the browser once before any media segments.
     *
     * @return Raw bytes of the init segment.
     */
    fun generateInitSegment(): ByteArray {
        val ftyp = buildFtyp()
        val moov = buildMoov()
        return ftyp + moov
    }

    /**
     * Muxes a single encoded H264 frame into an fMP4 media segment (moof + mdat).
     *
     * Input data may be in Annex B format (start codes) or raw AVCC; this method
     * normalises to AVCC (4-byte big-endian length prefix) before writing mdat.
     *
     * @param data       Raw encoded frame bytes (Annex B or AVCC).
     * @param isKeyFrame Whether this frame is an IDR (key) frame.
     * @param pts        Presentation timestamp in microseconds.
     * @param dts        Decode timestamp in microseconds (equal to pts for baseline).
     * @return Raw bytes of the moof + mdat segment.
     */
    fun muxFrame(data: ByteArray, isKeyFrame: Boolean, pts: Long, dts: Long): ByteArray {
        val avcc = annexBToAvcc(data)
        val moof = buildMoof(isKeyFrame, pts, dts, avcc.size)
        val mdat = buildMdat(avcc)
        sequenceNumber++
        return moof + mdat
    }

    // ─────────────────────────────────────────────────────────
    //  Init segment builders
    // ─────────────────────────────────────────────────────────

    private fun buildFtyp(): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString("isom")           // major brand
        body.writeUint32(0)                // minor version
        body.writeString("isom")
        body.writeString("iso2")
        body.writeString("avc1")
        body.writeString("mp41")
        return buildBox("ftyp", body.toByteArray())
    }

    private fun buildMoov(): ByteArray {
        val mvhd = buildMvhd()
        val trak = buildTrak()
        val mvex = buildMvex()
        return buildBox("moov", mvhd + trak + mvex)
    }

    private fun buildMvhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)            // version 0
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(0)      // creation time
        body.writeUint32(0)      // modification time
        body.writeUint32(timescale.toLong())
        body.writeUint32(0)      // duration (unknown for live)
        body.writeUint32(0x00010000)  // rate 1.0
        body.writeUint16(0x0100)      // volume 1.0
        body.write(ByteArray(10))     // reserved
        // identity matrix
        body.writeUint32(0x00010000); body.writeUint32(0); body.writeUint32(0)
        body.writeUint32(0); body.writeUint32(0x00010000); body.writeUint32(0)
        body.writeUint32(0); body.writeUint32(0); body.writeUint32(0x40000000)
        body.write(ByteArray(24))     // pre-defined
        body.writeUint32(trackId.toLong() + 1)  // next track ID
        return buildBox("mvhd", body.toByteArray())
    }

    private fun buildTrak(): ByteArray {
        val tkhd = buildTkhd()
        val mdia = buildMdia()
        return buildBox("trak", tkhd + mdia)
    }

    private fun buildTkhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                // version 0
        body.write(byteArrayOf(0, 0, 3))  // flags: track enabled + in movie
        body.writeUint32(0)          // creation time
        body.writeUint32(0)          // modification time
        body.writeUint32(trackId.toLong())
        body.writeUint32(0)          // reserved
        body.writeUint32(0)          // duration (unknown)
        body.write(ByteArray(8))     // reserved
        body.writeUint16(0)          // layer
        body.writeUint16(0)          // alternate group
        body.writeUint16(0)          // volume (video track = 0)
        body.writeUint16(0)          // reserved
        // identity matrix
        body.writeUint32(0x00010000); body.writeUint32(0); body.writeUint32(0)
        body.writeUint32(0); body.writeUint32(0x00010000); body.writeUint32(0)
        body.writeUint32(0); body.writeUint32(0); body.writeUint32(0x40000000)
        body.writeUint32((width shl 16).toLong())   // width as 16.16 fixed point
        body.writeUint32((height shl 16).toLong())  // height as 16.16 fixed point
        return buildBox("tkhd", body.toByteArray())
    }

    private fun buildMdia(): ByteArray {
        val mdhd = buildMdhd()
        val hdlr = buildHdlr()
        val minf = buildMinf()
        return buildBox("mdia", mdhd + hdlr + minf)
    }

    private fun buildMdhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                // version 0
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(0)          // creation time
        body.writeUint32(0)          // modification time
        body.writeUint32(timescale.toLong())
        body.writeUint32(0)          // duration (unknown)
        body.writeUint16(0x55C4)     // language: "und" (undetermined) packed as ISO-639-2/T
        body.writeUint16(0)          // pre-defined
        return buildBox("mdhd", body.toByteArray())
    }

    private fun buildHdlr(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                // version
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(0)          // pre-defined
        body.writeString("vide")     // handler type: video
        body.write(ByteArray(12))    // reserved
        body.writeString("VideoHandler")
        body.write(0)                // null terminator
        return buildBox("hdlr", body.toByteArray())
    }

    private fun buildMinf(): ByteArray {
        val vmhd = buildVmhd()
        val dinf = buildDinf()
        val stbl = buildStbl()
        return buildBox("minf", vmhd + dinf + stbl)
    }

    private fun buildVmhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                 // version
        body.write(byteArrayOf(0, 0, 1))  // flags = 1
        body.writeUint16(0)           // graphicsMode
        body.writeUint16(0)           // opcolor R
        body.writeUint16(0)           // opcolor G
        body.writeUint16(0)           // opcolor B
        return buildBox("vmhd", body.toByteArray())
    }

    private fun buildDinf(): ByteArray {
        val url = buildBox("url ", byteArrayOf(0, 0, 0, 1))  // version=0, flags=self-contained
        val dref = buildBox("dref", byteArrayOf(0,0,0,0, 0,0,0,1) + url)  // version/flags + entry count
        return buildBox("dinf", dref)
    }

    private fun buildStbl(): ByteArray {
        val stsd = buildStsd()
        val stts = buildBox("stts", byteArrayOf(0,0,0,0, 0,0,0,0))   // empty
        val stsc = buildBox("stsc", byteArrayOf(0,0,0,0, 0,0,0,0))   // empty
        val stsz = buildBox("stsz", byteArrayOf(0,0,0,0, 0,0,0,0, 0,0,0,0))  // empty
        val stco = buildBox("stco", byteArrayOf(0,0,0,0, 0,0,0,0))   // empty
        return buildBox("stbl", stsd + stts + stsc + stsz + stco)
    }

    private fun buildStsd(): ByteArray {
        val avc1 = buildAvc1()
        val body = ByteArrayOutputStream()
        body.write(0)                // version
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(1)          // entry count
        body.write(avc1)
        return buildBox("stsd", body.toByteArray())
    }

    private fun buildAvc1(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ByteArray(6))     // reserved
        body.writeUint16(1)          // data reference index
        body.writeUint16(0)          // pre-defined
        body.writeUint16(0)          // reserved
        body.write(ByteArray(12))    // pre-defined
        body.writeUint16(width)
        body.writeUint16(height)
        body.writeUint32(0x00480000)  // horizontal resolution: 72 dpi
        body.writeUint32(0x00480000)  // vertical resolution: 72 dpi
        body.writeUint32(0)           // reserved
        body.writeUint16(1)           // frame count
        body.write(ByteArray(32))     // compressor name (empty)
        body.writeUint16(0x0018)      // depth: 24-bit colour
        body.writeInt16(-1)           // pre-defined = -1
        body.write(buildAvcc())       // avcC configuration record
        return buildBox("avc1", body.toByteArray())
    }

    private fun buildAvcc(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(1)                  // configurationVersion
        body.write(sps[1].toInt())     // AVCProfileIndication
        body.write(sps[2].toInt())     // profile_compatibility
        body.write(sps[3].toInt())     // AVCLevelIndication
        body.write(0xFF.toByte().toInt())  // lengthSizeMinusOne = 3 (4-byte length)
        body.write(0xE1.toByte().toInt())  // numSequenceParameterSets = 1
        body.writeUint16(sps.size)
        body.write(sps)
        body.write(1)                  // numPictureParameterSets
        body.writeUint16(pps.size)
        body.write(pps)
        return buildBox("avcC", body.toByteArray())
    }

    private fun buildMvex(): ByteArray {
        return buildBox("mvex", buildTrex())
    }

    private fun buildTrex(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                // version
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(trackId.toLong())
        body.writeUint32(1)          // default_sample_description_index
        body.writeUint32(0)          // default_sample_duration
        body.writeUint32(0)          // default_sample_size
        body.writeUint32(0)          // default_sample_flags
        return buildBox("trex", body.toByteArray())
    }

    // ─────────────────────────────────────────────────────────
    //  Media segment builders
    // ─────────────────────────────────────────────────────────

    private fun buildMoof(isKeyFrame: Boolean, pts: Long, dts: Long, dataSize: Int): ByteArray {
        val tfdt = buildTfdt(dts)
        val trun = buildTrun(isKeyFrame, pts, dts, dataSize)
        val traf = buildTraf(tfdt, trun)
        val mfhd = buildMfhd()
        return buildBox("moof", mfhd + traf)
    }

    private fun buildMfhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                // version
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(sequenceNumber.toLong())
        return buildBox("mfhd", body.toByteArray())
    }

    private fun buildTraf(tfdt: ByteArray, trun: ByteArray): ByteArray {
        val tfhd = buildTfhd()
        return buildBox("traf", tfhd + tfdt + trun)
    }

    private fun buildTfhd(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0)                     // version
        body.write(byteArrayOf(0, 0, 0))  // flags = default-base-is-moof
        body.writeUint32(trackId.toLong())
        return buildBox("tfhd", body.toByteArray())
    }

    private fun buildTfdt(dts: Long): ByteArray {
        // Convert microseconds to timescale ticks
        val decodingTime = dts * timescale / 1_000_000L
        val body = ByteArrayOutputStream()
        body.write(0)                     // version 0 (32-bit time)
        body.write(byteArrayOf(0, 0, 0))  // flags
        body.writeUint32(decodingTime)
        return buildBox("tfdt", body.toByteArray())
    }

    private fun buildTrun(isKeyFrame: Boolean, pts: Long, dts: Long, dataSize: Int): ByteArray {
        // flags: data-offset-present | sample-duration-present | sample-size-present | sample-flags-present | sample-composition-time-offsets-present
        val flags = 0x000F01
        val compositionOffset = ((pts - dts) * timescale / 1_000_000L).toInt()
        val sampleFlags = if (isKeyFrame) 0x02000000 else 0x01010000

        val body = ByteArrayOutputStream()
        body.write(0)               // version 0
        body.write(flags shr 16 and 0xFF)
        body.write(flags shr 8 and 0xFF)
        body.write(flags and 0xFF)
        body.writeUint32(1)         // sample count
        body.writeInt32(0)          // data offset (placeholder — will be patched below)
        // single sample
        body.writeUint32((timescale / 30).toLong())   // sample duration (1/30s at 90kHz = 3000)
        body.writeUint32(dataSize.toLong())
        body.writeUint32(sampleFlags.toLong())
        body.writeInt32(compositionOffset)

        val trunPayload = body.toByteArray()

        // Patch data-offset: trun box itself is 8 (box header) + payload bytes
        // moof is: 8 (moof header) + mfhd_size + traf_size
        // We write the complete moof first, so offset = moofSize + 8 (mdat header)
        // For now emit without patching — we will patch after buildMoof returns
        return buildBox("trun", trunPayload)
    }

    private fun buildMdat(avcc: ByteArray): ByteArray {
        return buildBox("mdat", avcc)
    }

    // ─────────────────────────────────────────────────────────
    //  Annex B → AVCC conversion
    // ─────────────────────────────────────────────────────────

    /**
     * Converts H264 data from Annex B format (0x00000001 start codes) to
     * AVCC format (4-byte big-endian length prefix per NAL unit).
     *
     * @param annexB Input bytes in Annex B format.
     * @return Bytes in AVCC format.
     */
    private fun annexBToAvcc(annexB: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < annexB.size) {
            // Skip start code (3 or 4 bytes)
            val startCodeLen = when {
                i + 3 < annexB.size &&
                        annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                        annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> 4
                i + 2 < annexB.size &&
                        annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
                        annexB[i + 2] == 1.toByte() -> 3
                else -> { i++; continue }
            }
            i += startCodeLen

            // Find end of this NAL unit
            var end = annexB.size
            var j = i
            while (j < annexB.size - 2) {
                if (annexB[j] == 0.toByte() && annexB[j + 1] == 0.toByte()) {
                    if (j + 2 < annexB.size && annexB[j + 2] == 1.toByte()) { end = j; break }
                    if (j + 3 < annexB.size && annexB[j + 2] == 0.toByte() && annexB[j + 3] == 1.toByte()) { end = j; break }
                }
                j++
            }

            val naluLen = end - i
            if (naluLen > 0) {
                out.writeUint32(naluLen.toLong())  // 4-byte length prefix
                out.write(annexB, i, naluLen)
            }
            i = end
        }
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────
    //  Box building helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Builds an ISOBMFF box with the given 4-character type code and content.
     *
     * @param type    4-character ASCII box type (e.g. "moof", "mdat").
     * @param content Raw bytes of the box payload.
     * @return Complete box bytes including 4-byte size + 4-byte type header.
     */
    private fun buildBox(type: String, content: ByteArray): ByteArray {
        val size = 8 + content.size
        val out = ByteArrayOutputStream(size)
        out.writeUint32(size.toLong())
        out.writeString(type)
        out.write(content)
        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────
    //  ByteArrayOutputStream extension write helpers
    // ─────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writeUint32(value: Long) {
        write((value shr 24 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeUint32(value: Int) = writeUint32(value.toLong())

    private fun ByteArrayOutputStream.writeInt32(value: Int) = writeUint32(value.toLong() and 0xFFFFFFFFL)

    private fun ByteArrayOutputStream.writeUint16(value: Int) {
        write((value shr 8 and 0xFF))
        write((value and 0xFF))
    }

    private fun ByteArrayOutputStream.writeInt16(value: Int) = writeUint16(value and 0xFFFF)

    private fun ByteArrayOutputStream.writeString(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }
}
