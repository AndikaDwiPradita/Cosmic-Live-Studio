package com.cosmic.livestudio.network

import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Data class for pairing information shared between sender and receiver.
 * Contains IP address, TCP port, and pairing code.
 */
data class PairingInfo(
    val ip: String,
    val port: Int,
    val code: String
) {
    /**
     * Validates the pairing info fields.
     */
    fun isValid(): Boolean {
        return NetworkUtils.isValidIp(ip) && port in 1..65535 && code.length == 6 && code.all { it.isDigit() }
    }

    /**
     * Creates a JSON string representation for QR code encoding.
     */
    fun toJson(): String {
        return """{"ip":"$ip","port":$port,"code":"$code"}"""
    }

    /**
     * Parses a PairingInfo from JSON string (from QR code or manual input).
     */
    companion object {
        fun fromJson(json: String): PairingInfo? {
            return try {
                val ip = extractJsonString(json, "ip")
                val portStr = extractJsonNumber(json, "port")
                val code = extractJsonString(json, "code")
                if (ip != null && portStr != null && code != null) {
                    val port = portStr.toIntOrNull()
                    if (port != null) PairingInfo(ip, port, code) else null
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
    }
}

/**
 * Utility functions for network operations.
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Gets the local IPv4 address that can be used for peer-to-peer connections.
     * Prefers Wi-Fi interface, falls back to other non-loopback interfaces.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue

                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    val hostAddress = address.hostAddress ?: continue
                    // Prefer IPv4 (no colons) and filter out link-local (169.254.x.x)
                    if (!hostAddress.contains(":") && !hostAddress.startsWith("169.254.")) {
                        Log.d(TAG, "Found local IP: $hostAddress on interface ${ni.name}")
                        return hostAddress
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Error getting network interfaces", e)
        }
        return null
    }

    /**
     * Validates an IPv4 or IPv6 address string.
     */
    fun isValidIp(ip: String): Boolean {
        if (ip.isEmpty()) return false
        try {
            val address = InetAddress.getByName(ip)
            return !address.isLoopbackAddress && !address.isAnyLocalAddress
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Validates a port number.
     */
    fun isValidPort(port: Int): Boolean = port in 1..65535

    /**
     * Validates pairing code format (6 digits).
     */
    fun isValidPairingCode(code: String): Boolean = code.length == 6 && code.all { it.isDigit() }
}
