package com.dualcamera.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST = 100
    private var statusReceiver: BroadcastReceiver? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var statusDot: View
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnModeFront: Button
    private lateinit var btnModeBack: Button
    private lateinit var btnModeBoth: Button
    private lateinit var switchHidden: Switch
    private lateinit var tvSavePath: TextView
    private lateinit var tvTimerLabel: TextView
    private lateinit var spinnerLang: Spinner
    private var timerHandler = Handler(Looper.getMainLooper())
    private var timerSeconds = 0
    private var isRecording = false

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.READ_MEDIA_VIDEO)
    }.toTypedArray()

    private val languages = arrayOf("العربية", "English", "Español", "Français", "Deutsch", "Türkçe", "हिंदी")
    private val langCodes = arrayOf("ar", "en", "es", "fr", "de", "tr", "hi")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("DualCamPrefs", Context.MODE_PRIVATE)

        initViews()
        setupLanguageSpinner()
        setupModeButtons()
        setupVoiceButtons()
        setupSavePath()
        setupTimerButton()
        setupControlButtons()
        registerStatusReceiver()
        applyLanguage()

        if (allPermissionsGranted()) startCameraService()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        statusDot = findViewById(R.id.statusDot)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnModeFront = findViewById(R.id.btnModeFront)
        btnModeBack = findViewById(R.id.btnModeBack)
        btnModeBoth = findViewById(R.id.btnModeBoth)
        switchHidden = findViewById(R.id.switchHidden)
        tvSavePath = findViewById(R.id.tvSavePath)
        tvTimerLabel = findViewById(R.id.tvTimerLabel)
        spinnerLang = findViewById(R.id.spinnerLang)
        switchHidden.isChecked = prefs.getBoolean("hidden_mode", false)
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLang.adapter = adapter
        val savedLang = prefs.getInt("lang_index", 0)
        spinnerLang.setSelection(savedLang)
        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("lang_index", pos).apply()
                applyLanguage()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun applyLanguage() {
        val idx = prefs.getInt("lang_index", 0)
        when (idx) {
            0 -> { // Arabic
                tvStatus.text = "جاهز"
                btnStart.text = "تشغيل الخدمة"
                btnStop.text = "ايقاف"
                btnModeFront.text = "امامية"
                btnModeBack.text = "خلفية"
                btnModeBoth.text = "الاتنين"
                findViewById<Button>(R.id.btnVoiceFront).text = "بصمة امامية"
                findViewById<Button>(R.id.btnVoiceBack).text = "بصمة خلفية"
                findViewById<Button>(R.id.btnVoiceBoth).text = "بصمة الاتنين"
                findViewById<Button>(R.id.btnVoiceStop).text = "بصمة وقف"
            }
            1 -> { // English
                tvStatus.text = "Ready"
                btnStart.text = "Start Service"
                btnStop.text = "Stop"
                btnModeFront.text = "Front"
                btnModeBack.text = "Back"
                btnModeBoth.text = "Both"
                findViewById<Button>(R.id.btnVoiceFront).text = "Voice: Front"
                findViewById<Button>(R.id.btnVoiceBack).text = "Voice: Back"
                findViewById<Button>(R.id.btnVoiceBoth).text = "Voice: Both"
                findViewById<Button>(R.id.btnVoiceStop).text = "Voice: Stop"
            }
            else -> {}
        }
    }

    private fun setupModeButtons() {
        val savedMode = prefs.getString("camera_mode", "both") ?: "both"
        updateModeButtons(savedMode)
        btnModeFront.setOnClickListener { setMode("front") }
        btnModeBack.setOnClickListener { setMode("back") }
        btnModeBoth.setOnClickListener { setMode("both") }
    }

    private fun setMode(mode: String) {
        prefs.edit().putString("camera_mode", mode).apply()
        updateModeButtons(mode)
        sendBroadcast(Intent(CameraRecordingService.ACTION_SET_MODE).putExtra("mode", mode))
    }

    private fun updateModeButtons(mode: String) {
        val activeColor = getColor(android.R.color.holo_blue_light)
        val inactiveColor = 0xFF1A1A2E.toInt()
        btnModeFront.setBackgroundColor(if (mode == "front") activeColor else inactiveColor)
        btnModeBack.setBackgroundColor(if (mode == "back") activeColor else inactiveColor)
        btnModeBoth.setBackgroundColor(if (mode == "both") activeColor else inactiveColor)
        btnModeFront.setTextColor(if (mode == "front") 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        btnModeBack.setTextColor(if (mode == "back") 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        btnModeBoth.setTextColor(if (mode == "both") 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun setupVoiceButtons() {
        findViewById<Button>(R.id.btnVoiceFront).setOnClickListener { recordVoiceFingerprint("front") }
        findViewById<Button>(R.id.btnVoiceBack).setOnClickListener { recordVoiceFingerprint("back") }
        findViewById<Button>(R.id.btnVoiceBoth).setOnClickListener { recordVoiceFingerprint("both") }
        findViewById<Button>(R.id.btnVoiceStop).setOnClickListener { recordVoiceFingerprint("stop") }
    }

    private fun recordVoiceFingerprint(type: String) {
        val langIdx = prefs.getInt("lang_index", 0)
        val hints = mapOf(
            "front" to arrayOf("امامية","front","frontal","avant","vorne","on","आगे"),
            "back" to arrayOf("خلفية","back","trasera","arriere","hinten","arka","पीछे"),
            "both" to arrayOf("الاتنين","both","ambas","les deux","beide","ikisi","दोनों"),
            "stop" to arrayOf("وقف","stop","parar","arreter","stopp","dur","रुको")
        )
        val hint = hints[type]?.getOrElse(langIdx) { hints[type]!![0] } ?: ""
        AlertDialog.Builder(this)
            .setTitle("تسجيل بصمة الصوت")
            .setMessage("قل الكلمة: \"$hint\"\nبعد ضغط موافق")
            .setPositiveButton("تسجيل") { _, _ ->
                sendBroadcast(Intent(CameraRecordingService.ACTION_RECORD_VOICE).putExtra("type", type))
                Toast.makeText(this, "قل الكلمة الان...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("الغاء", null)
            .show()
    }

    private fun setupSavePath() {
        tvSavePath.text = if (switchHidden.isChecked) "مسار الحفظ: مخفي" else "مسار الحفظ: معرض الصور"
        switchHidden.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("hidden_mode", checked).apply()
            tvSavePath.text = if (checked) "مسار الحفظ: مخفي" else "مسار الحفظ: معرض الصور"
            sendBroadcast(Intent(CameraRecordingService.ACTION_SET_HIDDEN).putExtra("hidden", checked))
        }
    }

    private fun setupTimerButton() {
        val savedTimer = prefs.getInt("rec_timer_min", 0)
        tvTimerLabel.text = if (savedTimer == 0) "مؤقت: بدون حد" else "مؤقت: $savedTimer دقيقة"
        findViewById<Button>(R.id.btnSetTimer).setOnClickListener {
            val options = arrayOf("بدون حد", "1 دقيقة", "5 دقائق", "10 دقائق", "30 دقيقة", "60 دقيقة")
            val values = arrayOf(0, 1, 5, 10, 30, 60)
            AlertDialog.Builder(this)
                .setTitle("حدد مدة التسجيل")
                .setItems(options) { _, which ->
                    val val_ = values[which]
                    prefs.edit().putInt("rec_timer_min", val_).apply()
                    tvTimerLabel.text = if (val_ == 0) "مؤقت: بدون حد" else "مؤقت: $val_ دقيقة"
                    sendBroadcast(Intent(CameraRecordingService.ACTION_SET_TIMER).putExtra("minutes", val_))
                }.show()
        }
    }

    private fun setupControlButtons() {
        btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                startCameraService()
                Toast.makeText(this, "الخدمة شغالة", Toast.LENGTH_SHORT).show()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
            }
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, CameraRecordingService::class.java))
            Toast.makeText(this, "تم ايقاف الخدمة", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        findViewById<Button>(R.id.btnLock).setOnClickListener {
            lockApp()
        }
    }

    private fun lockApp() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Toast.makeText(applicationContext, "تم التحقق", Toast.LENGTH_SHORT).show()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(applicationContext, "خطأ: $errString", Toast.LENGTH_SHORT).show()
                }
            })
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("قفل DualCamera")
                .setSubtitle("استخدم بصمة الاصبع")
                .setNegativeButtonText("الغاء")
                .build()
            biometricPrompt.authenticate(promptInfo)
        } else {
            Toast.makeText(this, "بصمة الاصبع غير متاحة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerStatusReceiver() {
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(CameraRecordingService.EXTRA_STATUS) ?: return
                when (status) {
                    "recording" -> {
                        isRecording = true
                        tvStatus.text = "يسجل الان"
                        tvStatus.setTextColor(0xFFEF4444.toInt())
                        statusDot.setBackgroundResource(R.drawable.dot_recording)
                        tvTimer.visibility = View.VISIBLE
                        startUITimer()
                    }
                    "stopped" -> {
                        isRecording = false
                        tvStatus.text = "تم الحفظ"
                        tvStatus.setTextColor(0xFF10B981.toInt())
                        statusDot.setBackgroundResource(R.drawable.dot_idle)
                        tvTimer.visibility = View.INVISIBLE
                        stopUITimer()
                    }
                    "ready" -> {
                        tvStatus.text = "جاهز"
                        tvStatus.setTextColor(0xFFFFFFFF.toInt())
                        statusDot.setBackgroundResource(R.drawable.dot_idle)
                    }
                }
            }
        }
        val filter = IntentFilter(CameraRecordingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun startUITimer() {
        timerSeconds = 0
        timerHandler.post(object : Runnable {
            override fun run() {
                val min = timerSeconds / 60
                val sec = timerSeconds % 60
                tvTimer.text = String.format("%02d:%02d", min, sec)
                timerSeconds++
                timerHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopUITimer() {
        timerHandler.removeCallbacksAndMessages(null)
        timerSeconds = 0
        tvTimer.text = "00:00"
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraRecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST && allPermissionsGranted()) startCameraService()
    }

    override fun onDestroy() {
        statusReceiver?.let { unregisterReceiver(it) }
        stopUITimer()
        super.onDestroy()
    }
}
