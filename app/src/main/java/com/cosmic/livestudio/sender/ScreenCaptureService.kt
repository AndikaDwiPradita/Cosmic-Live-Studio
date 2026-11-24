package com.cosmic.livestudio.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cosmic.livestudio.R
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureService : Service() {
    private val isRunning = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var encoder: H264ScreenEncoder? = null
    private var tcpJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clientSocketRef = AtomicReference<Socket?>(null)
    private var notificationManager: NotificationManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val frameChannel = Channel<Pair<ByteArray, Boolean>>(128)
    private var currentPairingCode = ""
    private var currentTcpPort = StreamProtocol.TCP_STREAMING_PORT
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamFps = 30
    private var streamBitrate = 3_500_000
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CosmicLive::WakeLock")
        wakeLock?.acquire()
        Log.d("ScreenCapture", "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
        Log.d("ScreenCapture", "WakeLock released")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                currentPairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE) ?: generatePairingCode()
                currentTcpPort = intent.getIntExtra(EXTRA_TCP_PORT, StreamProtocol.TCP_STREAMING_PORT)
                streamWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                streamHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                streamFps = intent.getIntExtra(EXTRA_FPS, 30)
                streamBitrate = intent.getIntExtra(EXTRA_BITRATE, 3_500_000)

                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionData?.let { manager.getMediaProjection(android.app.Activity.RESULT_OK, it)?.let(::startCapture) }
            }
            ACTION_STOP -> stopCapture()
            ACTION_UPDATE_SETTINGS -> {
                streamWidth = intent.getIntExtra(EXTRA_WIDTH, streamWidth)
                streamHeight = intent.getIntExtra(EXTRA_HEIGHT, streamHeight)
                streamFps = intent.getIntExtra(EXTRA_FPS, streamFps)
                streamBitrate = intent.getIntExtra(EXTRA_BITRATE, streamBitrate)
                restartEncoder()
            }
        }
        return START_STICKY
    }

    private fun generatePairingCode() = (100000..999999).random().toString()

    private fun startCapture(projection: MediaProjection) {
        if (isRunning.getAndSet(true)) return
        mediaProjection = projection

        // Create encoder with proper keyframe handling
        val newEncoder = H264ScreenEncoder(streamWidth, streamHeight, streamFps, streamBitrate) { data, isKeyframe ->
            if (isRunning.get()) {
                frameChannel.trySend(data to isKeyframe)
            }
        }
        encoder = newEncoder
        val surface = newEncoder.start()
        if (surface == null) {
            Log.e("ScreenCapture", "Failed to start encoder")
            stopCapture()
            return
        }

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        virtualDisplay = projection.createVirtualDisplay(
            "CosmicLiveScreenCapture", streamWidth, streamHeight, metrics.densityDpi,
            0, surface, null, Handler(Looper.getMainLooper())
        )

        if (virtualDisplay == null) {
            Log.e("ScreenCapture", "Failed to create virtual display")
            stopCapture()
            return
        }

        startTcpServer()
        startUdpBeacon()
        showNotification()
        Log.d("ScreenCapture", "Capture started: ${streamWidth}x${streamHeight} @ ${streamFps}fps, code: $currentPairingCode")
    }

    private fun restartEncoder() {
        if (!isRunning.get()) return
        encoder?.stop()
        val newEncoder = H264ScreenEncoder(streamWidth, streamHeight, streamFps, streamBitrate) { data, isKeyframe ->
            if (isRunning.get()) frameChannel.trySend(data to isKeyframe)
        }
        encoder = newEncoder
        val surface = newEncoder.start()
        surface?.let { virtualDisplay?.setSurface(it) } ?: run { Log.e("ScreenCapture", "Restart encoder failed"); stopCapture() }
        Log.d("ScreenCapture", "Encoder restarted with new settings")
    }

    private fun startTcpServer() {
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = ServerSocket(currentTcpPort).apply {
                    reuseAddress = true
                    soTimeout = 1000
                    setReceiveBufferSize(256 * 1024)
                }
                serverSocket = socket
                Log.d("ScreenCapture", "TCP server listening on port $currentTcpPort")

                while (isRunning.get()) {
                    try {
                        val client = socket.accept().apply {
                            tcpNoDelay = true
                            setSendBufferSize(256 * 1024)
                            soTimeout = 5000
                        }
                        Log.d("ScreenCapture", "Client connected: ${client.inetAddress}")
                        handleClient(client)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Timeout - continue loop
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w("ScreenCapture", "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenCapture", "TCP server error", e)
            }
            Log.d("ScreenCapture", "TCP server stopped")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val previousSocket = clientSocketRef.getAndSet(socket)
        previousSocket?.close()

        // First, perform pairing handshake
        val pairingSuccess = performPairingHandshake(socket)
        if (!pairingSuccess) {
            Log.d("ScreenCapture", "Pairing failed, closing connection")
            if (clientSocketRef.compareAndSet(socket, null)) {
                try { socket.close() } catch (_: Exception) {}
            }
            return
        }

        // Pairing successful - send stream config and start streaming
        sendPacket(socket, StreamProtocol.createConfigPacket(streamWidth, streamHeight, streamFps, streamBitrate))

        try {
            val output = socket.getOutputStream()
            while (isRunning.get() && !socket.isClosed && clientSocketRef.get() == socket) {
                // Use non-blocking receive with timeout to allow checking connection state
                val frameData = withTimeoutOrNull(1000) { frameChannel.receive() }
                if (frameData == null) continue // Timeout - check loop condition

                val (data, isKeyframe) = frameData
                try {
                    output.write(StreamProtocol.createPacketFromNal(data))
                    output.flush()
                } catch (e: Exception) {
                    Log.w("ScreenCapture", "Write error, closing connection", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("ScreenCapture", "Client handling error", e)
        } finally {
            if (clientSocketRef.compareAndSet(socket, null)) {
                try { socket.close() } catch (_: Exception) {}
            }
            Log.d("ScreenCapture", "Client disconnected")
        }
    }

    /**
     * Performs pairing handshake with the connected client.
     * Waits for pairing request, validates code, sends response.
     */
    private suspend fun performPairingHandshake(socket: Socket): Boolean {
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArray(StreamProtocol.HEADER_SIZE)

        // Wait for pairing request with timeout
        try {
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n == -1) {
                    Log.w("ScreenCapture", "Client disconnected during pairing handshake")
                    return false
                }
                read += n
            }

            val (frameType, payloadLength) = StreamProtocol.parseHeader(header)
            if (frameType != StreamProtocol.FRAME_TYPE_PAIR_REQUEST) {
                Log.w("ScreenCapture", "Expected pairing request, got frame type: $frameType")
                sendPacket(socket, StreamProtocol.createPairErrorResponse("Invalid handshake: expected pairing request"))
                return false
            }

            if (payloadLength < 0 || payloadLength > 1024) {
                Log.w("ScreenCapture", "Invalid pairing request payload length: $payloadLength")
                sendPacket(socket, StreamProtocol.createPairErrorResponse("Invalid pairing request"))
                return false
            }

            val payload = ByteArray(payloadLength)
            read = 0
            while (read < payloadLength) {
                val n = input.read(payload, read, payloadLength - read)
                if (n == -1) {
                    Log.w("ScreenCapture", "Client disconnected during pairing payload read")
                    return false
                }
                read += n
            }

            // Parse pairing code from request
            val requestCode = StreamProtocol.parsePairRequestPayload(payload)
            if (requestCode == null) {
                Log.w("ScreenCapture", "Failed to parse pairing request")
                sendPacket(socket, StreamProtocol.createPairErrorResponse("Invalid pairing request format"))
                return false
            }

            Log.d("ScreenCapture", "Received pairing request with code: $requestCode, expected: $currentPairingCode")

            // Validate pairing code
            if (requestCode != currentPairingCode) {
                Log.w("ScreenCapture", "Pairing code mismatch")
                sendPacket(socket, StreamProtocol.createPairErrorResponse("Kode pairing tidak cocok"))
                return false
            }

            // Pairing successful - send success response with stream config
            val response = StreamProtocol.createPairSuccessResponse(streamWidth, streamHeight, streamFps, streamBitrate)
            sendPacket(socket, response)
            Log.d("ScreenCapture", "Pairing successful, streaming config sent")
            return true

        } catch (e: Exception) {
            Log.e("ScreenCapture", "Pairing handshake error", e)
            try {
                sendPacket(socket, StreamProtocol.createPairErrorResponse("Handshake error: ${e.message}"))
            } catch (_: Exception) {}
            return false
        }
    }

    private fun sendPacket(socket: Socket, packet: ByteArray) {
        try {
            val output = socket.getOutputStream()
            output.write(packet)
            output.flush()
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Failed to send packet", e)
            throw RuntimeException("Failed to send packet", e)
        }
    }

    private fun startUdpBeacon() {
        val intent = Intent(this, UdpBeaconBroadcaster::class.java).apply {
            action = UdpBeaconBroadcaster.ACTION_START
            putExtra(UdpBeaconBroadcaster.EXTRA_PAIRING_CODE, currentPairingCode)
            putExtra(UdpBeaconBroadcaster.EXTRA_TCP_PORT, currentTcpPort)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopUdpBeacon() {
        stopService(Intent(this, UdpBeaconBroadcaster::class.java).apply { action = UdpBeaconBroadcaster.ACTION_STOP })
    }

    private fun stopCapture() {
        if (!isRunning.getAndSet(false)) return
        Log.d("ScreenCapture", "Stopping capture...")

        encoder?.stop()
        encoder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        serverSocket?.close()
        serverSocket = null

        clientSocketRef.getAndSet(null)?.close()

        tcpJob?.cancel()
        tcpJob = null

        stopUdpBeacon()
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
    }

    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title_sender))
            .setContentText(getString(R.string.notification_text_sender, currentPairingCode))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_sender),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen sharing broadcast service"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "cosmic_live_sender"
        const val ACTION_START = "com.cosmic.livestudio.action.START_CAPTURE"
        const val ACTION_STOP = "com.cosmic.livestudio.action.STOP_CAPTURE"
        const val ACTION_UPDATE_SETTINGS = "com.cosmic.livestudio.action.UPDATE_SETTINGS"
        const val EXTRA_PROJECTION_DATA = "com.cosmic.livestudio.extra.MEDIA_PROJECTION_DATA"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
    }
}