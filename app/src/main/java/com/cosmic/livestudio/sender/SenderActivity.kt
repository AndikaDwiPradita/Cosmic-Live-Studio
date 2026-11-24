package com.cosmic.livestudio.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cosmic.livestudio.R
import com.cosmic.livestudio.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SenderActivity : AppCompatActivity() {
    private val MEDIA_PROJECTION_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002

    private lateinit var tvPairingCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleSharing: Button
    private lateinit var btnCopyCode: Button
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var spinnerBitrate: Spinner

    private var pairingCode = ""
    private var isSharing = false
    private var mediaProjectionManager: MediaProjectionManager? = null

    // Stream settings
    private var currentWidth = 1280
    private var currentHeight = 720
    private var currentFps = 30
    private var currentBitrate = 3_500_000

    private val resolutions = listOf(
        "480p (854x480)" to Pair(854, 480),
        "720p (1280x720)" to Pair(1280, 720),
        "1080p (1920x1080)" to Pair(1920, 1080)
    )
    private val fpsOptions = listOf("30 FPS" to 30, "60 FPS" to 60)
    private val bitrateOptions = listOf(
        "2 Mbps" to 2_000_000,
        "3.5 Mbps" to 3_500_000,
        "5 Mbps" to 5_000_000,
        "8 Mbps" to 8_000_000
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        setupToolbar()
        initViews()
        setupSpinners()
        generatePairingCode()
        checkPermissionsAndStart()

        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        tvPairingCode = findViewById(R.id.tvPairingCode)
        tvStatus = findViewById(R.id.tvSenderStatus)
        btnToggleSharing = findViewById(R.id.btnToggleSharing)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerFps = findViewById(R.id.spinnerFps)
        spinnerBitrate = findViewById(R.id.spinnerBitrate)

        btnToggleSharing.setOnClickListener { toggleSharing() }
        btnCopyCode.setOnClickListener { copyPairingCode() }
    }

    private fun setupSpinners() {
        // Resolution spinner
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions.map { it.first }.toTypedArray())
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(1) // 720p default
        spinnerResolution.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val (w, h) = resolutions[position].second
                currentWidth = w
                currentHeight = h
                if (isSharing) updateStreamSettings()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // FPS spinner
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions.map { it.first }.toTypedArray())
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFps.adapter = fpsAdapter
        spinnerFps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentFps = fpsOptions[position].second
                if (isSharing) updateStreamSettings()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Bitrate spinner
        val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateOptions.map { it.first }.toTypedArray())
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBitrate.adapter = bitrateAdapter
        spinnerBitrate.setSelection(1) // 3.5 Mbps default
        spinnerBitrate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentBitrate = bitrateOptions[position].second
                if (isSharing) updateStreamSettings()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun generatePairingCode() {
        pairingCode = (100000..999999).random().toString()
        tvPairingCode.text = pairingCode.chunked(3).joinToString(" ")
    }

    private fun copyPairingCode() {
        android.content.ClipboardManager clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Pairing Code", pairingCode)
        clipboard.primaryClip = clip
        Toast.makeText(this, "Kode disalin ke clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScreenCapture()
            } else {
                Toast.makeText(this, "Izin notifikasi diperlukan untuk layanan foreground", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScreenCapture() {
        mediaProjectionManager?.let { mgr ->
            startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCaptureService(data)
            } else {
                Toast.makeText(this, "Izin rekam layar ditolak", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCaptureService(projectionData: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION, projectionData)
            putExtra(ScreenCaptureService.EXTRA_PAIRING_CODE, pairingCode)
            putExtra(ScreenCaptureService.EXTRA_TCP_PORT, 45455)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, currentWidth)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, currentHeight)
            putExtra(ScreenCaptureService.EXTRA_FPS, currentFps)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, currentBitrate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isSharing = true
        updateUI()
    }

    private fun toggleSharing() {
        if (isSharing) {
            stopCaptureService()
        } else {
            startScreenCapture()
        }
    }

    private fun stopCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        stopService(intent)
        isSharing = false
        updateUI()
    }

    private fun updateStreamSettings() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
            putExtra(ScreenCaptureService.EXTRA_WIDTH, currentWidth)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, currentHeight)
            putExtra(ScreenCaptureService.EXTRA_FPS, currentFps)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, currentBitrate)
        }
        startService(intent)
    }

    private fun updateUI() {
        if (isSharing) {
            btnToggleSharing.text = getString(R.string.btn_stop_sharing)
            btnToggleSharing.setBackgroundColor(getColor(R.color.error))
            tvStatus.text = getString(R.string.status_waiting)
            tvStatus.setTextColor(getColor(R.color.status_connecting))
        } else {
            btnToggleSharing.text = getString(R.string.btn_start_sharing)
            btnToggleSharing.setBackgroundColor(getColor(R.color.btn_primary_bg))
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(getColor(R.color.status_disconnected))
        }
    }

    override fun onDestroy() {
        if (isSharing) {
            stopCaptureService()
        }
        super.onDestroy()
    }
}