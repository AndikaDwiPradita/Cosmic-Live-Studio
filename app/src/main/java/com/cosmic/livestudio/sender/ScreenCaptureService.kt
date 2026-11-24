package com.cosmic.livestudio.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.projection.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.launch
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

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION)
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
        val newEncoder = H264ScreenEncoder(streamWidth, streamHeight, streamFps, streamBitrate) { data, _ ->
            if (isRunning.get()) frameChannel.trySend(data to true)
        }
        encoder = newEncoder
        val surface = newEncoder.start()
        if (surface == null) {
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
        startTcpServer()
        startUdpBeacon()
        showNotification()
    }

    private fun restartEncoder() {
        if (!isRunning.get()) return
        encoder?.stop()
        val newEncoder = H264ScreenEncoder(streamWidth, streamHeight, streamFps, streamBitrate) { data, _ ->
            if (isRunning.get()) frameChannel.trySend(data to true)
        }
        encoder = newEncoder
        val surface = newEncoder.start()
        surface?.let { virtualDisplay?.setSurface(it) } ?: run { stopCapture(); return }
    }

    private fun startTcpServer() {
        tcpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = ServerSocket(currentTcpPort).apply {
                    reuseAddress = true
                    soTimeout = 1000
                }
                serverSocket = socket
                while (isRunning.get()) {
                    try {
                        val client = socket.accept().apply { tcpNoDelay = true }
                        handleClient(client)
                    } catch (_: java.net.SocketTimeoutException) {
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
        clientSocketRef.getAndSet(socket)?.close()
        sendPacket(socket, StreamProtocol.createConfigPacket(streamWidth, streamHeight, streamFps, streamBitrate))
        try {
            val output = socket.getOutputStream()
            while (isRunning.get() && !socket.isClosed && clientSocketRef.get() == socket) {
                val (data, _) = frameChannel.receive()
                output.write(StreamProtocol.createPacketFromNal(data))
                output.flush()
            }
        } catch (e: Exception) {
            Log.w("ScreenCapture", "Client handling error", e)
        } finally {
            if (clientSocketRef.compareAndSet(socket, null)) socket.close()
        }
    }

    private fun sendPacket(socket: Socket, packet: ByteArray) {
        socket.getOutputStream().write(packet)
        socket.getOutputStream().flush()
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
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_sender), NotificationManager.IMPORTANCE_LOW)
            channel.description = "Screen sharing broadcast service"
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
