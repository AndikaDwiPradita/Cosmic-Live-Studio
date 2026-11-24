package com.cosmic.livestudio.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cosmic.livestudio.R
import com.cosmic.livestudio.network.NetworkUtils
import com.cosmic.livestudio.network.PairingInfo
import com.cosmic.livestudio.network.StreamProtocol
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.Socket

class ReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectionInfo: TextView
    private lateinit var layoutStats: android.widget.LinearLayout
    private lateinit var tvStatResolution: TextView
    private lateinit var tvStatFps: TextView
    private lateinit var tvStatBitrate: TextView
    private lateinit var tvStatLatency: TextView
    private lateinit var layoutPairingInput: android.widget.LinearLayout
    private lateinit var etPairingCode: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var layoutConnectedControls: android.widget.LinearLayout
    private lateinit var btnDisconnect: Button

    // Manual pairing views
    private lateinit var radioGroupPairingMode: RadioGroup
    private lateinit var radioUdpDiscovery: RadioButton
    private lateinit var radioManualPairing: RadioButton
    private lateinit var layoutManualPairing: android.widget.LinearLayout
    private lateinit var etIpAddress: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPort: com.google.android.material.textfield.TextInputEditText
    private lateinit var etManualPairingCode: com.google.android.material.textfield.TextInputEditText
    private lateinit var btnScanQr: Button
    private lateinit var btnManualConnect: Button

    private var udpResolver: UdpPairingResolver? = null
    private var decoder: H264StreamDecoder? = null
    private var tcpJob: Job? = null
    private var socket: Socket? = null
    private var isConnected = false
    private var isConnecting = false
    private var currentPairingCode = ""
    private var currentIp = ""
    private var currentPort = StreamProtocol.TCP_STREAMING_PORT
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    private val QR_SCAN_REQUEST_CODE = 2001
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
        layoutPairingInput = findViewById(R.id.layoutPairingInput)
        etPairingCode = findViewById(R.id.etPairingCode)
        btnConnect = findViewById(R.id.btnConnect)
        layoutConnectedControls = findViewById(R.id.layoutConnectedControls)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // Manual pairing views
        radioGroupPairingMode = findViewById(R.id.radioGroupPairingMode)
        radioUdpDiscovery = findViewById(R.id.radioUdpDiscovery)
        radioManualPairing = findViewById(R.id.radioManualPairing)
        layoutManualPairing = findViewById(R.id.layoutManualPairing)
        etIpAddress = findViewById(R.id.etIpAddress)
        etPort = findViewById(R.id.etPort)
        etManualPairingCode = findViewById(R.id.etManualPairingCode)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnManualConnect = findViewById(R.id.btnManualConnect)
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(this)
        surfaceView.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }

    private fun setupListeners() {
        // UDP Discovery mode (default)
        btnConnect.setOnClickListener { startUdpPairing() }
        btnDisconnect.setOnClickListener { disconnect() }
        etPairingCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startUdpPairing()
                true
            } else false
        }

        // Manual pairing mode
        radioGroupPairingMode.setOnCheckedChangeListener { _, checkedId ->
            val isManual = checkedId == R.id.radioManualPairing
            layoutPairingInput.visibility = if (isManual) android.view.View.GONE else android.view.View.VISIBLE
            layoutManualPairing.visibility = if (isManual) android.view.View.VISIBLE else android.view.View.GONE
        }

        btnScanQr.setOnClickListener { scanQrCode() }
        btnManualConnect.setOnClickListener { startManualPairing() }
        etManualPairingCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startManualPairing()
                true
            } else false
        }
    }

    // ==================== UDP Discovery Pairing ====================
    private fun startUdpPairing() {
        val code = etPairingCode.text.toString().trim().replace(" ", "")
        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(this, "Masukkan 6-digit pairing code yang valid", Toast.LENGTH_SHORT).show()
            return
        }

        currentPairingCode = code
        reconnectAttempts = 0
        setConnectingState(true)
        startUdpDiscovery()
    }

    private fun startUdpDiscovery() {
        udpResolver = UdpPairingResolver(
            context = this,
            targetPairingCode = currentPairingCode,
            onResolved = { ip, port ->
                runOnUiThread {
                    currentIp = ip
                    currentPort = port
                    tvConnectionInfo.text = "$ip:$port"
                    connectTcp(ip, port)
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (isConnecting) {
                        attemptReconnect(error)
                    } else {
                        setConnectingState(false)
                        showError(error)
                    }
                }
            }
        )
        udpResolver?.start()
    }

    // ==================== Manual Pairing ====================
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
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QR_SCAN_REQUEST_CODE) {
            val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            if (result != null) {
                if (result.contents == null) {
                    Toast.makeText(this, "Pemindaian dibatalkan", Toast.LENGTH_SHORT).show()
                } else {
                    handleQrCodeResult(result.contents)
                }
            }
        }
    }

    private fun handleQrCodeResult(qrContent: String) {
        val pairingInfo = PairingInfo.fromJson(qrContent)
        if (pairingInfo == null || !pairingInfo.isValid()) {
            Toast.makeText(this, "QR Code tidak valid atau format tidak dikenali", Toast.LENGTH_LONG).show()
            return
        }

        // Fill in the manual pairing fields
        etIpAddress.setText(pairingInfo.ip)
        etPort.setText(pairingInfo.port.toString())
        etManualPairingCode.setText(pairingInfo.code)

        Toast.makeText(this, "QR Code berhasil dipindai. Tekan 'Hubungkan Manual' untuk melanjutkan.", Toast.LENGTH_SHORT).show()
    }

    private fun startManualPairing() {
        val ip = etIpAddress.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        val code = etManualPairingCode.text.toString().trim().replace(" ", "")

        // Validate inputs
        if (!NetworkUtils.isValidIp(ip)) {
            Toast.makeText(this, "Alamat IP tidak valid", Toast.LENGTH_SHORT).show()
            return
        }
        val port = portStr.toIntOrNull()
        if (port == null || !NetworkUtils.isValidPort(port)) {
            Toast.makeText(this, "Port tidak valid (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }
        if (!NetworkUtils.isValidPairingCode(code)) {
            Toast.makeText(this, "Masukkan 6-digit pairing code yang valid", Toast.LENGTH_SHORT).show()
            return
        }

        currentIp = ip
        currentPort = port
        currentPairingCode = code
        reconnectAttempts = 0
        setConnectingState(true)
        connectTcp(ip, port)
    }

    // ==================== TCP Connection ====================
    private fun connectTcp(ip: String, port: Int) {
        if (!isConnecting) return
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val newSocket = Socket()
                socket = newSocket
                newSocket.setTcpNoDelay(true)
                newSocket.setReceiveBufferSize(256 * 1024)
                newSocket.setSoTimeout(5000)
                newSocket.connect(java.net.InetSocketAddress(ip, port), 8000)
                runOnUiThread { onConnected() }
                handleTcpStream(newSocket)
            } catch (e: Exception) {
                Log.e("ReceiverActivity", "TCP connection error", e)
                runOnUiThread {
                    if (isConnecting) {
                        attemptReconnect("Gagal terhubung: ${e.message}")
                    } else {
                        showError("Gagal terhubung: ${e.message}")
                        setConnectingState(false)
                    }
                }
            }
        }
    }

    private fun attemptReconnect(lastError: String) {
        reconnectAttempts++
        if (reconnectAttempts <= maxReconnectAttempts) {
            val delayMs = minOf(1000 * (2 * reconnectAttempts), 10000) // Exponential backoff
            tvStatus.text = "Mencoba ulang... ($reconnectAttempts/$maxReconnectAttempts)"
            tvStatus.setTextColor(getColor(R.color.status_connecting))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isConnecting) {
                    // Re-try based on current mode
                    if (radioUdpDiscovery.isChecked) {
                        udpResolver?.stop()
                        udpResolver = null
                        startUdpDiscovery()
                    } else {
                        connectTcp(currentIp, currentPort)
                    }
                }
            }, delayMs.toLong())
        } else {
            setConnectingState(false)
            showError("Gagal terhubung setelah $maxReconnectAttempts percobaan. $lastError")
        }
    }

    private suspend fun handleTcpStream(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(StreamProtocol.HEADER_SIZE)

        // Always perform pairing handshake (both UDP and Manual modes)
        val handshakeSuccess = performPairingHandshake(socket)
        if (!handshakeSuccess) {
            runOnUiThread { disconnect() }
            return
        }

        // Now handle video stream
        while (isConnected && !socket.isClosed) {
            try {
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n == -1) throw java.io.EOFException("Stream ended")
                    read += n
                }
                val (_, payloadLength) = StreamProtocol.parseHeader(header)
                if (payloadLength < 0 || payloadLength > 1024 * 1024) {
                    throw IllegalStateException("Invalid packet length: $payloadLength")
                }
                val payload = ByteArray(payloadLength)
                read = 0
                while (read < payloadLength) {
                    val n = input.read(payload, read, payloadLength - read)
                    if (n == -1) throw java.io.EOFException("Stream ended")
                    read += n
                }
                val packet = header + payload
                decoder?.queueFrame(packet)
            } catch (e: Exception) {
                if (isConnected) {
                    Log.e("ReceiverActivity", "Stream read error", e)
                    runOnUiThread { showError("Koneksi terputus: ${e.message}") }
                }
                break
            }
        }
        runOnUiThread { disconnect() }
    }

    /**
     * Performs pairing handshake for manual pairing mode.
     * Sends pairing request, waits for response.
     */
    private suspend fun performPairingHandshake(socket: Socket): Boolean {
        val output = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(StreamProtocol.HEADER_SIZE)

        try {
            // Send pairing request
            val requestPacket = StreamProtocol.createPairRequestPayload(currentPairingCode)
            output.write(requestPacket)
            output.flush()
            Log.d("ReceiverActivity", "Sent pairing request with code: $currentPairingCode")

            // Read pairing response
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n == -1) throw java.io.EOFException("Stream ended during handshake")
                read += n
            }

            val (frameType, payloadLength) = StreamProtocol.parseHeader(header)
            if (frameType != StreamProtocol.FRAME_TYPE_PAIR_RESPONSE) {
                Log.w("ReceiverActivity", "Expected pairing response, got frame type: $frameType")
                return false
            }

            if (payloadLength < 0 || payloadLength > 4096) {
                Log.w("ReceiverActivity", "Invalid pairing response payload length: $payloadLength")
                return false
            }

            val payload = ByteArray(payloadLength)
            read = 0
            while (read < payloadLength) {
                val n = input.read(payload, read, payloadLength - read)
                if (n == -1) throw java.io.EOFException("Stream ended during handshake payload")
                read += n
            }

            val response = StreamProtocol.parsePairResponsePayload(payload)
            if (response == null) {
                Log.w("ReceiverActivity", "Failed to parse pairing response")
                return false
            }

            if (!response.success) {
                val errorMsg = response.error ?: "Pairing gagal: kode tidak cocok"
                Log.w("ReceiverActivity", "Pairing failed: $errorMsg")
                runOnUiThread { showError(errorMsg) }
                return false
            }

            Log.d("ReceiverActivity", "Pairing successful! Stream config: ${response.width}x${response.height} @ ${response.fps}fps")
            return true

        } catch (e: Exception) {
            Log.e("ReceiverActivity", "Pairing handshake error", e)
            runOnUiThread { showError("Handshake error: ${e.message}") }
            return false
        }
    }

    private fun onConnected() {
        isConnected = true
        isConnecting = false
        reconnectAttempts = 0
        setConnectedState(true)
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
        Toast.makeText(this, "Terhubung ke HP 2", Toast.LENGTH_SHORT).show()
    }

    private fun disconnect() {
        isConnected = false
        isConnecting = false

        decoder?.stop()
        decoder = null

        socket?.close()
        socket = null

        tcpJob?.cancel()
        tcpJob = null

        udpResolver?.stop()
        udpResolver = null

        setConnectedState(false)
        clearStats()
        Toast.makeText(this, "Terputus dari HP 2", Toast.LENGTH_SHORT).show()
    }

    private fun setConnectingState(connecting: Boolean) {
        isConnecting = connecting
        if (connecting) {
            tvStatus.text = getString(R.string.status_connecting)
            tvStatus.setTextColor(getColor(R.color.status_connecting))
            btnConnect.isEnabled = false
            etPairingCode.isEnabled = false
            btnManualConnect.isEnabled = false
            btnScanQr.isEnabled = false
            etIpAddress.isEnabled = false
            etPort.isEnabled = false
            etManualPairingCode.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
            btnConnect.isEnabled = true
            etPairingCode.isEnabled = true
            btnManualConnect.isEnabled = true
            btnScanQr.isEnabled = true
            etIpAddress.isEnabled = true
            etPort.isEnabled = true
            etManualPairingCode.isEnabled = true
        }
    }

    private fun setConnectedState(connected: Boolean) {
        if (connected) {
            tvStatus.text = getString(R.string.status_connected)
            tvStatus.setTextColor(getColor(R.color.status_connected))
            layoutPairingInput.visibility = android.view.View.GONE
            layoutManualPairing.visibility = android.view.View.GONE
            radioGroupPairingMode.visibility = android.view.View.GONE
            layoutConnectedControls.visibility = android.view.View.VISIBLE
            layoutStats.visibility = android.view.View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
            layoutPairingInput.visibility = android.view.View.VISIBLE
            layoutManualPairing.visibility = android.view.View.GONE
            radioGroupPairingMode.visibility = android.view.View.VISIBLE
            layoutConnectedControls.visibility = android.view.View.GONE
            layoutStats.visibility = android.view.View.GONE
            tvConnectionInfo.text = ""
            btnConnect.isEnabled = true
            etPairingCode.isEnabled = true
            btnManualConnect.isEnabled = true
            btnScanQr.isEnabled = true
            etIpAddress.isEnabled = true
            etPort.isEnabled = true
            etManualPairingCode.isEnabled = true
            etPairingCode.text?.clear()
            etManualPairingCode.text?.clear()
        }
    }

    private fun clearStats() {
        tvStatResolution.text = "--"
        tvStatFps.text = "--"
        tvStatBitrate.text = "--"
        tvStatLatency.text = "--"
    }

    private fun formatBitrate(bps: Long): String = when {
        bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format("%.0f Kbps", bps / 1000.0)
        else -> "${bps} bps"
    }

    private fun showError(message: String) {
        tvStatus.text = getString(R.string.status_error)
        tvStatus.setTextColor(getColor(R.color.error))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (isConnected) disconnect() }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    companion object {
        const val QR_SCAN_REQUEST_CODE = 2001
        const val CAMERA_PERMISSION_REQUEST_CODE = 2002
    }
}