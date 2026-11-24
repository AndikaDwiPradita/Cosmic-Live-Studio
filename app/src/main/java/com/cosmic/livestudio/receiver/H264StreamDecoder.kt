package com.cosmic.livestudio.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 Stream Decoder using MediaCodec hardware decoder
 * Receives framed H.264 packets and decodes to Surface
 */
class H264StreamDecoder(
    private val surface: Surface,
    private val onStatsUpdate: (Int, Int, Long, Long) -> Unit, // (width, height, fps, bitrate)
    private val onError: (String) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var decoderScope: CoroutineScope? = null
    private val frameChannel = Channel<ByteArray>(128)
    private var width = 0
    private var height = 0
    private var spsReceived = false
    private var ppsReceived = false
    private var formatConfigured = false

    // Stats tracking
    private val frameCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private var lastStatsTime = System.currentTimeMillis()
    private var lastFrameTime = 0L
    private var calculatedFps = 0

    fun start() {
        if (isRunning.getAndSet(true)) return

        decoderScope = CoroutineScope(Dispatchers.IO)
        decoderScope?.launch { decoderLoop() }
        decoderScope?.launch { statsLoop() }

        Log.d("H264Decoder", "Decoder started")
    }

    fun queueFrame(data: ByteArray) {
        if (isRunning.get()) {
            frameChannel.trySend(data)
        }
    }

    private suspend fun decoderLoop() {
        val timeoutUs = 10000L

        while (isRunning.get()) {
            val data = frameChannel.receive() ?: break

            try {
                if (!formatConfigured) {
                    // Parse header to get frame type
                    if (data.size >= StreamProtocol.HEADER_SIZE) {
                        val (frameType, _) = StreamProtocol.parseHeader(data.copyOfRange(0, StreamProtocol.HEADER_SIZE))
                        val payload = data.copyOfRange(StreamProtocol.HEADER_SIZE, data.size)

                        when (frameType) {
                            StreamProtocol.FRAME_TYPE_SPS -> handleSps(payload)
                            StreamProtocol.FRAME_TYPE_PPS -> handlePps(payload)
                            StreamProtocol.FRAME_TYPE_CONFIG -> handleConfig(payload)
                            StreamProtocol.FRAME_TYPE_IDR, StreamProtocol.FRAME_TYPE_P -> {
                                if (spsReceived && ppsReceived && formatConfigured) {
                                    queueToDecoder(payload, frameType == StreamProtocol.FRAME_TYPE_IDR)
                                } else {
                                    // Buffer frames until config is ready
                                    // For now, just log
                                    Log.d("H264Decoder", "Buffering frame, waiting for SPS/PPS: type=$frameType")
                                }
                            }
                            StreamProtocol.FRAME_TYPE_HEARTBEAT -> {
                                // Ignore heartbeat
                            }
                            StreamProtocol.FRAME_TYPE_EOS -> {
                                Log.d("H264Decoder", "End of stream received")
                                break
                            }
                        }
                    }
                } else {
                    // Format already configured, parse and queue
                    if (data.size >= StreamProtocol.HEADER_SIZE) {
                        val (frameType, _) = StreamProtocol.parseHeader(data.copyOfRange(0, StreamProtocol.HEADER_SIZE))
                        val payload = data.copyOfRange(StreamProtocol.HEADER_SIZE, data.size)
                        if (frameType == StreamProtocol.FRAME_TYPE_IDR || frameType == StreamProtocol.FRAME_TYPE_P) {
                            queueToDecoder(payload, frameType == StreamProtocol.FRAME_TYPE_IDR)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("H264Decoder", "Decoder loop error", e)
                if (isRunning.get()) onError("Decoder error: ${e.message}")
            }
        }
    }

    private fun handleSps(sps: ByteArray) {
        spsReceived = true
        Log.d("H264Decoder", "SPS received: ${sps.size} bytes")
        tryConfigureFormat()
    }

    private fun handlePps(pps: ByteArray) {
        ppsReceived = true
        Log.d("H264Decoder", "PPS received: ${pps.size} bytes")
        tryConfigureFormat()
    }

    private fun handleConfig(payload: ByteArray) {
        val config = StreamProtocol.parseConfigPacket(payload)
        config?.let {
            width = it.width
            height = it.height
            Log.d("H264Decoder", "Config received: ${it.width}x${it.height} @ ${it.fps}fps, ${it.bitrate}bps")
            tryConfigureFormat()
        }
    }

    private fun tryConfigureFormat() {
        if (spsReceived && ppsReceived && !formatConfigured && width > 0 && height > 0) {
            configureDecoder()
        }
    }

    private fun configureDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)

            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()

            formatConfigured = true
            Log.d("H264Decoder", "Decoder configured: ${width}x$height")
        } catch (e: Exception) {
            Log.e("H264Decoder", "Failed to configure decoder", e)
            onError("Gagal mengkonfigurasi decoder: ${e.message}")
        }
    }

    private fun queueToDecoder(payload: ByteArray, isKeyframe: Boolean) {
        val codec = mediaCodec ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                inputBuffer.clear()
                inputBuffer.put(payload)

                val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val presentationTimeUs = System.nanoTime() / 1000

                codec.queueInputBuffer(inputIndex, 0, payload.size, presentationTimeUs, flags)

                // Update stats
                frameCount.incrementAndGet()
                byteCount.addAndGet(payload.size.toLong())
            }
        } catch (e: Exception) {
            Log.w("H264Decoder", "Queue input error", e)
        }

        // Drain output
        drainOutput()
    }

    private fun drainOutput() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                val render = bufferInfo.size > 0
                codec.releaseOutputBuffer(outputIndex, render)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed
            } else {
                break
            }
        }
    }

    private suspend fun statsLoop() {
        while (isRunning.get()) {
            kotlinx.coroutines.delay(1000)
            calculateStats()
        }
    }

    private fun calculateStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsTime
        if (elapsed > 0) {
            val frames = frameCount.getAndSet(0)
            val bytes = byteCount.getAndSet(0)
            calculatedFps = (frames * 1000 / elapsed).toInt()
            val bitrate = (bytes * 8 * 1000 / elapsed)

            onStatsUpdate(width, height, calculatedFps.toLong(), bitrate)
            lastStatsTime = now
        }
    }

    fun stop() {
        isRunning.set(false)
        frameChannel.close()
        decoderScope?.coroutineContext.cancel()
        decoderScope = null

        try {
            mediaCodec?.signalEndOfInputStream()
            // Drain remaining
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val index = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: break
                if (index >= 0) {
                    mediaCodec?.releaseOutputBuffer(index, true)
                } else {
                    break
                }
            }
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Decoder", "Error stopping decoder", e)
        } finally {
            mediaCodec = null
        }

        Log.d("H264Decoder", "Decoder stopped")
    }
}