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

    private var udpResolver: UdpPairingResolver? = null
    private var decoder: H264StreamDecoder? = null
    private var tcpJob: Job? = null
    private var socket: Socket? = null
    private var isConnected = false

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
        setConnectingState(true)
        udpResolver = UdpPairingResolver(code,
            onResolved = { ip, port -> runOnUiThread { tvConnectionInfo.text = "$ip:$port"; connectTcp(ip, port) } },
            onError = { error -> runOnUiThread { setConnectingState(false); showError(error) } }
        )
        udpResolver?.start()
    }

    private fun connectTcp(ip: String, port: Int) {
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val newSocket = Socket()
                socket = newSocket
                newSocket.setTcpNoDelay(true)
                newSocket.setReceiveBufferSize(256 * 1024)
                newSocket.connect(java.net.InetSocketAddress(ip, port), 5000)
                runOnUiThread { onConnected() }
                handleTcpStream(newSocket)
            } catch (e: Exception) {
                runOnUiThread { showError("Gagal terhubung: ${e.message}"); setConnectingState(false) }
            }
        }
    }

    private suspend fun handleTcpStream(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(StreamProtocol.HEADER_SIZE)
        while (isConnected && !socket.isClosed) {
            try {
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n == -1) throw java.io.EOFException("Stream ended")
                    read += n
                }
                val (_, payloadLength) = StreamProtocol.parseHeader(header)
                if (payloadLength < 0) throw IllegalStateException("Invalid packet length")
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
                if (isConnected) runOnUiThread { showError("Koneksi terputus: ${e.message}") }
                break
            }
        }
        runOnUiThread { disconnect() }
    }

    private fun onConnected() {
        isConnected = true
        setConnectedState(true)
        decoder = H264StreamDecoder(surfaceView.holder.surface,
            onStatsUpdate = { w, h, fps, bitrate -> runOnUiThread {
                tvStatResolution.text = "${w}x${h}"; tvStatFps.text = "$fps"; tvStatBitrate.text = formatBitrate(bitrate)
            } },
            onError = { error -> runOnUiThread { showError(error) } })
        decoder?.start()
    }

    private fun disconnect() {
        isConnected = false
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
    }

    private fun setConnectingState(connecting: Boolean) {
        tvStatus.text = getString(if (connecting) R.string.status_connecting else R.string.status_disconnected)
        tvStatus.setTextColor(getColor(if (connecting) R.color.status_connecting else R.color.status_disconnected))
        btnConnect.isEnabled = !connecting
        etPairingCode.isEnabled = !connecting
    }

    private fun setConnectedState(connected: Boolean) {
        tvStatus.text = getString(if (connected) R.string.status_connected else R.string.status_disconnected)
        tvStatus.setTextColor(getColor(if (connected) R.color.status_connected else R.color.status_disconnected))
        layoutPairingInput.visibility = if (connected) android.view.View.GONE else android.view.View.VISIBLE
        layoutConnectedControls.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        layoutStats.visibility = if (connected) android.view.View.VISIBLE else android.view.View.GONE
        if (!connected) {
            tvConnectionInfo.text = ""
            btnConnect.isEnabled = true
            etPairingCode.isEnabled = true
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
}
