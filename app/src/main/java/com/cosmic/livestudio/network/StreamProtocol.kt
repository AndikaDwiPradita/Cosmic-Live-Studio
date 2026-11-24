package com.cosmic.livestudio.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        if (nal.isEmpty()) return createPacket(FRAME_TYPE_P, nal)
        val nalType = nal[0].toInt() and 0x1F
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
        if (header.size < HEADER_SIZE) {
            throw IllegalArgumentException("Header too small: ${header.size} < $HEADER_SIZE")
        }
        val buffer = ByteBuffer.wrap(header).apply { order(ByteOrder.BIG_ENDIAN) }
        return buffer.getInt() to buffer.getInt()
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
        return try {
            val app = extractJsonString(json, "app")
            val code = extractJsonString(json, "code")
            val portStr = extractJsonNumber(json, "tcpPort")
            if (app != null && code != null && portStr != null) {
                val port = portStr.toIntOrNull()
                if (port != null) DiscoveryInfo(app, code, port) else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonNumber(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
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