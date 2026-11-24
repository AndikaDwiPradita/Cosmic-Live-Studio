package com.cosmic.livestudio.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stream Protocol Definitions for Cosmic Live Studio
 *
 * Packet Framing: 8-byte header + payload
 * Header: [4 bytes: frame type/flags][4 bytes: payload length]
 * Frame Types:
 *   0x01 = SPS (Sequence Parameter Set)
 *   0x02 = PPS (Picture Parameter Set)
 *   0x03 = IDR Frame (Keyframe)
 *   0x04 = P Frame (Predicted)
 *   0x05 = B Frame (Bi-directional)
 *   0x06 = Config/Control
 *   0x07 = Heartbeat
 *   0xFF = End of Stream
 */
object StreamProtocol {
    // UDP Discovery
    const val UDP_DISCOVERY_PORT = 45454
    const val UDP_BROADCAST_INTERVAL_MS = 1000L
    const val DISCOVERY_APP_ID = "COSMIC_LIVE"

    // TCP Streaming
    const val TCP_STREAMING_PORT = 45455

    // Frame Types
    const val FRAME_TYPE_SPS = 0x01
    const val FRAME_TYPE_PPS = 0x02
    const val FRAME_TYPE_IDR = 0x03
    const val FRAME_TYPE_P = 0x04
    const val FRAME_TYPE_B = 0x05
    const val FRAME_TYPE_CONFIG = 0x06
    const val FRAME_TYPE_HEARTBEAT = 0x07
    const val FRAME_TYPE_EOS = 0xFF.toByte()

    // Header size in bytes
    const val HEADER_SIZE = 8

    // Flags
    const val FLAG_KEYFRAME = 0x80000000
    const val FLAG_CONFIG = 0x40000000
    const val FLAG_END_OF_STREAM = 0x20000000

    /**
     * Creates a framed packet with header
     */
    fun createPacket(frameType: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(HEADER_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(frameType)
            putInt(payload.size)
        }.array()
        return header + payload
    }

    /**
     * Creates a packet from NAL unit (automatically detects type)
     */
    fun createPacketFromNal(nal: ByteArray): ByteArray {
        val nalType = nal[0] and 0x1F
        val frameType = when (nalType) {
            7 -> FRAME_TYPE_SPS    // SPS
            8 -> FRAME_TYPE_PPS    // PPS
            5 -> FRAME_TYPE_IDR    // IDR slice
            1 -> FRAME_TYPE_P      // Non-IDR slice
            else -> FRAME_TYPE_P
        }
        return createPacket(frameType, nal)
    }

    /**
     * Parses header from byte array
     */
    fun parseHeader(header: ByteArray): Pair<Int, Int> {
        val buffer = ByteBuffer.wrap(header).apply { order(ByteOrder.BIG_ENDIAN) }
        val frameType = buffer.getInt()
        val payloadLength = buffer.getInt()
        return frameType to payloadLength
    }

    /**
     * Creates discovery payload JSON
     */
    fun createDiscoveryPayload(code: String, tcpPort: Int): String {
        return """{"app":"$DISCOVERY_APP_ID","code":"$code","tcpPort":$tcpPort}"""
    }

    /**
     * Parses discovery payload
     */
    data class DiscoveryInfo(
        val app: String,
        val code: String,
        val tcpPort: Int
    )

    fun parseDiscoveryPayload(json: String): DiscoveryInfo? {
        try {
            // Simple JSON parsing without external dependency for UDP packets
            val app = extractJsonValue(json, "app")
            val code = extractJsonValue(json, "code")
            val portStr = extractJsonValue(json, "tcpPort")
            if (app != null && code != null && portStr != null) {
                return DiscoveryInfo(app, code, portStr.toInt())
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return null
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }

    /**
     * Creates config packet with stream parameters
     */
    fun createConfigPacket(width: Int, height: Int, fps: Int, bitrate: Int): ByteArray {
        val config = ByteBuffer.allocate(16).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(width)
            putInt(height)
            putInt(fps)
            putInt(bitrate)
        }.array()
        return createPacket(FRAME_TYPE_CONFIG, config)
    }

    /**
     * Parses config packet
     */
    fun parseConfigPacket(payload: ByteArray): ConfigInfo? {
        if (payload.size < 16) return null
        val buffer = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
        return ConfigInfo(
            width = buffer.getInt(),
            height = buffer.getInt(),
            fps = buffer.getInt(),
            bitrate = buffer.getInt()
        )
    }

    data class ConfigInfo(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int
    )

    /**
     * Creates heartbeat packet
     */
    fun createHeartbeat(): ByteArray {
        return createPacket(FRAME_TYPE_HEARTBEAT, ByteArray(0))
    }

    /**
     * Creates end of stream packet
     */
    fun createEndOfStream(): ByteArray {
        return createPacket(FRAME_TYPE_EOS, ByteArray(0))
    }
}