package com.mykerd.synchrogaea

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.*
import android.os.*
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import android.content.pm.ServiceInfo
import android.util.Size
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import java.io.File
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues
import android.graphics.SurfaceTexture
import android.location.LocationManager
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.VideoRecordEvent
import android.os.Build
import android.view.Surface
import androidx.lifecycle.Lifecycle
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import android.security.KeyPairGeneratorSpec
import java.math.BigInteger
import javax.security.auth.x500.X500Principal
import java.util.Calendar

class SService : LifecycleService() {
    private var isRunning = false
    private var currentTarget: String = ""
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var sampleRate = 16000
    private var lastFrameTime = 0L
    private val frameInterval = 1000L / 7
    private val jpegOutputStream = ByteArrayOutputStream()
    private var targetFps = 7L
    private var targetWidth = 1280
    private var targetHeight = 720
    private var jpegQuality = 30
    private var lastDataSentTime = System.currentTimeMillis()
    private var isRecActive = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isThreadRecRunning = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var useFrontCamera = false
    private var blackBoxDurationMs: Long = 10000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    companion object {
        const val LOG_ACTION = "com.mykerd.synchrogaea.LOG_MSG"
        const val EXTRA_MSG = "message"
        const val CHANNEL_ID = "GaeaServiceChannel_V4"
    }
    private val scoReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    broadcastLog("BT: SCO Activated...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }, 2000)
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    broadcastLog("BT: Deactivated. Internal Microphone mode.")
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        broadcastLog("BT: Channel SCO Activated")
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && isRunning) {
                        audioManager.startBluetoothSco()
                    }
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(scoReceiver, filter)
            }
        } catch (e: Exception) {
            broadcastLog("SYS_ERR: Receiver registration failed")
        }
        initIncomingAudio()
    }
    class BootReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON") {
                val appContext = context.applicationContext
                val serviceIntent = Intent(appContext, SService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(serviceIntent)
                    } else {
                        appContext.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("GAEA_BOOT", "Error in boot app: ${e.message}")
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gaea::UplinkWakeLock").apply {
                acquire()
            }
            broadcastLog("SYS: WakeLock ACQUIRED")
        }
        val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
        this.blackBoxDurationMs = prefs.getLong("bb_duration", 10) * 1000L
        if (intent?.action == "ACTION_SWITCH_CAMERA") {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            prefs.edit().putBoolean("use_front_camera", lensFacing == CameraSelector.LENS_FACING_FRONT).apply()
            if (isRunning) {
                startCameraEngine()
            }
            return START_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SynchroGaea: Uplink Active")
            .setContentText("Streaming Audio/Video incoming...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(1, notification, serviceType)
            } else {
                startForeground(1, notification)
            }
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val hasFineLoc = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasFineLoc) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    10f,
                    object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                )
                broadcastLog("GPS: Active Tracking ENABLED")
            }
        } catch (e: Exception) {
            broadcastLog("LAUNCH_ERR: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return START_NOT_STICKY
            }
        }
        val useFront = prefs.getBoolean("use_front_camera", false)
        lensFacing = if (useFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        isRecActive = intent?.getBooleanExtra("IS_REC_ACTIVE", false) ?: prefs.getBoolean("is_rec_active", false)
        val intentQual = intent?.getIntExtra("CAM_QUALITY", -1) ?: -1
        if (intentQual != -1) {
            this.jpegQuality = intentQual
        } else {
            this.jpegQuality = prefs.getInt("cam_qual", 30)
        }
        val serverListRaw = intent?.getStringExtra("SERVER_LIST") ?: prefs.getString("last_ip", "192.168.1.220:9999") ?: "192.168.1.220:9999"
        if (serverListRaw != currentTarget) {
            stopEngines()
            currentTarget = serverListRaw
            isRunning = true
            if (isRecActive) { //Critical Warn (forced to start recording [possible bug])
                broadcastLog("SYS: Instant Recording Triggered")
                startCameraEngine()
                isRecModeActive()
            } //Critical Warn (forced to start recording [possible bug])
            startNetworkEngine(serverListRaw)
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRunning) {
                    startCameraEngine()
                    if (!isRecActive) {
                        startAudioCapture()
                    }
                }
            }, 500)
        }
        return START_STICKY
    }
    private fun startNetworkEngine(serverListRaw: String) {
        val threadTarget = serverListRaw
        thread(start = true, name = "GaeaNetThread") {
            val servers = serverListRaw.split(";").map { it.trim() }.filter { it.contains(":") }
            while (isRunning && currentTarget == threadTarget) {
                for (serverAddr in servers) {
                    if (!isRunning || currentTarget != threadTarget) break
                    var currentSocketInstance: Socket? = null
                    try {
                        val lastColon = serverAddr.lastIndexOf(':')
                        val ip = serverAddr.substring(0, lastColon).replace("[", "").replace("]", "")
                        val port = serverAddr.substring(lastColon + 1).toIntOrNull() ?: 9999
                        broadcastLog("NET: CONNECTING TO $ip:$port...")
                        val newSocket = createSmartSocket(ip, port)
                        currentSocketInstance = newSocket
                        newSocket.tcpNoDelay = true
                        newSocket.keepAlive = true
                        newSocket.soTimeout = 20000
                        newSocket.trafficClass = 0x10 or 0x08
                        newSocket.sendBufferSize = 256 * 1024
                        newSocket.receiveBufferSize = 256 * 1024
                        socket = newSocket
                        newSocket.setPerformancePreferences(0, 1, 2)
                        newSocket.sendBufferSize = 128 * 1024
                        val bos = java.io.BufferedOutputStream(newSocket.getOutputStream(), 16384)
                        outputStream = DataOutputStream(bos)
                        val inputStream = DataInputStream(newSocket.getInputStream())
                        broadcastLog("NET: UPLINK_ESTABLISHED!")
                        Handler(Looper.getMainLooper()).post {
                            initIncomingAudio()
                            if (!isRecActive) {
                                startAudioCapture()
                            } else {
                                broadcastLog("AUDIO: Streaming disabled (REC MODE)")
                            }
                        }
                        thread(start = true, name = "GaeaReceiveThread") {
                            try {
                                var audioBuffer = ByteArray(65536)
                                while (isRunning && !newSocket.isClosed && currentTarget == threadTarget) {
                                    val type = inputStream.read().takeIf { it != -1 } ?: break
                                    val len = inputStream.readInt()
                                    if (len in 1..1000000) {
                                        if (len > audioBuffer.size) {
                                            audioBuffer = ByteArray(len)
                                        }
                                        inputStream.readFully(audioBuffer, 0, len)
                                        when (type) {
                                            2 -> audioTrack?.let { track ->
                                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                                                track.write(audioBuffer, 0, len)
                                            }
                                            3 -> {
                                                val cmd = String(audioBuffer, 0, len)
                                                handleCommand(cmd)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (isRunning && currentTarget == threadTarget) {
                                    broadcastLog("RCV_ERR: ${e.localizedMessage}")
                                }
                            } finally {
                                try { inputStream.close() } catch (_: Exception) {}
                                try { newSocket.close() } catch (_: Exception) {}
                            }
                        }
                        while (isRunning && !newSocket.isClosed && currentTarget == threadTarget) {
                            val currentTime = System.currentTimeMillis()
                            val silentTime = currentTime - lastDataSentTime
                            if (silentTime > 20000) {
                                broadcastLog("WATCHDOG: CRITICAL_FREEZE! Hard resetting engines...")
                                try {
                                    newSocket.close()
                                    outputStream?.close()
                                } catch (_: Exception) {}
                                stopEngines()
                                isRunning = true
                                lastDataSentTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (isRunning) {
                                        broadcastLog("WATCHDOG: Re-spawning Camera & Audio...")
                                        startCameraEngine()
                                        if (!isRecActive) {
                                            startAudioCapture()
                                        }
                                    }
                                }, 1000)
                                break
                            }
                            sendData(0, "ALIVE".toByteArray())
                            refreshNotification()
                            try {
                                Thread.sleep(5000)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning && currentTarget == threadTarget) {
                            broadcastLog("NET_ERR on $serverAddr: ${e.localizedMessage}")
                            broadcastLog("RETRYING_IN_7_SECONDS...")
                            Thread.sleep(7000)
                        }
                    } finally {
                        try { outputStream?.close() } catch (_: Exception) {}
                        try { currentSocketInstance?.close() } catch (_: Exception) {}
                        socket = null
                        outputStream = null

                        if (isRunning && currentTarget == threadTarget) {
                            broadcastLog("NET: TARGET_LOST/NEXT_SERVER...")
                        }
                    }
                }
                Thread.sleep(2000)
            }
            broadcastLog("NET: THREAD TERMINATED FOR $threadTarget")
        }
    }
    @Synchronized
    private fun sendData(type: Int, data: ByteArray) {
        val currentSocket = socket
        val stream = outputStream
        if (currentSocket == null || stream == null || !currentSocket.isConnected || currentSocket.isClosed) {
            return
        }
        try {
            stream.writeByte(type)
            stream.writeInt(data.size)
            stream.write(data)
            stream.flush()
            lastDataSentTime = System.currentTimeMillis()
        } catch (e: Exception) {
            try {
                socket?.close()
            } catch (_: Exception) { }
            outputStream = null
            socket = null
            broadcastLog("NET_SEND_ERR: Connection reset")
        }
    }
    private fun startCameraEngine() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (!isRunning) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()
                val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                val targetFps = prefs.getLong("cam_fps", 7L)
                val resStr = prefs.getString("cam_res", "1280x720") ?: "1280x720"
                val resParts = resStr.split("x")
                val targetWidth = if (resParts.size == 2) resParts[0].toIntOrNull() ?: 1280 else 1280
                val targetHeight = if (resParts.size == 2) resParts[1].toIntOrNull() ?: 720 else 720
                val currentFrameInterval = 1000L / targetFps
                val analyzerLogic = ImageAnalysis.Analyzer { imageProxy ->
                    try {
                        if (isRecActive) {
                            imageProxy.close()
                            return@Analyzer
                        }
                        val currentTime = System.currentTimeMillis()
                        val currentSocketStream = outputStream
                        if (isRunning && currentSocketStream != null && (currentTime - lastFrameTime) >= currentFrameInterval) {
                            val currentPrefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                            this.jpegQuality = currentPrefs.getInt("cam_qual", 30)

                            val jpegBytes = imageToJpeg(imageProxy)
                            if (jpegBytes.isNotEmpty()) {
                                sendData(1, jpegBytes)
                                lastFrameTime = currentTime
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GAEA", "Error analyzer: ${e.message}")
                    } finally {
                        imageProxy.close()
                    }
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(targetWidth, targetHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor, analyzerLogic)
                val targetQual = prefs.getInt("cam_qual", 30)
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .setTargetVideoEncodingBitRate(targetQual * 10000)
                    .build()
                this.videoCapture = VideoCapture.withOutput(recorder)
                val preview = Preview.Builder()
                    .setTargetResolution(Size(targetWidth, targetHeight))
                    .build()
                val dummySurfaceTexture = android.graphics.SurfaceTexture(0)
                preview.setSurfaceProvider { request ->
                    request.provideSurface(android.view.Surface(dummySurfaceTexture), cameraExecutor) {}
                }
                cameraProvider.unbindAll()
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                try {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        cameraProvider.bindToLifecycle(
                            this,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                            this.videoCapture
                        )
                        broadcastLog("CAMERA: UPLINK_READY + BLACK_BOX_ARMED")
                    }
                    if (isRecActive) {
                        isRecModeActive()
                    }
                } catch (e: Exception) {
                    broadcastLog("CAM_WARN: Multi-mode failed, trying fallback 480p...")
                    cameraProvider.unbindAll()
                    val fallbackAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    fallbackAnalysis.setAnalyzer(cameraExecutor, analyzerLogic)
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        fallbackAnalysis,
                        this.videoCapture
                    )
                    broadcastLog("CAMERA: UPLINK_READY (Fallback 480p)")
                    if (isRecActive) {
                        isRecModeActive()
                    }
                }
            } catch (e: Exception) {
                broadcastLog("CAM_CRITICAL_ERR: ${e.message}")
                Log.e("GAEA", "All camera modes failed: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        if (isRunning) {
            startCameraEngine()
        }
    }
    private fun startAudioCapture() {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufSize <= 0) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
                val currentRecord = audioRecord
                audioRecord?.startRecording()
                thread(start = true, name = "GaeaAudioCapture") {
                    val readBuffer = ByteArray(minBufSize)
                    while (isRunning && audioRecord == currentRecord) {
                        val read = currentRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                        if (read > 0 && isRunning) {
                            val sendBuf = ByteArray(read)
                            System.arraycopy(readBuffer, 0, sendBuf, 0, read)
                            sendData(2, sendBuf)
                        }
                    }
                    broadcastLog("SYS: Old Audio Thread terminated")
                }
            } catch (e: Exception) {
                broadcastLog("AUDIO_CAP_ERR: ${e.message}")
            }
        }
    }
    private fun initIncomingAudio() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        //audioManager.mode = AudioManager.MODE_NORMAL
        if (audioManager.isBluetoothScoAvailableOffCall) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            broadcastLog("AUDIO_TRACK_ERR: Invalid buffer size")
            return
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        try {
            audioTrack = AudioTrack(audioAttributes, audioFormat, minBufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                broadcastLog("SYS: AUDIO_RECEIVER_READY")
            }
        } catch (e: Exception) {
            broadcastLog("AUDIO_TRACK_CRASH: ${e.message}")
        }
    }
    private fun handleCommand(cmd: String) {
        when {
            cmd.startsWith("SET_QUALITY:") -> {
                val newQual = cmd.substringAfter(":").toIntOrNull() ?: return
                jpegQuality = newQual.coerceIn(1, 100)
                val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("cam_qual", jpegQuality).apply()
                broadcastLog("CMD: JPEG_QUALITY_UPDATED -> $jpegQuality%")
            }
            cmd.startsWith("VIBRATE") -> {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
                broadcastLog("CMD: VIBRATION_TRIGGERED")
            }
            cmd.startsWith("SET_RATE:") -> {
                val newRate = cmd.substringAfter(":").toIntOrNull() ?: return
                if (this.sampleRate == newRate) return
                this.sampleRate = newRate
                broadcastLog("CMD: SYNC_AUDIO_RATE -> $newRate Hz")
                Handler(Looper.getMainLooper()).post {
                    try {
                        audioTrack?.apply {
                            if (state == AudioTrack.STATE_INITIALIZED) { stop(); release() }
                        }
                        audioTrack = null
                        audioRecord?.apply {
                            if (state == AudioRecord.STATE_INITIALIZED) { stop(); release() }
                        }
                        audioRecord = null
                        initIncomingAudio()
                        if (!isRecActive) {
                            startAudioCapture()
                        }
                        broadcastLog("SYS: AUDIO_FULL_SYNC_@_$newRate")
                    } catch (e: Exception) {
                        broadcastLog("ERR: AUDIO_SYNC_FAILED: ${e.message}")
                    }
                }
            }
        }
    }
    private fun imageToJpeg(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, row * width, width)
        }
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        var index = width * height
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vPos = row * vRowStride + col * vPixelStride
                val uPos = row * uRowStride + col * uPixelStride
                nv21[index++] = vBuffer.get(vPos)
                nv21[index++] = uBuffer.get(uPos)
            }
        }
        jpegOutputStream.reset()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, jpegOutputStream)
        image.close()
        return jpegOutputStream.toByteArray()
    }
    private fun refreshNotification() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SynchroGaea: Uplink Active")
            .setContentText("Streaming active | Tap to open")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(1, builder.build())
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "GaeaServiceChannel_V4"
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(channelId) == null) {
                val serviceChannel = NotificationChannel(
                    channelId,
                    "SynchroGaea Engine",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Uplink streaming service"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager?.createNotificationChannel(serviceChannel)
            }
        }
    }
    private fun createSmartSocket(ip: String, port: Int): Socket {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            val factory = sc.socketFactory
            val sslSocket = factory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(ip, port), 7000)
            sslSocket.startHandshake()
            broadcastLog("NET: UPLINK_ENCRYPTED (TLS)")
            return sslSocket
        } catch (e: Exception) {
            broadcastLog("NET: SSL_OFF -> FALLBACK_CLEARTEXT")
            val normalSocket = Socket()
            normalSocket.connect(InetSocketAddress(ip, port), 7000)
            return normalSocket
        }
    }
    private fun isRecModeActive() {
        if (isThreadRecRunning) return
        isThreadRecRunning = true
        broadcastLog("Blackbox called and Enabled!")
        if (!isRecActive) {
            broadcastLog("BLACK_BOX: MODE_DISABLED")
            isThreadRecRunning = false
            return
        }
        val prefs = getSharedPreferences("GaeaPrefs", android.content.Context.MODE_PRIVATE)
        val targetQual = prefs.getInt("cam_qual", 30)
        thread(start = true, name = "GaeaBlackBoxEngine") {
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true
                Thread.sleep(300)
                val storageDir = File(getExternalFilesDir(null), "uplink_rec")
                if (!storageDir.exists()) storageDir.mkdirs()
                try {
                    val noMedia = File(storageDir, ".nomedia")
                    if (!noMedia.exists()) noMedia.createNewFile()
                } catch (e: Exception) { }
                while (isRunning && isRecActive) {
                    try {
                        Thread.sleep(1000) //Delay for hardware
                        val currentPrefs = getSharedPreferences("GaeaPrefs", android.content.Context.MODE_PRIVATE)
                        val updatedDurationSec = currentPrefs.getLong("bb_duration", 10L)
                        val effectiveDurationMs = updatedDurationSec * 1000L
                        val capture = videoCapture ?: throw Exception("VideoCapture not ready")
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val videoFile = File(storageDir, "REC_$timestamp.mp4")
                        val fileOptionsBuilder = androidx.camera.video.FileOutputOptions.Builder(videoFile)
                        try {
                            val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                            val hasFineLoc = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasFineLoc) {
                                val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastLocation != null) {
                                    fileOptionsBuilder.setLocation(lastLocation)
                                    broadcastLog("GPS_VERBOSE: [Lat: ${lastLocation.latitude} | Lon: ${lastLocation.longitude}]")
                                }
                            }
                        } catch (e: Exception) {
                            //Removed log for performance <3
                        }
                        val fileOptions = fileOptionsBuilder.build()
                        val pendingRecording = capture.output.prepareRecording(this, fileOptions)
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                this, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingRecording.withAudioEnabled()
                        }
                        currentRecording = pendingRecording.start(androidx.core.content.ContextCompat.getMainExecutor(this)) { event ->
                            if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                                if (!event.hasError()) {
                                    VideoVerify(videoFile) //FIRM
                                    broadcastLog("BLACK_BOX: SAVED Qual: $targetQual] -> ${videoFile.name}")
                                    android.media.MediaScannerConnection.scanFile(this, arrayOf(videoFile.absolutePath), null, null)
                                    if (socket != null && socket!!.isConnected) {
                                        thread {
                                            try {
                                                val pendingFiles = storageDir.listFiles { f ->
                                                    f.extension == "mp4" && !f.name.contains("_send")
                                                }?.sortedBy { it.name }
                                                pendingFiles?.forEach { file ->
                                                    if (socket == null || !socket!!.isConnected) return@forEach
                                                    if (file.exists() && file.length() > 0) {
                                                        val fileBytes = file.readBytes()
                                                        sendData(4, fileBytes)
                                                        val newName = file.name.replace(".mp4", "_send.mp4")
                                                        val moved = file.renameTo(File(storageDir, newName))
                                                        if (moved) {
                                                            broadcastLog("SYNC: UPLOAD_OK (${newName})")
                                                        }
                                                        Thread.sleep(300)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                broadcastLog("SYNC_ERR: ${e.message}")
                                            }
                                        }
                                    }
                                } else {
                                    broadcastLog("BLACK_BOX_ERR: Finalize error ${event.error}")
                                }
                            }
                        }
                        broadcastLog("BLACK_BOX: RECORDING_START")
                        Thread.sleep(1120) //Delay for old devices
                        Thread.sleep(effectiveDurationMs)
                        currentRecording?.stop()
                        currentRecording = null
                        Thread.sleep(350) //GAP
                    } catch (e: Exception) {
                        broadcastLog("BLACK_BOX_ERR: ${e.message}")
                        Thread.sleep(3000)
                    }
                }
            } finally {
                currentRecording?.stop()
                isThreadRecRunning = false
                broadcastLog("BLACK_BOX: THREAD_TERMINATED")
            }
        }
    }
    private fun VideoVerify(videoFile: File): String {
        val alias = "Gaea_Forense_Key"
        val timestamp = System.currentTimeMillis()
        try {
            if (!videoFile.exists() || videoFile.length() == 0L) return "ERR_FILE_NOT_FOUND"
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(alias)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                    kpg.initialize(spec)
                    kpg.generateKeyPair()
                } else {
                    val start = Calendar.getInstance()
                    val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
                    val kpg = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                    val spec = KeyPairGeneratorSpec.Builder(this)
                        .setAlias(alias)
                        .setSubject(X500Principal("CN=$alias"))
                        .setSerialNumber(BigInteger.TEN)
                        .setStartDate(start.time)
                        .setEndDate(end.time)
                        .build()
                    kpg.initialize(spec)
                    kpg.generateKeyPair()
                }
            }
            val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            val privateKey = entry.privateKey
            val algorithm = if (privateKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val signature = Signature.getInstance(algorithm)
            signature.initSign(privateKey)
            val videoBytes = videoFile.readBytes()
            val timeBytes = ByteBuffer.allocate(8).putLong(timestamp).array()
            signature.update(videoBytes)
            signature.update(timeBytes)
            val finalSignature = signature.sign()
            val signatureHex = finalSignature.joinToString("") { "%02x".format(it) }
            java.io.FileOutputStream(videoFile, true).use { output ->
                val header = "\n--GAEA_START--\n"
                val body = "TS:$timestamp\nSIG:$signatureHex\nALG:${privateKey.algorithm}"
                val footer = "\n--GAEA_END--"
                val fullMetadata = "$header$body$footer"
                output.write(fullMetadata.toByteArray(Charsets.UTF_8))
            }
            broadcastLog("FORENSIC_SIG: [TS: $timestamp | INJECTED: OK]")
            return signatureHex
        } catch (e: Exception) {
            broadcastLog("VIDEO_VERIFY_FAIL: ${e.message}")
            return "SIGNATURE_ERROR"
        }
    }
    /*
     //Old version pre-audio stability modify!
    private fun isRecModeActive() {
        if (isThreadRecRunning) return
        isThreadRecRunning = true
        broadcastLog("Blackbox called and Enabled!")
        if (!isRecActive) {
            broadcastLog("BLACK_BOX: MODE_DISABLED")
            isThreadRecRunning = false
            return
        }
        val prefs = getSharedPreferences("GaeaPrefs", android.content.Context.MODE_PRIVATE)
        //val targetRes = prefs.getString("cam_res", "1280x720")
        val targetQual = prefs.getInt("cam_qual", 30)
        thread(start = true, name = "GaeaBlackBoxEngine") {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                Thread.sleep(150)
                val storageDir = File(getExternalFilesDir(null), "uplink_rec")
                if (!storageDir.exists()) storageDir.mkdirs()
                try {
                    val noMedia = File(storageDir, ".nomedia")
                    if (!noMedia.exists()) noMedia.createNewFile()
                } catch (e: Exception) { }
                while (isRunning && isRecActive) {
                    try {
                        val currentPrefs = getSharedPreferences("GaeaPrefs", android.content.Context.MODE_PRIVATE)
                        val updatedDurationSec = currentPrefs.getLong("bb_duration", 10L)
                        val effectiveDurationMs = updatedDurationSec * 1000L
                        val capture = videoCapture ?: throw Exception("VideoCapture not ready")
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val videoFile = File(storageDir, "REC_$timestamp.mp4")
                        val fileOptionsBuilder = androidx.camera.video.FileOutputOptions.Builder(videoFile)
                        try {
                            val locationManager = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                            val hasFineLoc = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasFineLoc) {
                                val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                                val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

                                val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                if (lastLocation != null) {
                                    fileOptionsBuilder.setLocation(lastLocation)
                                    broadcastLog("GPS_VERBOSE: [Lat: ${lastLocation.latitude} | Lon: ${lastLocation.longitude}] | Alt: ${lastLocation.altitude}m")
                                } else {
                                    broadcastLog("GPS_VERBOSE: Enabled (GPS:$isGpsEnabled, Net:$isNetworkEnabled) but NO_FIX (null location)")
                                }
                            } else {
                                broadcastLog("GPS_VERBOSE: PERMISSION_DENIED (No Fine Location)")
                            }
                        } catch (e: Exception) {
                            broadcastLog("GPS_VERBOSE: ERROR (${e.message})")
                        }
                        val fileOptions = fileOptionsBuilder.build()
                        val pendingRecording = capture.output.prepareRecording(this, fileOptions)
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                this, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingRecording.withAudioEnabled()
                        }
                        currentRecording = pendingRecording.start(androidx.core.content.ContextCompat.getMainExecutor(this)) { event ->
                            if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                                if (!event.hasError()) {
                                    broadcastLog("BLACK_BOX: SAVED Qual: $targetQual] -> ${videoFile.name}")
                                  //  /* [Res: $targetRes | */
                                    android.media.MediaScannerConnection.scanFile(
                                        this,
                                        arrayOf(videoFile.absolutePath),
                                        null,
                                        null
                                    )
                                    if (socket != null && socket!!.isConnected) {
                                        thread {
                                            try {
                                                val pendingFiles = storageDir.listFiles { f ->
                                                    f.extension == "mp4" && !f.name.contains("_send")
                                                }?.sortedBy { it.name }
                                                pendingFiles?.forEach { file ->
                                                    if (socket == null || !socket!!.isConnected) return@forEach
                                                    if (file.exists() && file.length() > 0) {
                                                        val fileBytes = file.readBytes()
                                                        sendData(4, fileBytes)
                                                        val newName = file.name.replace(".mp4", "_send.mp4")
                                                        val moved = file.renameTo(File(storageDir, newName))
                                                        if (moved) {
                                                            broadcastLog("SYNC: UPLOAD_OK (${newName})")
                                                        }
                                                        Thread.sleep(300)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                broadcastLog("SYNC_ERR: ${e.message}")
                                            }
                                        }
                                    }
                                } else {
                                    broadcastLog("BLACK_BOX_ERR: Finalize error ${event.error}")
                                }
                            }
                        }
                        broadcastLog("BLACK_BOX: RECORDING_START") //($targetRes)
                        Thread.sleep(effectiveDurationMs)
                        currentRecording?.stop()
                        currentRecording = null
                        Thread.sleep(350) //default: 50ms or 150/250/300ms for remove GAP
                    } catch (e: Exception) {
                        broadcastLog("BLACK_BOX_ERR: ${e.message}")
                        Thread.sleep(3000)
                    }
                }
            } finally {
                currentRecording?.stop()
                isThreadRecRunning = false
                broadcastLog("BLACK_BOX: THREAD_TERMINATED")
            }
        }
    }
    */
    private fun broadcastLog(msg: String) {
        val intent = Intent(LOG_ACTION).apply {
            putExtra(EXTRA_MSG, msg); setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    private fun stopEngines() {
        isRunning = false
        broadcastLog("NET: STOPPING ENGINES...")
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (e: Exception) { }
        try {
            socket?.close()
        } catch (e: Exception) { }
        outputStream = null
        socket = null
        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("GAEA", "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("GAEA", "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            audioManager.setSpeakerphoneOn(false)
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e("GAEA", "Error resetting AudioManager: ${e.message}")
        }
    }
    override fun onDestroy() {
        isRunning = false
        try {
            locationManager?.let {
                it.removeUpdates { }
                broadcastLog("GPS: Tracking STOPPED")
            }
        } catch (e: Exception) {
        }
        try {
            unregisterReceiver(scoReceiver)
        } catch (e: Exception) { }
        stopEngines()
        try {
            cameraExecutor.shutdownNow()
        } catch (e: Exception) { }
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}