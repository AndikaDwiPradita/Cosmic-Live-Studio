package com.cosmic.livestudio.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrameEncoded: (ByteArray, Boolean) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderJob: Job? = null

    fun start(): Surface? {
        if (isRunning.getAndSet(true)) return null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            encoderJob = CoroutineScope(Dispatchers.IO).launch { encoderLoop(codec) }
            Log.d("H264Encoder", "Encoder started: ${width}x${height} @ ${fps}fps")
            return inputSurface
        } catch (e: Exception) {
            Log.e("H264Encoder", "Failed to start encoder", e)
            stop()
            return null
        }
    }

    private suspend fun encoderLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        val keyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        onFrameEncoded(data, keyframe)
                    }
                    codec.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sendConfigFrames(codec.outputFormat)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e("H264Encoder", "Encoder loop error", e)
            }
        }
    }

    private fun sendConfigFrames(format: MediaFormat) {
        for (key in arrayOf("csd-0", "csd-1")) {
            val buffer = format.getByteBuffer(key)
            if (buffer != null) {
                val copy = ByteArray(buffer.remaining())
                buffer.get(copy)
                onFrameEncoded(copy, true)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        encoderJob?.cancel()
        encoderJob = null
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
}
