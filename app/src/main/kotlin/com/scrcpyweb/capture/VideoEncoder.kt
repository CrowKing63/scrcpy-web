package com.scrcpyweb.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H264 video encoder wrapper using MediaCodec in async surface-input mode.
 *
 * Frames are fed via the input Surface returned by [getInputSurface].
 * Encoded output is delivered asynchronously via [onEncodedFrame] and [onSpsAvailable].
 *
 * @param width   Encode width in pixels.
 * @param height  Encode height in pixels.
 * @param bitrate Target bitrate in bits per second (default 4 Mbps).
 * @param fps     Target frame rate (default 30).
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 4_000_000,
    private val fps: Int = 30
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var keyframeRunnable: Runnable? = null

    /** Invoked on each encoded frame with the raw ByteBuffer and its metadata. */
    var onEncodedFrame: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    /** Invoked once when the encoder emits codec-specific data (SPS + PPS NAL units). */
    var onSpsAvailable: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null

    /**
     * Returns the input Surface that the VirtualDisplay should render into.
     * Must be called after [start].
     */
    fun getInputSurface(): Surface = requireNotNull(inputSurface) {
        "VideoEncoder not started — call start() before getInputSurface()"
    }

    /**
     * Configures and starts the MediaCodec encoder.
     * Spawns a dedicated HandlerThread for async callbacks.
     */
    fun start() {
        handlerThread = HandlerThread("VideoEncoderThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Repeat the last frame when the screen is static so the encoder
            // keeps producing output even if VirtualDisplay sends nothing new.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface mode — no input buffer handling needed
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec-specific data: extract SPS and PPS
                        parseSpsAndPps(buffer, info)
                    } else if (info.size > 0) {
                        onEncodedFrame?.invoke(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    e.printStackTrace()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // Format change handled via codec-specific data in onOutputBufferAvailable
                }
            }, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        }

        // Periodically request a sync frame so slow/static-screen streams
        // always have a fresh IDR for newly connected clients.
        keyframeRunnable = object : Runnable {
            override fun run() {
                try {
                    codec?.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                } catch (_: Exception) { /* codec may have been released */ }
                handler?.postDelayed(this, KEYFRAME_REQUEST_INTERVAL_MS)
            }
        }
        handler?.postDelayed(keyframeRunnable!!, KEYFRAME_REQUEST_INTERVAL_MS)
    }

    /**
     * Stops encoding and releases all MediaCodec resources.
     */
    fun stop() {
        keyframeRunnable?.let { handler?.removeCallbacks(it) }
        keyframeRunnable = null
        codec?.stop()
        codec?.release()
        codec = null
        inputSurface?.release()
        inputSurface = null
        handler = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    /**
     * Parses codec-specific data to extract SPS and PPS NAL units.
     * The codec-specific data is in Annex B format: [00 00 00 01 SPS 00 00 00 01 PPS].
     */
    companion object {
        /** Interval between forced IDR requests (milliseconds). */
        private const val KEYFRAME_REQUEST_INTERVAL_MS = 2000L
    }

    private fun parseSpsAndPps(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)

        // Find the two Annex B start codes (00 00 00 01) to split SPS and PPS
        var secondStart = -1
        for (i in 4 until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                secondStart = i
                break
            }
        }

        if (secondStart == -1) return

        // Strip Annex B start codes for SPS and PPS
        val sps = data.copyOfRange(4, secondStart)
        val pps = data.copyOfRange(secondStart + 4, data.size)
        onSpsAvailable?.invoke(sps, pps)
    }
}
