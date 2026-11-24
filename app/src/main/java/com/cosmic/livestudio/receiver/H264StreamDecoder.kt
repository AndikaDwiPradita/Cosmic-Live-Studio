package com.cosmic.livestudio.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class H264StreamDecoder(
    private val surface: Surface,
    private val onStatsUpdate: (Int, Int, Long, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var decoderJob: Job? = null
    private var statsJob: Job? = null
    private val frameChannel = kotlinx.coroutines.channels.Channel<ByteArray>(128)
    private var width = 0
    private var height = 0
    private var spsReceived = false
    private var ppsReceived = false
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var formatConfigured = false
    private val frameCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)
    private var lastStatsTime = System.currentTimeMillis()

    fun start() {
        if (isRunning.getAndSet(true)) return
        decoderJob = CoroutineScope(Dispatchers.IO).launch { decoderLoop() }
        statsJob = CoroutineScope(Dispatchers.IO).launch { statsLoop() }
    }

    fun queueFrame(data: ByteArray) {
        if (isRunning.get()) frameChannel.trySend(data)
    }

    private suspend fun decoderLoop() {
        while (isRunning.get()) {
            val data = frameChannel.receiveCatching().getOrNull() ?: break
            try {
                if (data.size < StreamProtocol.HEADER_SIZE) continue
                val (frameType, _) = StreamProtocol.parseHeader(data.copyOfRange(0, StreamProtocol.HEADER_SIZE))
                val payload = data.copyOfRange(StreamProtocol.HEADER_SIZE, data.size)
                when (frameType) {
                    StreamProtocol.FRAME_TYPE_SPS -> handleSps(payload)
                    StreamProtocol.FRAME_TYPE_PPS -> handlePps(payload)
                    StreamProtocol.FRAME_TYPE_CONFIG -> handleConfig(payload)
                    StreamProtocol.FRAME_TYPE_IDR, StreamProtocol.FRAME_TYPE_P -> {
                        if (formatConfigured) queueToDecoder(payload, frameType == StreamProtocol.FRAME_TYPE_IDR)
                    }
                    StreamProtocol.FRAME_TYPE_EOS -> break
                }
            } catch (e: Exception) {
                if (isRunning.get()) onError("Decoder error: ${e.message}")
            }
        }
    }

    private fun handleSps(sps: ByteArray) {
        spsData = stripAnnexBStartCode(sps)
        spsReceived = true
        tryConfigureFormat()
    }

    private fun handlePps(pps: ByteArray) {
        ppsData = stripAnnexBStartCode(pps)
        ppsReceived = true
        tryConfigureFormat()
    }

    private fun stripAnnexBStartCode(data: ByteArray): ByteArray {
        return when {
            data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> data.copyOfRange(4, data.size)
            data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> data.copyOfRange(3, data.size)
            else -> data
        }
    }

    private fun handleConfig(payload: ByteArray) {
        StreamProtocol.parseConfigPacket(payload)?.let {
            width = it.width
            height = it.height
            tryConfigureFormat()
        }
    }

    private fun tryConfigureFormat() {
        if (spsReceived && ppsReceived && !formatConfigured && width > 0 && height > 0) configureDecoder()
    }

    private fun configureDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
            spsData?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            ppsData?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec = codec
            codec.configure(format, surface, null, 0)
            codec.start()
            formatConfigured = true
        } catch (e: Exception) {
            onError("Gagal mengkonfigurasi decoder: ${e.message}")
        }
    }

    private fun queueToDecoder(payload: ByteArray, isKeyframe: Boolean) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val buffer = codec.getInputBuffer(inputIndex) ?: return
                buffer.clear()
                val cleanPayload = stripAnnexBStartCode(payload)
                buffer.put(cleanPayload)
                val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inputIndex, 0, cleanPayload.size, System.nanoTime() / 1000, flags)
                frameCount.incrementAndGet()
                byteCount.addAndGet(cleanPayload.size.toLong())
            }
            drainOutput()
        } catch (e: Exception) {
            Log.w("H264Decoder", "Queue input error", e)
        }
    }

    private fun drainOutput() {
        val codec = mediaCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) codec.releaseOutputBuffer(index, info.size > 0)
            else break
        }
    }

    private suspend fun statsLoop() {
        while (isRunning.get()) {
            delay(1000)
            val now = System.currentTimeMillis()
            val elapsed = now - lastStatsTime
            if (elapsed > 0) {
                val frames = frameCount.getAndSet(0)
                val bytes = byteCount.getAndSet(0)
                val fps = (frames * 1000 / elapsed).toInt()
                val bitrate = bytes * 8 * 1000 / elapsed
                onStatsUpdate(width, height, fps.toLong(), bitrate)
                lastStatsTime = now
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        frameChannel.close()
        decoderJob?.cancel()
        statsJob?.cancel()
        decoderJob = null
        statsJob = null
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Decoder", "Error stopping decoder", e)
        } finally {
            mediaCodec = null
            formatConfigured = false
        }
    }
}
