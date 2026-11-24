package com.cosmic.livestudio.receiver

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cosmic.livestudio.R
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private var udpResolver: UdpPairingResolver? = null
    private var decoder: H264StreamDecoder? = null
    private var tcpScope: CoroutineScope? = null
    private var socket: Socket? = null
    private var isConnected = false
    private var currentPairingCode = ""
    private var connectedIp = ""
    private var connectedPort = 0

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
    }

    private fun setupSurfaceView() {
        surfaceView.holder.addCallback(this)
        surfaceView.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener { startPairing() }
        btnDisconnect.setOnClickListener { disconnect() }

        etPairingCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startPairing()
                true
            } else false
        }
    }

    private fun startPairing() {
        val code = etPairingCode.text.toString().trim().replace(" ", "")
        if (code.length != 6 || !code.all { it.isDigit() }) {
            Toast.makeText(this, "Masukkan 6-digit pairing code yang valid", Toast.LENGTH_SHORT).show()
            return
        }

        currentPairingCode = code
        setConnectingState(true)

        // Start UDP resolver
        udpResolver = UdpPairingResolver(
            targetPairingCode = code,
            onResolved = { ip, port ->
                runOnUiThread {
                    connectedIp = ip
                    connectedPort = port
                    tvConnectionInfo.text = "$ip:$port"
                    connectTcp(ip, port)
                }
            },
            onError = { error ->
                runOnUiThread {
                    setConnectingState(false)
                    showError(error)
                }
            }
        )
        udpResolver?.start()
    }

    private fun connectTcp(ip: String, port: Int) {
        tcpScope = CoroutineScope(Dispatchers.IO)
        tcpScope?.launch {
            try {
                socket = Socket().apply {
                    setSoTimeout(5000)
                    setTcpNoDelay(true)
                    setReceiveBufferSize(256 * 1024)
                }
                socket?.connect(java.net.InetSocketAddress(ip, port), 5000)

                runOnUiThread { onConnected() }
                handleTcpStream(socket!!)
            } catch (e: Exception) {
                Log.e("ReceiverActivity", "TCP connection error", e)
                runOnUiThread {
                    showError("Gagal terhubung ke HP 2: ${e.message}")
                    setConnectingState(false)
                }
            }
        }
    }

    private suspend fun handleTcpStream(socket: Socket) {
        val inputStream = BufferedInputStream(socket.getInputStream())
        val headerBuffer = ByteArray(StreamProtocol.HEADER_SIZE)

        while (isConnected && !socket.isClosed) {
            try {
                // Read header
                var bytesRead = 0
                while (bytesRead < StreamProtocol.HEADER_SIZE && isConnected) {
                    val read = inputStream.read(headerBuffer, bytesRead, StreamProtocol.HEADER_SIZE - bytesRead)
                    if (read == -1) {
                        throw java.io.EOFException("Stream ended")
                    }
                    bytesRead += read
                }

                val (frameType, payloadLength) = StreamProtocol.parseHeader(headerBuffer)

                // Read payload
                val payload = ByteArray(payloadLength)
                bytesRead = 0
                while (bytesRead < payloadLength && isConnected) {
                    val read = inputStream.read(payload, bytesRead, payloadLength - bytesRead)
                    if (read == -1) {
                        throw java.io.EOFException("Stream ended")
                    }
                    bytesRead += read
                }

                // Combine header + payload for decoder
                val fullPacket = ByteArray(StreamProtocol.HEADER_SIZE + payloadLength)
                System.arraycopy(headerBuffer, 0, fullPacket, 0, StreamProtocol.HEADER_SIZE)
                System.arraycopy(payload, 0, fullPacket, StreamProtocol.HEADER_SIZE, payloadLength)

                decoder?.queueFrame(fullPacket)

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

    private fun onConnected() {
        isConnected = true
        setConnectedState(true)

        // Initialize decoder with surface
        decoder = H264StreamDecoder(
            surface = surfaceView.holder.surface,
            onStatsUpdate = { w, h, fps, bitrate ->
                runOnUiThread {
                    tvStatResolution.text = "${w}x$h"
                    tvStatFps.text = "${fps}"
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

        decoder?.stop()
        decoder = null

        socket?.close()
        socket = null

        tcpScope?.coroutineContext.cancel()
        tcpScope = null

        udpResolver?.stop()
        udpResolver = null

        setConnectedState(false)
        clearStats()
        Toast.makeText(this, "Terputus dari HP 2", Toast.LENGTH_SHORT).show()
    }

    private fun setConnectingState(connecting: Boolean) {
        if (connecting) {
            tvStatus.text = getString(R.string.status_connecting)
            tvStatus.setTextColor(getColor(R.color.status_connecting))
            btnConnect.isEnabled = false
            etPairingCode.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
            btnConnect.isEnabled = true
            etPairingCode.isEnabled = true
        }
    }

    private fun setConnectedState(connected: Boolean) {
        if (connected) {
            tvStatus.text = getString(R.string.status_connected)
            tvStatus.setTextColor(getColor(R.color.status_connected))
            layoutPairingInput.visibility = android.view.View.GONE
            layoutConnectedControls.visibility = android.view.View.VISIBLE
            layoutStats.visibility = android.view.View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
            layoutPairingInput.visibility = android.view.View.VISIBLE
            layoutConnectedControls.visibility = android.view.View.GONE
            layoutStats.visibility = android.view.View.GONE
            tvConnectionInfo.text = ""
            btnConnect.isEnabled = true
            etPairingCode.isEnabled = true
            etPairingCode.text?.clear()
        }
    }

    private fun clearStats() {
        tvStatResolution.text = "--"
        tvStatFps.text = "--"
        tvStatBitrate.text = "--"
        tvStatLatency.text = "--"
    }

    private fun formatBitrate(bps: Long): String {
        return when {
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
            bps >= 1_000 -> String.format("%.0f Kbps", bps / 1000.0)
            else -> "${bps} bps"
        }
    }

    private fun showError(message: String) {
        tvStatus.text = getString(R.string.status_error)
        tvStatus.setTextColor(getColor(R.color.error))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface ready - decoder will be created on connect
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Surface size changed
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (isConnected) {
            disconnect()
        }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}