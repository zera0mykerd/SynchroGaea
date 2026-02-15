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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

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
        super.onStartCommand(intent, flags, startId)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SynchroGaea: Uplink Active")
            .setContentText("Streaming Audio/Video in corso...")
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
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(1, notification, serviceType)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            broadcastLog("LAUNCH_ERR: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return START_NOT_STICKY
            }
        }
        val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
        val savedAddr = prefs.getString("last_ip", "192.168.1.220:9999") ?: "192.168.1.220:9999"
        val parts = savedAddr.split(":")
        val defaultIp = if (parts.size >= 1) parts[0] else "192.168.1.220"
        val defaultPort = if (parts.size >= 2) parts[1].toIntOrNull() ?: 9999 else 9999
        val ip = intent?.getStringExtra("SERVER_IP") ?: defaultIp
        val port = intent?.getStringExtra("SERVER_PORT")?.toIntOrNull() ?: defaultPort
        val newTarget = "$ip:$port"
        val intentQual = intent?.getIntExtra("CAM_QUALITY", -1) ?: -1
        if (intentQual != -1) {
            this.jpegQuality = intentQual
        } else {
            this.jpegQuality = prefs.getInt("cam_qual", 30)
        }
        if (newTarget != currentTarget) {
            stopEngines()
            currentTarget = newTarget
            isRunning = true
            startNetworkEngine(ip, port)
            Handler(Looper.getMainLooper()).postDelayed({
                if (isRunning) {
                    startCameraEngine()
                    startAudioCapture()
                }
            }, 500)
        }
        return START_STICKY
    }
    private fun startNetworkEngine(ip: String, port: Int) {
        val threadTarget = "$ip:$port"
        thread(start = true, name = "GaeaNetThread") {
            while (isRunning && currentTarget == threadTarget) {
                Thread.sleep(7000)
                var currentSocketInstance: Socket? = null
                try {
                    broadcastLog("NET: CONNECTING TO $ip:$port...")
                    val newSocket = createSmartSocket(ip, port)
                    currentSocketInstance = newSocket
                    newSocket.tcpNoDelay = true
                    newSocket.keepAlive = true
                    newSocket.soTimeout = 20000
                    socket = newSocket
                    outputStream = DataOutputStream(newSocket.getOutputStream())
                    val inputStream = DataInputStream(newSocket.getInputStream())
                    broadcastLog("NET: UPLINK_ESTABLISHED!")
                    Handler(Looper.getMainLooper()).post {
                        initIncomingAudio()
                        startAudioCapture()
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
                        val silentTime = System.currentTimeMillis() - lastDataSentTime
                        if (silentTime > 40000) {
                            broadcastLog("WATCHDOG: Connection frozen. Resetting...")
                            try { newSocket.close() } catch (_: Exception) {}
                            break
                        }
                        sendData(0, "ALIVE".toByteArray())
                        refreshNotification()
                        Thread.sleep(5000)
                    }
                } catch (e: Exception) {
                    if (isRunning && currentTarget == threadTarget) {
                        broadcastLog("NET_ERR: ${e.localizedMessage}")
                        Thread.sleep(8000)
                    }
                } finally {
                    try { outputStream?.close() } catch (_: Exception) {}
                    try { currentSocketInstance?.close() } catch (_: Exception) {}
                    socket = null
                    outputStream = null
                    if (isRunning && currentTarget == threadTarget) {
                        broadcastLog("NET: DISCONNECTED - RETRYING...")
                    }
                }
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
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(targetWidth, targetHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        val currentTime = System.currentTimeMillis()
                        val currentSocket = outputStream
                        if (isRunning && currentSocket != null && (currentTime - lastFrameTime) >= currentFrameInterval) {
                            val prefs = getSharedPreferences("GaeaPrefs", Context.MODE_PRIVATE)
                            this.jpegQuality = prefs.getInt("cam_qual", 30)
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
                cameraProvider.unbindAll()
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
                    broadcastLog("CAMERA: STREAMING_READY")
                }
            } catch (e: Exception) {
                broadcastLog("CAM_BIND_ERR: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun startAudioCapture() {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufSize <= 0) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize)
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
                        startAudioCapture()
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
            unregisterReceiver(scoReceiver)
        } catch (e: Exception) { }
        stopEngines()
        try {
            cameraExecutor.shutdownNow()
        } catch (e: Exception) { }
        super.onDestroy()
    }
}