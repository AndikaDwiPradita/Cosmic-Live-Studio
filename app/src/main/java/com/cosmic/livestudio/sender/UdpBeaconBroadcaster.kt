package com.cosmic.livestudio.sender

import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class UdpBeaconBroadcaster : Service() {
    private val isRunning = AtomicBoolean(false)
    private var broadcastJob: Job? = null
    private var datagramSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pairingCode = ""
    private var tcpPort = StreamProtocol.TCP_STREAMING_PORT

    override fun onCreate() {
        super.onCreate()
        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("CosmicLiveUdpLock").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d("UdpBeacon", "Service created, MulticastLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pairingCode = intent?.getStringExtra(EXTRA_PAIRING_CODE) ?: ""
        tcpPort = intent?.getIntExtra(EXTRA_TCP_PORT, StreamProtocol.TCP_STREAMING_PORT) ?: StreamProtocol.TCP_STREAMING_PORT

        when (intent?.action) {
            ACTION_STOP -> stopBroadcasting()
            ACTION_START -> if (pairingCode.isNotEmpty() && !isRunning.get()) startBroadcasting()
        }
        return START_STICKY
    }

    private fun startBroadcasting() {
        isRunning.set(true)
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                    // Bind to any available port for sending
                }
                datagramSocket = socket

                val payload = StreamProtocol.createDiscoveryPayload(pairingCode, tcpPort).toByteArray(StandardCharsets.UTF_8)
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(payload, payload.size, broadcastAddress, StreamProtocol.UDP_DISCOVERY_PORT)

                Log.d("UdpBeacon", "Starting broadcast: ${String(payload, StandardCharsets.UTF_8)} on port ${StreamProtocol.UDP_DISCOVERY_PORT}")

                while (isRunning.get()) {
                    try {
                        socket.send(packet)
                        sendToAllInterfaces(socket, payload)
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w("UdpBeacon", "Broadcast send error", e)
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

    private fun sendToAllInterfaces(socket: DatagramSocket, payload: ByteArray) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                ni.interfaceAddresses.forEach { address ->
                    address.broadcast?.let { broadcast ->
                        try {
                            socket.send(DatagramPacket(payload, payload.size, broadcast, StreamProtocol.UDP_DISCOVERY_PORT))
                        } catch (e: Exception) {
                            // Ignore per-interface errors
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("UdpBeacon", "Interface enumeration error", e)
        }
    }

    private fun stopBroadcasting() {
        isRunning.set(false)
        datagramSocket?.close()
        datagramSocket = null
        broadcastJob?.cancel()
        broadcastJob = null
        Log.d("UdpBeacon", "Broadcasting stopped")
    }

    override fun onDestroy() {
        stopBroadcasting()
        multicastLock?.release()
        multicastLock = null
        Log.d("UdpBeacon", "Service destroyed, MulticastLock released")
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
