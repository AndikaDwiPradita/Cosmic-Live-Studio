package com.cosmic.livestudio.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 Screen Encoder using MediaCodec hardware encoder
 * Produces low-latency H.264 stream with SPS/PPS/IDR frames
 */
class H264ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrameEncoded: (ByteArray, Boolean) -> Unit // (data, isKeyframe)
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderScope: CoroutineScope? = null
    private var frameCount = 0L
    private var lastKeyframeTime = 0L
    private val KEYFRAME_INTERVAL_MS = 1000L // 1 second

    private val frameQueue = Channel<ByteArray>(64)

    fun start(): Surface? {
        if (isRunning.getAndSet(true)) return null

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)

            // Low latency tuning
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            // Start encoder loop
            encoderScope = CoroutineScope(Dispatchers.IO)
            encoderScope?.launch { encoderLoop() }

            Log.d("H264Encoder", "Encoder started: ${width}x$height @ ${fps}fps, ${bitrate}bps")
            return inputSurface
        } catch (e: Exception) {
            Log.e("H264Encoder", "Failed to start encoder", e)
            stop()
            return null
        }
    }

    private suspend fun encoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L // 10ms timeout

        while (isRunning.get()) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    processOutputBuffer(outputBuffer, bufferInfo)
                    codec.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Format changed - send new SPS/PPS
                    val newFormat = codec.outputFormat
                    sendConfigFrames(newFormat)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e("H264Encoder", "Encoder loop error", e)
                }
            }
        }
    }

    private fun processOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return

        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(data)

        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        frameCount++

        // Send config frames (SPS/PPS) on first keyframe or after format change
        if (isKeyframe && frameCount <= 2) {
            // SPS/PPS are typically in the first buffer after format change
            onFrameEncoded(data, true)
        } else if (isKeyframe) {
            onFrameEncoded(data, true)
            lastKeyframeTime = System.currentTimeMillis()
        } else {
            // Force keyframe if interval exceeded
            val now = System.currentTimeMillis()
            if (now - lastKeyframeTime > KEYFRAME_INTERVAL_MS) {
                requestKeyframe()
                onFrameEncoded(data, false)
            } else {
                onFrameEncoded(data, false)
            }
        }
    }

    private fun sendConfigFrames(format: MediaFormat) {
        // Extract CSD-0 (SPS) and CSD-1 (PPS)
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")

        csd0?.let {
            val sps = ByteArray(it.remaining())
            it.get(sps)
            onFrameEncoded(sps, true)
        }

        csd1?.let {
            val pps = ByteArray(it.remaining())
            it.get(pps)
            onFrameEncoded(pps, true)
        }
    }

    private fun requestKeyframe() {
        mediaCodec?.let { codec ->
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        }
        lastKeyframeTime = System.currentTimeMillis()
    }

    fun stop() {
        isRunning.set(false)
        encoderScope?.coroutineContext.cancel()
        encoderScope = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error stopping encoder", e)
        } finally {
            mediaCodec = null
            inputSurface?.release()
            inputSurface = null
        }
        Log.d("H264Encoder", "Encoder stopped")
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private val Bundle = android.os.Bundle::class.java
    }
}