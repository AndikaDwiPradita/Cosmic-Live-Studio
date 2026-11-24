package com.cosmic.livestudio.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

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
    // Keep frame types as Int because packet headers encode them as 32-bit integers.
    const val FRAME_TYPE_EOS = 0xFF

    // Pairing/Control Frame Types (0x10-0x1F range)
    const val FRAME_TYPE_PAIR_REQUEST = 0x10    // Receiver -> Sender: {code}
    const val FRAME_TYPE_PAIR_RESPONSE = 0x11   // Sender -> Receiver: {success, error?, width?, height?, fps?, bitrate?}
    const val FRAME_TYPE_PAIR_ERROR = 0x12      // Sender -> Receiver: {error}

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
     * Creates discovery payload JSON (UDP broadcast)
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

    // Pairing request/response payloads (JSON over framed packets)

    /**
     * Creates pairing request payload
     */
    fun createPairRequestPayload(code: String): ByteArray {
        val json = """{"code":"$code"}""".trimIndent()
        return createPacket(FRAME_TYPE_PAIR_REQUEST, json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Parses pairing request payload
     */
    fun parsePairRequestPayload(payload: ByteArray): String? {
        val json = String(payload, StandardCharsets.UTF_8)
        return extractJsonString(json, "code")
    }

    /**
     * Creates pairing success response
     */
    fun createPairSuccessResponse(width: Int, height: Int, fps: Int, bitrate: Int): ByteArray {
        val json = """{"success":true,"width":$width,"height":$height,"fps":$fps,"bitrate":$bitrate}"""
        return createPacket(FRAME_TYPE_PAIR_RESPONSE, json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Creates pairing error response
     */
    fun createPairErrorResponse(error: String): ByteArray {
        val json = """{"success":false,"error":"$error"}"""
        return createPacket(FRAME_TYPE_PAIR_RESPONSE, json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Parses pairing response payload
     */
    data class PairResponse(
        val success: Boolean,
        val error: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val bitrate: Int = 0
    )

    fun parsePairResponsePayload(payload: ByteArray): PairResponse? {
        return try {
            val json = String(payload, StandardCharsets.UTF_8)
            val success = extractJsonBoolean(json, "success") ?: false
            val error = extractJsonString(json, "error")
            val width = extractJsonNumber(json, "width")?.toIntOrNull() ?: 0
            val height = extractJsonNumber(json, "height")?.toIntOrNull() ?: 0
            val fps = extractJsonNumber(json, "fps")?.toIntOrNull() ?: 0
            val bitrate = extractJsonNumber(json, "bitrate")?.toIntOrNull() ?: 0
            PairResponse(success, error, width, height, fps, bitrate)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates config packet with stream parameters (legacy, kept for compatibility)
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
     * Parses config packet (legacy)
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

    // JSON parsing helpers
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonNumber(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean()
    }
}
