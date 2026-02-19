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
import android.view.KeyEvent
import android.widget.ImageButton
import android.widget.Spinner
import android.app.KeyguardManager
import android.view.WindowManager
import android.content.Context

class MainActivity : Activity() {
    private var tvLog: TextView? = null
    private var logScroll: ScrollView? = null
    private val PREFS_NAME = "GaeaPrefs"
    private val KEY_IP = "last_ip"
    private var isRecActive = false
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
        val etRes = findViewById<Spinner>(R.id.etRes)
        val etDuration = findViewById<EditText>(R.id.etDuration)
        val adapter = android.widget.ArrayAdapter.createFromResource(
            this,
            R.array.resolutions_array,
            R.layout.spinner_item
        )
        etRes.adapter = adapter
        adapter.setDropDownViewResource(R.layout.spinner_item)
        val filter = IntentFilter(SService.LOG_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSavedIp = prefs.getString(KEY_IP, "192.168.1.220:9999")
        etFps?.setText(prefs.getLong("cam_fps", 7L).toString())
        etAddress?.setText(lastSavedIp)
        etDuration?.setText(prefs.getLong("bb_duration", 10L).toString())
        val savedRes = prefs.getString("cam_res", "1280x720")
        val resArray = resources.getStringArray(R.array.resolutions_array)
        val position = resArray.indexOfFirst { it.startsWith(savedRes ?: "1280x720") }
        if (position >= 0) etRes.setSelection(position)
        val etQuality = findViewById<EditText>(R.id.etQuality)
        val rgRecord = findViewById<android.widget.RadioGroup>(R.id.rgRecord)
        val rbYes = findViewById<android.widget.RadioButton>(R.id.rbYes)
        val rbNo = findViewById<android.widget.RadioButton>(R.id.rbNo)
        val btnSwitch = findViewById<ImageButton>(R.id.btnSwitchCam)
        var isFrontActive = prefs.getBoolean("use_front_camera", false)
        if (isFrontActive) {
            btnSwitch.setBackgroundColor(android.graphics.Color.parseColor("#00FF41"))
            btnSwitch.setColorFilter(android.graphics.Color.BLACK)
        }
        btnSwitch.setOnClickListener {
            isFrontActive = !isFrontActive
            prefs.edit().putBoolean("use_front_camera", isFrontActive).apply()
            if (isFrontActive) {
                btnSwitch.setBackgroundColor(android.graphics.Color.parseColor("#00FF41"))
                btnSwitch.setColorFilter(android.graphics.Color.BLACK)
            } else {
                btnSwitch.setBackgroundColor(android.graphics.Color.parseColor("#111111"))
                btnSwitch.setColorFilter(android.graphics.Color.parseColor("#00FF41"))
            }
            val intent = Intent(this, SService::class.java)
            intent.action = "ACTION_SWITCH_CAMERA"
            startService(intent)
            logTerminal("LENS_SWITCH: ${if(isFrontActive) "FRONT" else "BACK"} & PREFS_SAVED")
        }
        isRecActive = prefs.getBoolean("is_rec_active", false)
        if (isRecActive) {
            rbYes?.isChecked = true
        } else {
            rbNo?.isChecked = true
        }
        rgRecord?.setOnCheckedChangeListener { _, checkedId ->
            isRecActive = (checkedId == R.id.rbYes)
            prefs.edit().putBoolean("is_rec_active", isRecActive).apply()
            if (isRecActive) {
                logTerminal("REC_PROTOCOL: ARMED (YES) & SAVED")
            } else {
                logTerminal("REC_PROTOCOL: DISARMED (NO) & SAVED")
            }
        }
        val savedQual = prefs.getInt("cam_qual", 30)
        etQuality?.setText(savedQual.toString())
        if (lastSavedIp != null && lastSavedIp.contains(":")) {
            initiateConnection(lastSavedIp)
        }
        logTerminal("SYSTEM_READY")
        checkPermissions()
        requestBatteryUnrestricted()
        btnConnect?.setOnClickListener {
            val rawInput = etAddress?.text?.toString() ?: ""
            val servers = rawInput.split(";").map { it.trim() }.filter { it.contains(":") }
            if (servers.isNotEmpty()) {
                prefs.edit().apply {
                    putString(KEY_IP, rawInput)
                    putLong("cam_fps", etFps?.text?.toString()?.toLongOrNull() ?: 7L)
                    putString("cam_res", etRes?.selectedItem?.toString()?.split(" ")?.get(0) ?: "1280x720")
                    putInt("cam_qual", etQuality?.text?.toString()?.toIntOrNull() ?: 30)
                    putLong("bb_duration", etDuration?.text?.toString()?.toLongOrNull() ?: 10L)
                    apply()
                }
                logTerminal("MULTI_LINK_INIT: ${servers.size} TARGETS")
                initiateConnection(rawInput)
            } else {
                logTerminal("SYNTAX_ERROR: USE IP:PORT ; IP:PORT")
            }
        }
    }
    private fun initiateConnection(fullAddr: String) {
        try {
            val intent = Intent(this, SService::class.java).apply {
                putExtra("SERVER_LIST", fullAddr)
                putExtra("IS_REC_ACTIVE", isRecActive)
            }
            val isIPv6 = fullAddr.contains("[") || fullAddr.count { it == ':' } > 2
            val protocolTag = if (isIPv6) "IPV6_MODE" else "IPV4_MODE"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            logTerminal("DISPATCHING_TO_SERVICE | $protocolTag")
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
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            val missingNormal = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missingNormal.isNotEmpty()) {
                logTerminal("REQUESTING_NORMAL_PERMS: ${missingNormal.size} MISSING")
                requestPermissions(missingNormal.toTypedArray(), 101)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    logTerminal("BACKGROUND_LOC_MISSING: Redirecting...")
                    android.widget.Toast.makeText(
                        this,
                        "Mandatory: Please set Location permission to 'Allow all the time' to continue.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = android.net.Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    } else {
                        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 102)
                    }
                    return
                }
            }
            logTerminal("ALL_PERMISSIONS_GRANTED")
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