package com.cosmic.livestudio.receiver

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UdpPairingResolver(
    private val context: Context,
    private val targetPairingCode: String,
    private val onResolved: (String, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var resolverJob: Job? = null
    private var datagramSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolvedRef = AtomicReference<Pair<String, Int>?>(null)

    fun start() {
        if (isRunning.getAndSet(true)) return

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("CosmicLiveUdpResolverLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        resolverJob = CoroutineScope(Dispatchers.IO).launch {
            var bindRetries = 0
            val maxBindRetries = 3
            var socket: DatagramSocket? = null

            // Retry binding if port is in use (TIME_WAIT)
            while (bindRetries < maxBindRetries && isRunning.get()) {
                try {
                    socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 1000
                        bind(InetSocketAddress(StreamProtocol.UDP_DISCOVERY_PORT))
                    }
                    datagramSocket = socket
                    break
                } catch (e: BindException) {
                    bindRetries++
                    Log.w("UdpResolver", "Bind failed (attempt $bindRetries/$maxBindRetries), retrying...", e)
                    if (bindRetries < maxBindRetries && isRunning.get()) {
                        delay(500) // Wait before retry
                    }
                } catch (e: Exception) {
                    Log.e("UdpResolver", "Socket creation error", e)
                    if (isRunning.get()) onError("Error membuat socket: ${e.message}")
                    stop()
                    return
                }
            }

            if (socket == null) {
                if (isRunning.get()) onError("Gagal bind ke port UDP setelah $maxBindRetries percobaan. Coba restart aplikasi.")
                stop()
                return
            }

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            val startTime = System.currentTimeMillis()
            val timeoutMs = 30_000L

            Log.d("UdpResolver", "Listening for pairing code: $targetPairingCode on port ${StreamProtocol.UDP_DISCOVERY_PORT}")

            while (isRunning.get() && resolvedRef.get() == null) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    if (isRunning.get()) onError("Timeout: Perangkat tidak ditemukan. Pastikan mode 'Bagikan Layar' sudah dimulai di HP 2 dan kedua perangkat berada pada Wi-Fi/Hotspot yang sama.")
                    break
                }
                try {
                    socket.receive(packet)
                    val receivedData = String(buffer, 0, packet.length, StandardCharsets.UTF_8)
                    val senderIp = packet.address.hostAddress ?: continue
                    val info = StreamProtocol.parseDiscoveryPayload(receivedData)
                    if (info != null && info.app == StreamProtocol.DISCOVERY_APP_ID && info.code == targetPairingCode) {
                        Log.d("UdpResolver", "Pairing code matched! IP: $senderIp, TCP Port: ${info.tcpPort}")
                        resolvedRef.set(senderIp to info.tcpPort)
                        onResolved(senderIp, info.tcpPort)
                        break
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Timeout - continue listening
                } catch (e: Exception) {
                    if (isRunning.get()) Log.w("UdpResolver", "Receive error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        datagramSocket?.close()
        datagramSocket = null
        resolverJob?.cancel()
        resolverJob = null
        multicastLock?.release()
        multicastLock = null
        Log.d("UdpResolver", "Resolver stopped")
    }

    fun isResolved(): Boolean = resolvedRef.get() != null
}
