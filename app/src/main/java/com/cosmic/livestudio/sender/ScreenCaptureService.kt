package com.cosmic.livestudio.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cosmic.livestudio.CosmicLiveApplication
import com.cosmic.livestudio.network.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen Capture Foreground Service
 * Handles MediaProjection, H.264 encoding, and TCP streaming to receiver
 */
class ScreenCaptureService : Service() {
    private val isRunning = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var encoder: H264ScreenEncoder? = null
    private var tcpServerScope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private val clientSocketRef = AtomicReference<Socket?>(null)
    private var notificationManager: NotificationManager? = null
    private var virtualDisplay: android.media.projection.VirtualDisplay? = null
    private val frameChannel = Channel<Pair<ByteArray, Boolean>>(128)
    private var currentPairingCode = ""
    private var currentTcpPort = StreamProtocol.TCP_STREAMING_PORT
    private var streamConfigSent = false

    // Stream settings
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamFps = 30
    private var streamBitrate = 3_500_000 // 3.5 Mbps

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ScreenCaptureService.ACTION_START -> {
                val projectionData = intent.getParcelableExtra<MediaProjection>(
                    MediaProjectionManager.EXTRA_MEDIA_PROJECTION
                )
                currentPairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE) ?: generatePairingCode()
                currentTcpPort = intent.getIntExtra(EXTRA_TCP_PORT, StreamProtocol.TCP_STREAMING_PORT)
                streamWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                streamHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                streamFps = intent.getIntExtra(EXTRA_FPS, 30)
                streamBitrate = intent.getIntExtra(EXTRA_BITRATE, 3_500_000)

                projectionData?.let { startCapture(it) }
            }
            ScreenCaptureService.ACTION_STOP -> stopCapture()
            ScreenCaptureService.ACTION_UPDATE_SETTINGS -> {
                streamWidth = intent.getIntExtra(EXTRA_WIDTH, streamWidth)
                streamHeight = intent.getIntExtra(EXTRA_HEIGHT, streamHeight)
                streamFps = intent.getIntExtra(EXTRA_FPS, streamFps)
                streamBitrate = intent.getIntExtra(EXTRA_BITRATE, streamBitrate)
                restartEncoder()
            }
        }
        return START_STICKY
    }

    private fun generatePairingCode(): String {
        return (100000..999999).random().toString()
    }

    private fun startCapture(projection: MediaProjection) {
        if (isRunning.getAndSet(true)) return

        mediaProjection = projection
        streamConfigSent = false

        // Start H.264 encoder
        encoder = H264ScreenEncoder(
            width = streamWidth,
            height = streamHeight,
            fps = streamFps,
            bitrate = streamBitrate,
            onFrameEncoded = { data, isKeyframe ->
                if (isRunning.get()) {
                    frameChannel.trySend(data to isKeyframe)
                }
            }
        )

        val inputSurface = encoder?.start()
        if (inputSurface == null) {
            Log.e("ScreenCapture", "Failed to create encoder input surface")
            stopCapture()
            return
        }

        // Create virtual display for screen capture
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val density = metrics.densityDpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CosmicLiveScreenCapture",
            streamWidth,
            streamHeight,
            density,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            inputSurface,
            null,
            Handler(Looper.getMainLooper())
        )

        // Start TCP server for streaming
        startTcpServer()

        // Start UDP beacon broadcaster
        startUdpBeacon()

        // Show foreground notification
        showNotification()

        Log.d("ScreenCapture", "Capture started: ${streamWidth}x${streamHeight} @ ${streamFps}fps, pairing code: $currentPairingCode")
    }

    private fun restartEncoder() {
        if (!isRunning.get()) return
        encoder?.stop()
        val inputSurface = encoder?.let { H264ScreenEncoder(
            width = streamWidth,
            height = streamHeight,
            fps = streamFps,
            bitrate = streamBitrate,
            onFrameEncoded = { data, isKeyframe ->
                if (isRunning.get()) {
                    frameChannel.trySend(data to isKeyframe)
                }
            }
        )?.start() }
        virtualDisplay?.setSurface(inputSurface!!)
        streamConfigSent = false
        Log.d("ScreenCapture", "Encoder restarted with new settings")
    }

    private fun startTcpServer() {
        tcpServerScope = CoroutineScope(Dispatchers.IO)
        tcpServerScope?.launch {
            try {
                serverSocket = ServerSocket(currentTcpPort).apply {
                    setReuseAddress(true)
                    soTimeout = 1000
                }
                Log.d("ScreenCapture", "TCP server listening on port $currentTcpPort")

                while (isRunning.get()) {
                    try {
                        val socket = serverSocket?.accept()?.apply {
                            setTcpNoDelay(true)
                            setSendBufferSize(256 * 1024)
                            soTimeout = 5000
                        }
                        socket?.let { handleClient(it) }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout - continue loop
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w("ScreenCapture", "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenCapture", "TCP server error", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        val previousSocket = clientSocketRef.getAndSet(socket)
        previousSocket?.close()

        Log.d("ScreenCapture", "Client connected: ${socket.inetAddress}")
        streamConfigSent = false

        // Send config packet first
        val configPacket = StreamProtocol.createConfigPacket(streamWidth, streamHeight, streamFps, streamBitrate)
        sendPacket(socket, configPacket)

        // Send SPS/PPS will be sent via encoder callback when first keyframe arrives
        // But we need to ensure config is sent
        streamConfigSent = true

        try {
            val outputStream = socket.getOutputStream()
            while (isRunning.get() && !socket.isClosed && clientSocketRef.get() == socket) {
                val frameData = frameChannel.receive()
                if (frameData == null) break

                val (data, isKeyframe) = frameData
                val packet = StreamProtocol.createPacketFromNal(data)
                sendPacket(socket, packet)
            }
        } catch (e: Exception) {
            Log.w("ScreenCapture", "Client handling error", e)
        } finally {
            if (clientSocketRef.get() == socket) {
                clientSocketRef.compareAndSet(socket, null)
            }
            try { socket.close() } catch (_: Exception) {}
            Log.d("ScreenCapture", "Client disconnected")
        }
    }

    private fun sendPacket(socket: Socket, packet: ByteArray) {
        try {
            val outputStream = socket.getOutputStream()
            outputStream.write(packet)
            outputStream.flush()
        } catch (e: Exception) {
            throw RuntimeException("Failed to send packet", e)
        }
    }

    private fun startUdpBeacon() {
        val intent = Intent(this, UdpBeaconBroadcaster::class.java).apply {
            action = UdpBeaconBroadcaster.ACTION_START
            putExtra(UdpBeaconBroadcaster.EXTRA_PAIRING_CODE, currentPairingCode)
            putExtra(UdpBeaconBroadcaster.EXTRA_TCP_PORT, currentTcpPort)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopUdpBeacon() {
        val intent = Intent(this, UdpBeaconBroadcaster::class.java).apply {
            action = UdpBeaconBroadcaster.ACTION_STOP
        }
        stopService(intent)
    }

    private fun stopCapture() {
        if (!isRunning.getAndSet(false)) return

        // Stop encoder
        encoder?.stop()
        encoder = null

        // Stop virtual display
        virtualDisplay?.release()
        virtualDisplay = null

        // Stop media projection
        mediaProjection?.stop()
        mediaProjection = null

        // Close server socket
        serverSocket?.close()
        serverSocket = null

        // Close client socket
        clientSocketRef.getAndSet(null)?.close()

        // Cancel coroutines
        tcpServerScope?.coroutineContext.cancel()
        tcpServerScope = null

        // Stop UDP beacon
        stopUdpBeacon()

        // Stop foreground
        stopForeground(true)
        stopSelf()

        Log.d("ScreenCapture", "Capture stopped")
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
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_TCP_PORT = "tcp_port"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
    }
}