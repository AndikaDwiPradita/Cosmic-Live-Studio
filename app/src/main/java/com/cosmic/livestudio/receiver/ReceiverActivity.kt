package com.cosmic.livestudio.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cosmic.livestudio.R
import com.cosmic.livestudio.network.NetworkUtils
import com.cosmic.livestudio.network.PairingInfo
import com.cosmic.livestudio.network.StreamProtocol
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

class ReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectionInfo: TextView
    private lateinit var layoutStats: LinearLayout
    private lateinit var tvStatResolution: TextView
    private lateinit var tvStatFps: TextView
    private lateinit var tvStatBitrate: TextView
    private lateinit var tvStatLatency: TextView
    private lateinit var etManualIp: TextInputEditText
    private lateinit var btnScanQr: Button
    private lateinit var btnConnectManual: Button
    private lateinit var layoutConnectedControls: LinearLayout
    private lateinit var btnDisconnect: Button

    private var decoder: H264StreamDecoder? = null
    private var tcpJob: Job? = null
    private var socket: Socket? = null
    private var isConnected = false
    private var isConnecting = false
    private var currentIp = ""
    private var currentPort = StreamProtocol.TCP_STREAMING_PORT
    private var currentPairingCode = "000000"

    private val CAMERA_PERMISSION_REQUEST_CODE = 2002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        initViews()
        setupSurfaceView()
        setupListeners()
    }

    private fun initViews() {
        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvReceiverStatus)
        tvConnectionInfo = findViewById(R.id.tvConnectionInfo)
        layoutStats = findViewById(R.id.layoutStats)
        tvStatResolution = findViewById(R.id.tvStatResolution)
        tvStatFps = findViewById(R.id.tvStatFps)
        tvStatBitrate = findViewById(R.id.tvStatBitrate)
        tvStatLatency = findViewById(R.id.tvStatLatency)
        etManualIp = findViewById(R.id.etManualIp)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnConnectManual = findViewById(R.id.btnConnectManual)
        layoutConnectedControls = findViewById(R.id.layoutConnectedControls)
        btnDisconnect = findViewById(R.id.btnDisconnect)
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(this)
        @Suppress("DEPRECATION")
        surfaceView.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }

    private fun setupListeners() {
        btnScanQr.setOnClickListener { scanQrCode() }
        btnConnectManual.setOnClickListener { startManualConnect() }
        btnDisconnect.setOnClickListener { disconnect() }
        etManualIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startManualConnect()
                true
            } else false
        }
    }

    private fun scanQrCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            return
        }
        val integrator = IntentIntegrator(this)
        integrator.setPrompt("Pindai QR Code dari HP 2 (Pengirim)")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanQrCode()
            } else {
                Toast.makeText(this, "Izin kamera diperlukan untuk memindai QR Code", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Pemindaian dibatalkan", Toast.LENGTH_SHORT).show()
            } else {
                handleQrCodeResult(result.contents)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun handleQrCodeResult(qrContent: String) {
        // New format: COSMIC|<IP>|<PORT>
        if (qrContent.startsWith("COSMIC|")) {
            val parts = qrContent.split("|")
            if (parts.size >= 3) {
                val ip = parts[1].trim()
                val port = parts[2].trim().toIntOrNull() ?: StreamProtocol.TCP_STREAMING_PORT
                etManualIp.setText("$ip:$port")
                startConnection(ip, port, "000000")
                return
            }
        }

        // Legacy JSON format
        val pairingInfo = PairingInfo.fromJson(qrContent)
        if (pairingInfo != null && pairingInfo.isValid()) {
            etManualIp.setText("${pairingInfo.ip}:${pairingInfo.port}")
            startConnection(pairingInfo.ip, pairingInfo.port, pairingInfo.code)
            return
        }

        Toast.makeText(this, "QR Code tidak valid atau format tidak dikenali", Toast.LENGTH_LONG).show()
    }

    private fun startManualConnect() {
        val input = etManualIp.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "Masukkan IP:Port pengirim", Toast.LENGTH_SHORT).show()
            return
        }

        val (ip, port) = parseIpPort(input) ?: run {
            Toast.makeText(this, "Format tidak valid. Gunakan IP:Port, contoh 192.168.43.1:45455", Toast.LENGTH_LONG).show()
            return
        }

        if (!NetworkUtils.isValidIp(ip)) {
            Toast.makeText(this, "Alamat IP tidak valid", Toast.LENGTH_SHORT).show()
            return
        }
        if (!NetworkUtils.isValidPort(port)) {
            Toast.makeText(this, "Port tidak valid (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }

        startConnection(ip, port, "000000")
    }

    private fun parseIpPort(input: String): Pair<String, Int>? {
        val cleaned = input.trim()
        // Handle IPv6-like or simple host:port - prefer last colon for port
        val lastColon = cleaned.lastIndexOf(':')
        return if (lastColon > 0 && lastColon < cleaned.length - 1) {
            val host = cleaned.substring(0, lastColon).trim()
            val port = cleaned.substring(lastColon + 1).trim().toIntOrNull() ?: return null
            host to port
        } else {
            // No port specified, use default
            cleaned to StreamProtocol.TCP_STREAMING_PORT
        }
    }

    private fun startConnection(ip: String, port: Int, pairingCode: String) {
        currentIp = ip
        currentPort = port
        currentPairingCode = pairingCode
        setConnectingState(true)
        tvConnectionInfo.text = "$ip:$port"
        connectTcp(ip, port)
    }

    private fun connectTcp(ip: String, port: Int) {
        tcpJob?.cancel()
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val newSocket = Socket()
                socket = newSocket
                newSocket.tcpNoDelay = true
                newSocket.receiveBufferSize = 256 * 1024
                newSocket.soTimeout = 5000
                newSocket.connect(InetSocketAddress(ip, port), 8000)
                handleTcpStream(newSocket)
            } catch (e: Exception) {
                Log.e(TAG, "TCP connection error", e)
                runOnUiThread {
                    if (isConnecting || isConnected) {
                        showError("Gagal terhubung: ${e.message}")
                    }
                    disconnectInternal(showToast = false)
                }
            }
        }
    }

    private suspend fun handleTcpStream(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())

        val handshakeOk = performPairingHandshake(socket, input)
        if (!handshakeOk) {
            runOnUiThread { disconnectInternal(showToast = false) }
            return
        }

        runOnUiThread { onConnected() }

        val header = ByteArray(StreamProtocol.HEADER_SIZE)
        while (isConnected && !socket.isClosed) {
            try {
                readFully(input, header, header.size)
                val (_, payloadLength) = StreamProtocol.parseHeader(header)
                if (payloadLength < 0 || payloadLength > 1024 * 1024) {
                    throw IllegalStateException("Invalid packet length: $payloadLength")
                }
                val payload = ByteArray(payloadLength)
                readFully(input, payload, payloadLength)
                val packet = header + payload
                decoder?.queueFrame(packet)
            } catch (e: Exception) {
                if (isConnected) {
                    Log.e(TAG, "Stream read error", e)
                    runOnUiThread { showError("Koneksi terputus: ${e.message}") }
                }
                break
            }
        }
        runOnUiThread { disconnectInternal(showToast = false) }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n == -1) throw java.io.EOFException("Stream ended")
            read += n
        }
    }

    private suspend fun performPairingHandshake(socket: Socket, input: BufferedInputStream): Boolean {
        return try {
            val output = socket.getOutputStream()
            output.write(StreamProtocol.createPairRequestPayload(currentPairingCode))
            output.flush()

            val header = ByteArray(StreamProtocol.HEADER_SIZE)
            readFully(input, header, header.size)
            val (frameType, payloadLength) = StreamProtocol.parseHeader(header)
            if (frameType != StreamProtocol.FRAME_TYPE_PAIR_RESPONSE) {
                Log.w(TAG, "Expected pairing response, got frame type: $frameType")
                return false
            }
            if (payloadLength < 0 || payloadLength > 4096) return false

            val payload = ByteArray(payloadLength)
            readFully(input, payload, payloadLength)
            val response = StreamProtocol.parsePairResponsePayload(payload) ?: return false
            if (!response.success) {
                runOnUiThread { showError(response.error ?: "Pairing gagal") }
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pairing handshake error", e)
            runOnUiThread { showError("Handshake error: ${e.message}") }
            false
        }
    }

    private fun onConnected() {
        isConnected = true
        isConnecting = false
        setConnectedUi(true)
        decoder = H264StreamDecoder(
            surface = surfaceView.holder.surface,
            onStatsUpdate = { w, h, fps, bitrate ->
                runOnUiThread {
                    tvStatResolution.text = "${w}x${h}"
                    tvStatFps.text = "$fps"
                    tvStatBitrate.text = formatBitrate(bitrate)
                    tvStatLatency.text = "< 50 ms"
                }
            },
            onError = { error ->
                runOnUiThread { showError(error) }
            }
        )
        decoder?.start()
        Toast.makeText(this, "Terhubung ke pengirim", Toast.LENGTH_SHORT).show()
    }

    private fun disconnect() {
        disconnectInternal(showToast = true)
    }

    private fun disconnectInternal(showToast: Boolean) {
        val wasConnected = isConnected || isConnecting
        isConnected = false
        isConnecting = false

        decoder?.stop()
        decoder = null

        try { socket?.close() } catch (_: Exception) {}
        socket = null

        tcpJob?.cancel()
        tcpJob = null

        runOnUiThread {
            setConnectedUi(false)
            clearStats()
            if (showToast && wasConnected) {
                Toast.makeText(this, "Terputus", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setConnectingState(connecting: Boolean) {
        isConnecting = connecting
        tvStatus.text = getString(R.string.status_connecting)
        tvStatus.setTextColor(getColor(R.color.status_connecting))
        btnConnectManual.isEnabled = false
        btnScanQr.isEnabled = false
        etManualIp.isEnabled = false
    }

    private fun setConnectedUi(connected: Boolean) {
        if (connected) {
            tvStatus.text = getString(R.string.status_connected)
            tvStatus.setTextColor(getColor(R.color.status_connected))
            btnConnectManual.visibility = View.GONE
            btnScanQr.visibility = View.GONE
            etManualIp.isEnabled = false
            layoutConnectedControls.visibility = View.VISIBLE
            layoutStats.visibility = View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
            btnConnectManual.visibility = View.VISIBLE
            btnConnectManual.isEnabled = true
            btnScanQr.visibility = View.VISIBLE
            btnScanQr.isEnabled = true
            etManualIp.isEnabled = true
            layoutConnectedControls.visibility = View.GONE
            layoutStats.visibility = View.GONE
            tvConnectionInfo.text = ""
        }
    }

    private fun clearStats() {
        tvStatResolution.text = "--"
        tvStatFps.text = "--"
        tvStatBitrate.text = "--"
        tvStatLatency.text = "--"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatBitrate(bitrate: Long): String {
        return when {
            bitrate >= 1_000_000L -> String.format("%.1f Mbps", bitrate / 1_000_000.0)
            bitrate >= 1_000L -> String.format("%.0f kbps", bitrate / 1_000.0)
            else -> "$bitrate bps"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        decoder?.stop()
    }

    override fun onDestroy() {
        disconnectInternal(showToast = false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReceiverActivity"
    }
}
