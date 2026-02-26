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
    private var mediaCodec: MediaCodec? = null
    private var encoderThread: Thread? = null
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
    private val sendLock = Any()
    private var cameraControl: CameraControl? = null
    private var torchOn = false
    private var isSyncInProgress = false
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
            while (currentTarget == threadTarget) {
                for (serverAddr in servers) {
                    if (currentTarget != threadTarget) break
                    isRunning = true
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
                        newSocket.trafficClass = 0xB8
                        newSocket.sendBufferSize = 32 * 1024 /* Default 128 */
                        newSocket.receiveBufferSize = 64 * 1024 /* Default 256 */
                        socket = newSocket
                        newSocket.setPerformancePreferences(0, 2, 1)
                        outputStream = DataOutputStream(newSocket.getOutputStream())
                        val inputStream = DataInputStream(newSocket.getInputStream())
                        val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                        val specificAuth = prefs.getString("auth_$serverAddr", "admin:password") ?: "admin:password"
                        sendData(0, specificAuth.toByteArray(Charsets.UTF_8))
                        val response = inputStream.read()
                        if (response == 255) {
                            broadcastLog("AUTH_ERR: Incorrect password for $serverAddr")
                            prefs.edit().remove("auth_$serverAddr").apply()
                            val intent = Intent("GAEA_AUTH_RETRY")
                            intent.putExtra("SERVER_IP", serverAddr)
                            sendBroadcast(intent)
                            throw Exception("AUTH_FAILED")
                        }
                        broadcastLog("NET: UPLINK_ESTABLISHED!")
                        Handler(Looper.getMainLooper()).post {
                            initIncomingAudio()
                            isRunning = true
                            startCameraEngine()
                            if (!isRecActive) {
                                startAudioCapture()
                            } else {
                                broadcastLog("AUDIO: Streaming disabled (REC MODE)")
                            }
                        }
                        thread(start = true, name = "GaeaReceiveThread") {
                            try {
                                var audioBuffer = ByteArray(65536)
                                while (!newSocket.isClosed && currentTarget == threadTarget) {
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
                                broadcastLog("RCV_ERR: ${e.localizedMessage}")
                            } finally {
                                try { inputStream.close() } catch (_: Exception) {}
                                try { newSocket.close() } catch (_: Exception) {}
                            }
                        }
                        while (!newSocket.isClosed && currentTarget == threadTarget) {
                            val currentTime = System.currentTimeMillis()
                            val silentTime = currentTime - lastDataSentTime
                            if (silentTime > 20000) {
                                broadcastLog("WATCHDOG: RESETTING...")
                                try {
                                    newSocket.close()
                                    outputStream?.close()
                                } catch (_: Exception) {}
                                isRunning = true
                                lastDataSentTime = System.currentTimeMillis()
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (currentTarget == threadTarget) {
                                        broadcastLog("WATCHDOG: Recovering Engines...")
                                        startCameraEngine()
                                        if (!isRecActive) startAudioCapture()
                                    }
                                }, 1000)
                                break
                            }
                            sendData(0, "ALIVE".toByteArray())
                            try {
                                val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                                val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toInt()).toInt() else 0
                                sendData(130, batteryPct.toString().toByteArray())
                            } catch (_: Exception) {}
                            refreshNotification()
                            try {
                                Thread.sleep(5000)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (currentTarget == threadTarget) {
                            broadcastLog("NET_ERR on $serverAddr: ${e.localizedMessage}")
                            broadcastLog("RETRYING_IN_7_SECONDS...")
                            Thread.sleep(7000)
                        }
                    } finally {
                        try { outputStream?.close() } catch (_: Exception) {}
                        try { currentSocketInstance?.close() } catch (_: Exception) {}
                        socket = null
                        outputStream = null
                        if (currentTarget == threadTarget) {
                            broadcastLog("NET: DISCONNECTED. Waiting 7s...")
                            Thread.sleep(7000)
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
        val stream = outputStream ?: return
        val s = socket
        if (s == null || !s.isConnected || s.isClosed) return
        thread {
            try {
                synchronized(sendLock) {
                    stream.writeByte(type)
                    stream.writeInt(data.size)
                    stream.write(data)
                    stream.flush()
                }
                lastDataSentTime = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e("GAEA", "Send failed, closing socket: ${e.message}")
                synchronized(sendLock) {
                    try {
                        socket?.close()
                    } catch (_: Exception) { }
                    socket = null
                    outputStream = null
                }
                broadcastLog("RCV_ERR: Socket Reset")
                if (isRunning) {
                    Handler(Looper.getMainLooper()).post { stopEngines() }
                }
            }
        }
    }
    private fun calculateOptimalBitrate(width: Int, height: Int, userQual: Int): Int {
        val numPixels = width * height
        val qualityMultiplier = userQual.coerceIn(1, 100) / 50.0
        val bpp = when {
            height >= 2160 -> 0.19 // 4K
            height >= 1440 -> 0.17 // 2K
            height >= 1080 -> 0.15 // 1080p
            height >= 720  -> 0.13 // 720p
            height >= 480  -> 0.12 // 480p
            height >= 360  -> 0.15 // 360p
            height >= 240  -> 0.18 // 240p
            else           -> 0.25 // 144p
        }
        val baseBitrate = (numPixels * bpp * 30).toInt()
        val finalBitrate = (baseBitrate * qualityMultiplier).toInt()
        return finalBitrate.coerceIn(300_000, 50_000_000)
    }
    private fun startCameraEngine() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (!isRunning) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()
                val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                val resStr = prefs.getString("cam_res", "1280x720") ?: "1280x720"
                val resParts = resStr.split("x")
                val rawWidth = resParts[0].toIntOrNull() ?: 1280
                val rawHeight = resParts[1].toIntOrNull() ?: 720
                val targetWidth = (rawWidth / 16) * 16
                val targetHeight = (rawHeight / 16) * 16
                val userQuality = prefs.getInt("cam_qual", 30)
                val targetFps = prefs.getLong("cam_fps", 7L)
                val currentFrameInterval = 1000L / targetFps
                val targetBitrate = calculateOptimalBitrate(targetWidth, targetHeight, userQuality)
                mediaCodec?.stop()
                mediaCodec?.release()
                val format = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 2130708361)
                format.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                format.setInteger("prepend-sps-pps-to-idr-frames", 1)
                format.setInteger("bitrate-mode", if (targetHeight < 720) 1 else 2)
                format.setInteger("color-standard", 1)
                format.setInteger("color-transfer", 3)
                format.setInteger("color-range", 2)
                when {
                    targetHeight >= 1080 -> {
                        format.setInteger("profile", 8)
                        format.setInteger("level", if (targetHeight >= 2160) 32768 else 1024)
                    }
                    targetHeight >= 720 -> {
                        format.setInteger("profile", 2)
                        format.setInteger("level", 512)
                    }
                    else -> {
                        format.setInteger("profile", 1)
                        format.setInteger("level", 128)
                    }
                }
                val codec = MediaCodec.createEncoderByType("video/avc")
                codec.setCallback(object : MediaCodec.Callback() {
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && isRunning && !isRecActive) {
                            val outData = ByteArray(info.size)
                            buffer.get(outData)
                            sendData(1, outData)
                        }
                        c.releaseOutputBuffer(index, false)
                    }
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {}
                    override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {}
                })
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val codecInputSurface = codec.createInputSurface()
                codec.start()
                this.mediaCodec = codec
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(targetWidth, targetHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val currentTime = System.currentTimeMillis()
                        if (isRunning && (currentTime - lastFrameTime) >= currentFrameInterval) {
                            this.jpegQuality = userQuality
                            val jpegBytes = imageToJpeg(imageProxy)
                            if (jpegBytes.isNotEmpty()) {
                                lastFrameTime = currentTime
                            }
                        }
                    } finally { imageProxy.close() }
                }
                val recQual = when {
                    targetHeight >= 2160 -> Quality.UHD
                    targetHeight >= 1080 -> Quality.FHD
                    targetHeight >= 720  -> Quality.HD
                    else -> Quality.SD
                }
                this.videoCapture = VideoCapture.withOutput(Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(recQual))
                    .setTargetVideoEncodingBitRate(targetBitrate)
                    .build())
                if (isRecActive) {
                    isRecModeActive()
                }
                val preview = Preview.Builder().setTargetResolution(Size(targetWidth, targetHeight)).build()
                preview.setSurfaceProvider(cameraExecutor) { request ->
                    request.provideSurface(codecInputSurface, cameraExecutor) {}
                }
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                cameraProvider.unbindAll()

                try {
                    val camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis, this.videoCapture
                    )
                    this.cameraControl = camera.cameraControl
                    broadcastLog("SYSTEM: FULL_MODE_ACTIVE (${targetWidth}x${targetHeight})")
                } catch (e: Exception) {
                    broadcastLog("HW_LIMIT: Entering Safe Mode...")
                    cameraProvider.unbindAll()
                    if (isRecActive) {
                        mediaCodec?.stop(); mediaCodec?.release(); mediaCodec = null
                        val simplePreview = Preview.Builder().build()
                        cameraProvider.bindToLifecycle(this, cameraSelector, simplePreview, this.videoCapture)
                        broadcastLog("SYSTEM: REC_PRIORITY_SAFE_MODE")
                    } else {
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                        broadcastLog("SYSTEM: STREAM_PRIORITY_SAFE_MODE")
                    }
                }
            } catch (e: Exception) {
                broadcastLog("FATAL_ERR: ${e.message}")
                Log.e("GAEA", "Engine Crash", e)
            }
        }, ContextCompat.getMainExecutor(this))
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
                val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("cam_qual", newQual).apply()
                this.jpegQuality = newQual
                val resStr = prefs.getString("cam_res", "1280x720") ?: "1280x720"
                val resParts = resStr.split("x")
                val w = resParts[0].toInt()
                val h = resParts[1].toInt()
                val newBitrate = calculateOptimalBitrate(w, h, newQual)
                try {
                    mediaCodec?.let { codec ->
                        val params = Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                        codec.setParameters(params)
                        broadcastLog("CMD: BITRATE_LIVE_UPDATED -> $newBitrate bps ($newQual%)")
                    }
                } catch (e: Exception) {
                    broadcastLog("ERR: LIVE_BITRATE_FAILED: ${e.message}")
                }
            }
            cmd.startsWith("SWITCH_CAM:") -> {
                val lensStr = cmd.substringAfter(":")
                val newLens = lensStr.toIntOrNull() ?: 1
                lensFacing = if (newLens == 1) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                broadcastLog("CMD: SWITCH_CAMERA -> ${if (newLens == 1) "BACK" else "FRONT"}")
                Handler(Looper.getMainLooper()).post {
                    try {
                        mediaCodec?.stop()
                        mediaCodec?.release()
                        mediaCodec = null
                        val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                        cameraProvider.unbindAll()
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isRunning) startCameraEngine()
                        }, 600)
                    } catch (e: Exception) {
                        broadcastLog("ERR: SWITCH_FAILED: ${e.message}")
                    }
                }
            }
            cmd.startsWith("SET_RES:") -> {
                val newRes = cmd.substringAfter(":")
                if (newRes.contains("x")) {
                    val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("cam_res", newRes).apply()
                    broadcastLog("CMD: RESOLUTION_CHANGE -> $newRes")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            mediaCodec?.stop()
                            mediaCodec?.release()
                            mediaCodec = null
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProvider.unbindAll()
                            broadcastLog("SYS: RECONFIGURING ENGINES...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isRunning) {
                                    startCameraEngine()
                                    broadcastLog("SYS: RESOLUTION APPLIED OK")
                                }
                            }, 600)
                        } catch (e: Exception) {
                            broadcastLog("ERR: SOFT_RESTART_FAILED: ${e.message}")
                            stopEngines()
                            startCameraEngine()
                        }
                    }
                }
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
            cmd.startsWith("TORCH") -> {
                try {
                    torchOn = !torchOn
                    cameraControl?.enableTorch(torchOn)
                    broadcastLog("CMD: TORCH -> ${if (torchOn) "ON" else "OFF"}")
                    Log.d("GAEA", "Torch Toggled: $torchOn")
                } catch (e: Exception) {
                    broadcastLog("ERR: TORCH_FAILED: ${e.message}")
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
                                    if (socket != null && socket!!.isConnected && !isSyncInProgress) {
                                        thread {
                                            isSyncInProgress = true
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
                                            } finally {
                                                isSyncInProgress = false
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
            mediaCodec?.let {
                it.stop()
                it.release()
                broadcastLog("CODEC: H.264 ENCODER RELEASED")
            }
        } catch (e: Exception) {
            Log.e("GAEA", "Error releasing MediaCodec: ${e.message}")
        }
        mediaCodec = null
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