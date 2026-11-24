package com.cosmic.livestudio.receiver

import android.util.Log
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UdpPairingResolver(
    private val targetPairingCode: String,
    private val onResolved: (String, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var resolverJob: Job? = null
    private var datagramSocket: DatagramSocket? = null
    private val resolvedRef = AtomicReference<Pair<String, Int>?>(null)

    fun start() {
        if (isRunning.getAndSet(true)) return
        resolverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket(null)
                datagramSocket = socket
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(StreamProtocol.UDP_DISCOVERY_PORT))
                socket.soTimeout = 1000

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                val startTime = System.currentTimeMillis()
                val timeoutMs = 15_000L

                while (isRunning.get() && resolvedRef.get() == null) {
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        if (isRunning.get()) onError("Timeout: Perangkat tidak ditemukan. Pastikan mode Bagikan Layar sudah dimulai dan kedua perangkat berada pada Wi-Fi yang sama.")
                        break
                    }
                    try {
                        socket.receive(packet)
                        val receivedData = String(buffer, 0, packet.length, Charsets.UTF_8)
                        val senderIp = packet.address.hostAddress ?: continue
                        val info = StreamProtocol.parseDiscoveryPayload(receivedData)
                        if (info != null && info.app == StreamProtocol.DISCOVERY_APP_ID && info.code == targetPairingCode) {
                            resolvedRef.set(senderIp to info.tcpPort)
                            onResolved(senderIp, info.tcpPort)
                            break
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w("UdpResolver", "Receive error", e)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) onError("Error jaringan: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        datagramSocket?.close()
        datagramSocket = null
        resolverJob?.cancel()
        resolverJob = null
    }

    fun isResolved(): Boolean = resolvedRef.get() != null
}
