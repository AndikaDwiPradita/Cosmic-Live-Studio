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
     * Interface prefixes that belong to mobile data / virtual networks.
     * Connecting to these IPs (e.g. carrier CGNAT 10.x.x.x) is impossible from
     * another phone on hotspot/wifi, so they must never be shown as pairing IP.
     */
    private val NON_LAN_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "seth", "ccinet", "usb", "tun", "tap", "dummy", "bp-", "ifb", "sit", "lo"
    )

    fun isLanInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return NON_LAN_PREFIXES.none { lower.startsWith(it) }
    }

    /** Human readable description of a network interface name. */
    fun describeInterface(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.startsWith("ap") || lower.startsWith("swlan") || lower.contains("softap") -> "Hotspot"
            lower.startsWith("wlan") -> "Wi-Fi"
            lower.startsWith("eth") -> "Ethernet"
            else -> "Antarmuka lain"
        }
    }

    private fun interfacePriority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("ap") || lower.startsWith("swlan") || lower.contains("softap") -> 0 // Hotspot AP
            lower.startsWith("wlan") -> 1 // Wi-Fi client
            lower.startsWith("eth") -> 2  // Ethernet / USB tethering
            else -> 3
        }
    }

    /**
     * Lists all usable IPv4 LAN candidates sorted best-first:
     * Hotspot AP > Wi-Fi > Ethernet > other non-cellular interfaces.
     * Mobile data interfaces (rmnet/ccmni/...) are excluded because their
     * carrier CGNAT addresses are unreachable for LAN pairing.
     */
    fun getLocalIpCandidates(): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>() // (interface, ip)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                if (!isLanInterfaceName(ni.name)) continue

                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    val hostAddress = address.hostAddress ?: continue
                    // Only IPv4, skip link-local (169.254.x.x)
                    if (!hostAddress.contains(":") && !hostAddress.startsWith("169.254.")) {
                        Log.d(TAG, "LAN candidate: $hostAddress on interface ${ni.name}")
                        candidates.add(ni.name to hostAddress)
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Error getting network interfaces", e)
        }
        return candidates.sortedBy { interfacePriority(it.first) }
    }

    /**
     * Gets the best local IPv4 address for peer-to-peer connections.
     * Priority: Hotspot/AP > Wi-Fi > Ethernet > other non-cellular.
     */
    fun getLocalIpAddress(): String? = getLocalIpCandidates().firstOrNull()?.second

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
