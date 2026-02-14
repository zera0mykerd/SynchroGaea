package com.mykerd.synchrogaea

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings

class MainActivity : Activity() {
    private var tvLog: TextView? = null
    private var logScroll: ScrollView? = null
    private val PREFS_NAME = "GaeaPrefs"
    private val KEY_IP = "last_ip"
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(SService.EXTRA_MSG)?.let { logTerminal(it) }
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val etAddress = findViewById<EditText>(R.id.etAddress)
        val etFps = findViewById<EditText>(R.id.etFps)
        val etRes = findViewById<EditText>(R.id.etRes)
        val filter = IntentFilter(SService.LOG_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSavedIp = prefs.getString(KEY_IP, "192.168.1.220:9999")
        etFps?.setText(prefs.getLong("cam_fps", 7L).toString())
        etRes?.setText(prefs.getString("cam_res", "1280x720"))
        etAddress?.setText(lastSavedIp)
        if (lastSavedIp != null && lastSavedIp.contains(":")) {
            initiateConnection(lastSavedIp)
        }
        logTerminal("SYSTEM_READY")
        checkPermissions()
        requestBatteryUnrestricted()
        btnConnect?.setOnClickListener {
            val newAddr = etAddress?.text?.toString() ?: ""
            val newFps = etFps?.text?.toString()?.toLongOrNull() ?: 7L
            val newRes = etRes?.text?.toString() ?: "1280x720"
            if (newAddr.contains(":") && !newAddr.endsWith(":")) {
                prefs.edit().apply {
                    putString(KEY_IP, newAddr)
                    putLong("cam_fps", newFps)
                    putString("cam_res", newRes)
                    apply()
                }
                logTerminal("CONNECTING_TO: $newAddr")
                initiateConnection(newAddr)
            } else {
                logTerminal("SYNTAX_ERROR: USE IP:PORT")
            }
        }
    }
    private fun initiateConnection(fullAddr: String) {
        try {
            val lastColon = fullAddr.lastIndexOf(':')
            if (lastColon == -1) {
                logTerminal("SYNTAX_ERROR: MISSING PORT")
                return
            }
            val ip = fullAddr.substring(0, lastColon).replace("[", "").replace("]", "")
            val port = fullAddr.substring(lastColon + 1)
            val intent = Intent(this, SService::class.java).apply {
                putExtra("SERVER_IP", ip)
                putExtra("SERVER_PORT", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            logTerminal("LINKING_TARGET: $ip")
        } catch (e: Exception) {
            logTerminal("CRITICAL_LAUNCH_ERR: ${e.message}")
        }
    }
    private fun logTerminal(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            tvLog?.let {
                it.append("\n[$timeStamp] > $message")
            }
            logScroll?.post { logScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perms = mutableListOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                logTerminal("REQUESTING_PERMISSIONS...")
                requestPermissions(missing.toTypedArray(), 101)
            }
        }
    }
    private fun requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                logTerminal("BATTERY: REQUEST_UNRESTRICTED_ACCESS")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                logTerminal("BATTERY: ALREADY_UNRESTRICTED")
            }
        }
    }
    override fun onDestroy() {
        try { unregisterReceiver(logReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}

/* * *****************************************************************************
 * 🛡️ EASTER EGG: THE "MINER'S ENCRYPTION" GUIDE (SSL/TLS IMPLEMENTATION) 🛡️
 * *****************************************************************************
 * Current System: Plaintext TCP (Lightweight, Fast, 4G Optimized).
 * Upgrade Path: If you need Bank-Level security for industrial secrets,
 * follow this technical blueprint to wrap your sockets in SSL/TLS.
 *
 * STEP 1: SERVER-SIDE (Python - GaeaServer)
 * -----------------------------------------
 * 1. Generate keys:
 * openssl req -newkey rsa:2048 -nodes -keyout server.key -x509 -days 365 -out server.crt
 * 2. In 'start_socket', wrap the accepted connection:
 * import ssl
 * context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
 * context.load_cert_chain(certfile="server.crt", keyfile="server.key")
 * # After self.conn, addr = s.accept():
 * self.conn = context.wrap_socket(self.conn, server_side=True)
 *
 * STEP 2: CLIENT-SIDE (Android - SService.kt)
 * -------------------------------------------
 * 1. In 'startNetworkEngine', replace 'Socket()' with 'SSLSocket':
 * val factory = SSLSocketFactory.getDefault()
 * val sslSocket = factory.createSocket() as SSLSocket
 * sslSocket.connect(InetSocketAddress(ip, port), 10000)
 * sslSocket.startHandshake()
 * socket = sslSocket
 *
 * NOTE: For Self-Signed certs, you must implement a 'TrustManager' that
 * accepts your specific 'server.crt' to avoid 'HandshakeException'.
 *
 * "Trust the code, but encrypt the path. Stay safe underground."
 * *****************************************************************************
 */