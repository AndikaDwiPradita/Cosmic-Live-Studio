package com.cosmic.livestudio.receiver

import android.util.Log
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * UDP Pairing Resolver
 * Listens for UDP beacon broadcasts and matches pairing code to discover sender IP/port
 */
class UdpPairingResolver(
    private val targetPairingCode: String,
    private val onResolved: (String, Int) -> Unit, // (ip, tcpPort)
    private val onError: (String) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var resolverScope: CoroutineScope? = null
    private var datagramSocket: DatagramSocket? = null
    private val resolvedRef = AtomicReference<Pair<String, Int>?>(null)

    fun start() {
        if (isRunning.getAndSet(true)) return

        resolverScope = CoroutineScope(Dispatchers.IO)
        resolverScope?.launch {
            try {
                datagramSocket = DatagramSocket(null).apply {
                    setReuseAddress(true)
                    bind(InetSocketAddress(StreamProtocol.UDP_DISCOVERY_PORT))
                    soTimeout = 1000
                }

                Log.d("UdpResolver", "Listening for pairing code: $targetPairingCode on port ${StreamProtocol.UDP_DISCOVERY_PORT}")

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                val startTime = System.currentTimeMillis()
                val timeoutMs = 15000L // 15 second timeout

                while (isRunning.get() && resolvedRef.get() == null) {
                    // Check timeout
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        onError("Timeout: Perangkat tidak ditemukan. Pastikan kedua HP terhubung ke jaringan yang sama.")
                        break
                    }

                    try {
                        datagramSocket?.receive(packet)
                        val receivedData = String(buffer, 0, packet.length)
                        val senderIp = packet.address.hostAddress

                        Log.d("UdpResolver", "Received from $senderIp: $receivedData")

                        val discoveryInfo = StreamProtocol.parseDiscoveryPayload(receivedData)
                        discoveryInfo?.let { info ->
                            if (info.app == StreamProtocol.DISCOVERY_APP_ID && info.code == targetPairingCode) {
                                Log.d("UdpResolver", "Pairing code matched! IP: $senderIp, TCP Port: ${info.tcpPort}")
                                resolvedRef.set(senderIp to info.tcpPort)
                                onResolved(senderIp, info.tcpPort)
                                break
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout - continue listening
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w("UdpResolver", "Receive error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpResolver", "Socket error", e)
                if (isRunning.get()) {
                    onError("Error jaringan: ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        datagramSocket?.close()
        datagramSocket = null
        resolverScope?.coroutineContext.cancel()
        resolverScope = null
        Log.d("UdpResolver", "Resolver stopped")
    }

    fun isResolved(): Boolean = resolvedRef.get() != null
}