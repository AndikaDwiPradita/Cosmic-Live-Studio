package com.cosmic.livestudio.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object StreamProtocol {
    const val UDP_DISCOVERY_PORT = 45454
    const val UDP_BROADCAST_INTERVAL_MS = 1000L
    const val DISCOVERY_APP_ID = "COSMIC_LIVE"
    const val TCP_STREAMING_PORT = 45455

    const val FRAME_TYPE_SPS = 0x01
    const val FRAME_TYPE_PPS = 0x02
    const val FRAME_TYPE_IDR = 0x03
    const val FRAME_TYPE_P = 0x04
    const val FRAME_TYPE_B = 0x05
    const val FRAME_TYPE_CONFIG = 0x06
    const val FRAME_TYPE_HEARTBEAT = 0x07
    const val FRAME_TYPE_EOS = 0xFF

    const val HEADER_SIZE = 8

    fun createPacket(frameType: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(HEADER_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(frameType)
            putInt(payload.size)
        }.array()
        return header + payload
    }

    fun createPacketFromNal(nal: ByteArray): ByteArray {
        if (nal.isEmpty()) return createPacket(FRAME_TYPE_P, nal)
        val nalType = nal[0].toInt() and 0x1F
        val frameType = when (nalType) {
            7 -> FRAME_TYPE_SPS
            8 -> FRAME_TYPE_PPS
            5 -> FRAME_TYPE_IDR
            1 -> FRAME_TYPE_P
            else -> FRAME_TYPE_P
        }
        return createPacket(frameType, nal)
    }

    fun parseHeader(header: ByteArray): Pair<Int, Int> {
        val buffer = ByteBuffer.wrap(header).apply { order(ByteOrder.BIG_ENDIAN) }
        return buffer.getInt() to buffer.getInt()
    }

    fun createDiscoveryPayload(code: String, tcpPort: Int): String {
        return """{"app":"$DISCOVERY_APP_ID","code":"$code","tcpPort":$tcpPort}"""
    }

    data class DiscoveryInfo(val app: String, val code: String, val tcpPort: Int)

    fun parseDiscoveryPayload(json: String): DiscoveryInfo? {
        return try {
            val app = extractJsonValue(json, "app")
            val code = extractJsonValue(json, "code")
            val portStr = extractJsonValue(json, "tcpPort")
            if (app != null && code != null && portStr != null) {
                DiscoveryInfo(app, code, portStr.toInt())
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

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

    fun parseConfigPacket(payload: ByteArray): ConfigInfo? {
        if (payload.size < 16) return null
        val buffer = ByteBuffer.wrap(payload).apply { order(ByteOrder.BIG_ENDIAN) }
        return ConfigInfo(buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt())
    }

    data class ConfigInfo(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    fun createHeartbeat(): ByteArray = createPacket(FRAME_TYPE_HEARTBEAT, ByteArray(0))

    fun createEndOfStream(): ByteArray = createPacket(FRAME_TYPE_EOS, ByteArray(0))
}
