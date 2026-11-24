package com.cosmic.livestudio.sender

import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP Beacon Broadcaster Service
 * Broadcasts pairing code and TCP port on local network for auto-discovery
 */
class UdpBeaconBroadcaster : Service() {
    private val isRunning = AtomicBoolean(false)
    private var broadcastScope: CoroutineScope? = null
    private var datagramSocket: DatagramSocket? = null
    private var pairingCode = ""
    private var tcpPort = StreamProtocol.TCP_STREAMING_PORT

    override fun onCreate() {
        super.onCreate()
        acquireWifiMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pairingCode = intent?.getStringExtra("pairing_code") ?: ""
        tcpPort = intent?.getIntExtra("tcp_port", StreamProtocol.TCP_STREAMING_PORT) ?: StreamProtocol.TCP_STREAMING_PORT

        if (pairingCode.isNotEmpty() && !isRunning.get()) {
            startBroadcasting()
        }
        return START_STICKY
    }

    private fun acquireWifiMulticastLock() {
        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("CosmicLiveUdpLock")
        multicastLock.isRefCounted = true
        multicastLock.acquire()
    }

    private fun startBroadcasting() {
        isRunning.set(true)
        broadcastScope = CoroutineScope(Dispatchers.IO)

        broadcastScope?.launch {
            try {
                datagramSocket = DatagramSocket().apply {
                    setBroadcast(true)
                    // Set socket timeout
                    soTimeout = 1000
                    // Allow reuse
                    reuseAddress = true
                }

                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val payload = StreamProtocol.createDiscoveryPayload(pairingCode, tcpPort)
                val payloadBytes = payload.toByteArray()
                val packet = DatagramPacket(payloadBytes, payloadBytes.size, broadcastAddress, StreamProtocol.UDP_DISCOVERY_PORT)

                Log.d("UdpBeacon", "Starting broadcast: $payload on port ${StreamProtocol.UDP_DISCOVERY_PORT}")

                while (isRunning.get()) {
                    try {
                        datagramSocket?.send(packet)
                        // Also send to all local network interfaces
                        sendToAllInterfaces(payloadBytes)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w("UdpBeacon", "Broadcast send error", e)
                        }
                    }
                    delay(StreamProtocol.UDP_BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e("UdpBeacon", "Broadcast socket error", e)
            } finally {
                stopBroadcasting()
            }
        }
    }

    private fun sendToAllInterfaces(payload: ByteArray) {
        try {
            val interfaces = NetworkInterface.networkInterfaces
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue

                ni.interfaceAddresses.forEach { addr ->
                    val broadcast = addr.broadcast
                    if (broadcast != null) {
                        val packet = DatagramPacket(payload, payload.size, broadcast, StreamProtocol.UDP_DISCOVERY_PORT)
                        datagramSocket?.send(packet)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore interface enumeration errors
        }
    }

    private fun stopBroadcasting() {
        isRunning.set(false)
        datagramSocket?.close()
        datagramSocket = null
        broadcastScope?.coroutineContext.cancel()
        broadcastScope = null
        Log.d("UdpBeacon", "Broadcasting stopped")
    }

    override fun onDestroy() {
        stopBroadcasting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cosmic.livestudio.action.START_BEACON"
        const val ACTION_STOP = "com.cosmic.livestudio.action.STOP_BEACON"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_TCP_PORT = "tcp_port"
    }
}