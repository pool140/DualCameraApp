package com.dualcamera.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.camera2.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraRecordingService : Service() {

    companion object {
        const val TAG = "DualCamera"
        const val CHANNEL_ID = "DualCameraChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATUS = "com.dualcamera.STATUS"
        const val ACTION_SET_MODE = "com.dualcamera.SET_MODE"
        const val ACTION_SET_HIDDEN = "com.dualcamera.SET_HIDDEN"
        const val ACTION_SET_TIMER = "com.dualcamera.SET_TIMER"
        const val ACTION_RECORD_VOICE = "com.dualcamera.RECORD_VOICE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONCURRENT = "concurrent"
    }

    // Camera
    private lateinit var cameraManager: CameraManager
    private var frontDevice: CameraDevice? = null
    private var backDevice: CameraDevice? = null
    private var frontRecorder: MediaRecorder? = null
    private var backRecorder: MediaRecorder? = null
    private var frontSession: CameraCaptureSession? = null
    private var backSession: CameraCaptureSession? = null
    private var frontUri: Uri? = null
    private var backUri: Uri? = null
    private var frontPfd: ParcelFileDescriptor? = null
    private var backPfd: ParcelFileDescriptor? = null
    private var frontLegacyPath = ""
    private var backLegacyPath = ""

    // Control
    private lateinit var audioManager: AudioManager
    private lateinit var volumeObserver: ContentObserver
    private var lastVolume = 0
    private var isRecording = false
    private var cameraMode = "both"
    private var hiddenMode = false
    private var timerMinutes = 0
    private var recordingStartTime = 0L

    // Voice
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningForFingerprint = false
    private var fingerprintType = ""

    // Volume press tracking
    private var volumeUpPressCount = 0
    private var volumeUpHandler = Handler(Looper.getMainLooper())
    private var screenOn = true

    // Timer
    private var timerHandler = Handler(Looper.getMainLooper())
    private var minuteHandler = Handler(Looper.getMainLooper())

    // Vibrator
    private lateinit var vibrator: Vibrator

    // Thread
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock

    // Prefs
    private lateinit var prefs: SharedPreferences

    // Screen receiver
    private var screenReceiver: BroadcastReceiver? = null

    // Settings receiver
    private var settingsReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startBackgroundThread()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        prefs = getSharedPreferences("DualCamPrefs", Context.MODE_PRIVATE)
        cameraMode = prefs.getString("camera_mode", "both") ?: "both"
        hiddenMode = prefs.getBoolean("hidden_mode", false)
        timerMinutes = prefs.getInt("rec_timer_min", 0)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualCamera::WakeLock")
        wakeLock.acquire(10 * 60 * 60 * 1000L)

        createNotificationChannel()
        val notif = buildNotification("جاهز - ارفع الصوت للتسجيل")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        registerVolumeObserver()
        registerScreenReceiver()
        registerSettingsReceiver()
        startVoiceListening()
        broadcastStatus("ready")
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CamBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    // ─── Screen Receiver ───────────────────────────────────────────────────────
    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                    Intent.ACTION_SCREEN_ON -> screenOn = true
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, f)
    }

    // ─── Settings Receiver ─────────────────────────────────────────────────────
    private fun registerSettingsReceiver() {
        settingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SET_MODE -> {
                        cameraMode = intent.getStringExtra("mode") ?: "both"
                        prefs.edit().putString("camera_mode", cameraMode).apply()
                    }
                    ACTION_SET_HIDDEN -> {
                        hiddenMode = intent.getBooleanExtra("hidden", false)
                        prefs.edit().putBoolean("hidden_mode", hiddenMode).apply()
                    }
                    ACTION_SET_TIMER -> {
                        timerMinutes = intent.getIntExtra("minutes", 0)
                        prefs.edit().putInt("rec_timer_min", timerMinutes).apply()
                    }
                    ACTION_RECORD_VOICE -> {
                        fingerprintType = intent.getStringExtra("type") ?: ""
                        isListeningForFingerprint = true
                        recordVoiceFingerprint(fingerprintType)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_SET_MODE)
            addAction(ACTION_SET_HIDDEN)
            addAction(ACTION_SET_TIMER)
            addAction(ACTION_RECORD_VOICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsReceiver, f)
        }
    }

    // ─── Volume Observer ───────────────────────────────────────────────────────
    private fun registerVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur != lastVolume) {
                    val up = cur > lastVolume
                    lastVolume = cur
                    if (up) handleVolumeUp()
                    else if (isRecording) stopRecording()
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    private fun handleVolumeUp() {
        if (!screenOn) {
            // Screen off: single press starts recording
            if (!isRecording) startRecording()
        } else {
            // Screen on: triple press starts recording
            volumeUpPressCount++
            volumeUpHandler.removeCallbacksAndMessages(null)
            volumeUpHandler.postDelayed({
                if (volumeUpPressCount >= 3 && !isRecording) startRecording()
                volumeUpPressCount = 0
            }, 800)
        }
    }

    // ─── Voice Fingerprint ─────────────────────────────────────────────────────
    private fun startVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spoken = matches[0].lowercase(Locale.getDefault())
                        if (isListeningForFingerprint) {
                            saveVoiceFingerprint(fingerprintType, spoken)
                            isListeningForFingerprint = false
                            sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, "voice_saved"))
                        } else {
                            checkVoiceCommand(spoken)
                        }
                    }
                    if (!isListeningForFingerprint) restartListening()
                }
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { mainHandler.postDelayed({ restartListening() }, 1000) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListeningSession()
        }
    }

    private fun startListeningSession() {
        if (isRecording) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, getCurrentLocale())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListening() {
        mainHandler.postDelayed({ startListeningSession() }, 500)
    }

    private fun getCurrentLocale(): String {
        val idx = prefs.getInt("lang_index", 0)
        return arrayOf("ar-SA", "en-US", "es-ES", "fr-FR", "de-DE", "tr-TR", "hi-IN")[idx]
    }

    private fun saveVoiceFingerprint(type: String, text: String) {
        prefs.edit().putString("voice_$type", text).apply()
        Log.d(TAG, "Saved voice fingerprint for $type: $text")
    }

    private fun checkVoiceCommand(spoken: String) {
        val front = prefs.getString("voice_front", null)
        val back = prefs.getString("voice_back", null)
        val both = prefs.getString("voice_both", null)
        val stop = prefs.getString("voice_stop", null)

        when {
            stop != null && isSimilar(spoken, stop) && isRecording -> stopRecording()
            front != null && isSimilar(spoken, front) && !isRecording -> {
                cameraMode = "front"; startRecording()
            }
            back != null && isSimilar(spoken, back) && !isRecording -> {
                cameraMode = "back"; startRecording()
            }
            both != null && isSimilar(spoken, both) && !isRecording -> {
                cameraMode = "both"; startRecording()
            }
        }
    }

    private fun isSimilar(spoken: String, saved: String): Boolean {
        val s = spoken.lowercase().trim()
        val sv = saved.lowercase().trim()
        return s == sv || s.contains(sv) || sv.contains(s)
    }

    private fun recordVoiceFingerprint(type: String) {
        mainHandler.post {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, getCurrentLocale())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    // ─── Recording ─────────────────────────────────────────────────────────────
    private fun startRecording() {
        vibrateShort()
        backgroundHandler.post {
            try {
                val ts = timestamp()
                when (cameraMode) {
                    "front" -> {
                        frontRecorder = createRecorder("FRONT", ts, withAudio = true, rotation = 270)
                        getFrontId()?.let { openCamera(it, frontRecorder!!, true) }
                    }
                    "back" -> {
                        backRecorder = createRecorder("BACK", ts, withAudio = true, rotation = 90)
                        getBackId()?.let { openCamera(it, backRecorder!!, false) }
                    }
                    else -> {
                        backRecorder = createRecorder("BACK", ts, withAudio = true, rotation = 90)
                        getBackId()?.let { openCamera(it, backRecorder!!, false) }
                        mainHandler.postDelayed({
                            try {
                                frontRecorder = createRecorder("FRONT", ts, withAudio = false, rotation = 270)
                                getFrontId()?.let { openCamera(it, frontRecorder!!, true) }
                            } catch (e: Exception) { Log.e(TAG, "front: " + e.message) }
                        }, 2000)
                    }
                }
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                updateNotification("يسجل - اخفض الصوت للايقاف")
                broadcastStatus("recording")
                startMinuteVibration()
                if (timerMinutes > 0) {
                    timerHandler.postDelayed({ stopRecording() }, timerMinutes * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start: " + e.message)
                cleanupAll()
            }
        }
    }

    private fun stopRecording() {
        vibrateDouble()
        timerHandler.removeCallbacksAndMessages(null)
        minuteHandler.removeCallbacksAndMessages(null)
        backgroundHandler.post {
            try {
                frontSession?.close(); backSession?.close()
                frontDevice?.close(); backDevice?.close()
                try { frontRecorder?.stop() } catch (e: Exception) {}
                try { backRecorder?.stop() } catch (e: Exception) {}
                frontRecorder?.release(); backRecorder?.release()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!hiddenMode) { finalizeUri(frontUri); finalizeUri(backUri) }
                    try { frontPfd?.close() } catch (e: Exception) {}
                    try { backPfd?.close() } catch (e: Exception) {}
                } else {
                    if (!hiddenMode) {
                        val paths = mutableListOf<String>()
                        if (frontLegacyPath.isNotEmpty()) paths.add(frontLegacyPath)
                        if (backLegacyPath.isNotEmpty()) paths.add(backLegacyPath)
                        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "stop: " + e.message)
            } finally {
                cleanupAll()
                updateNotification("تم الحفظ - جاهز")
                broadcastStatus("stopped")
                mainHandler.postDelayed({ startVoiceListening() }, 1000)
            }
        }
    }

    // ─── Vibration ─────────────────────────────────────────────────────────────
    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(150)
    }

    private fun vibrateDouble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
        else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
    }

    private fun vibrateMinute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(80, 50))
        else @Suppress("DEPRECATION") vibrator.vibrate(80)
    }

    private fun startMinuteVibration() {
        minuteHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isRecording) { vibrateMinute(); minuteHandler.postDelayed(this, 60000) }
            }
        }, 60000)
    }

    // ─── MediaRecorder ─────────────────────────────────────────────────────────
    private fun createRecorder(prefix: String, ts: String, withAudio: Boolean, rotation: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hiddenMode) {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.DISPLAY_NAME, prefix + "_" + ts + ".mp4")
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualCamera")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            val pfd = contentResolver.openFileDescriptor(uri, "w")!!
            if (prefix == "FRONT") { frontUri = uri; frontPfd = pfd }
            else { backUri = uri; backPfd = pfd }
            r.apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(5_000_000)
                setOrientationHint(rotation); setOutputFile(pfd.fileDescriptor); prepare()
            }
        } else {
            val dir = if (hiddenMode) {
                File(filesDir, "hidden_videos").also { it.mkdirs() }
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DualCamera").also { it.mkdirs() }
            }
            val path = File(dir, prefix + "_" + ts + ".mp4").absolutePath
            if (prefix == "FRONT") frontLegacyPath = path else backLegacyPath = path
            r.apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(5_000_000)
                setOrientationHint(rotation); setOutputFile(path); prepare()
            }
        }
        return r
    }

    private fun finalizeUri(uri: Uri?) {
        uri?.let {
            val v = ContentValues(); v.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(it, v, null, null)
        }
    }

    private fun cleanupAll() {
        frontSession = null; backSession = null; frontDevice = null; backDevice = null
        frontRecorder = null; backRecorder = null
        frontUri = null; backUri = null; frontPfd = null; backPfd = null
        frontLegacyPath = ""; backLegacyPath = ""; isRecording = false
    }

    // ─── Camera2 ───────────────────────────────────────────────────────────────
    private fun getFrontId() = cameraManager.cameraIdList.firstOrNull {
        cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    }

    private fun getBackId() = cameraManager.cameraIdList.firstOrNull {
        cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    @Suppress("DEPRECATION")
    private fun openCamera(id: String, recorder: MediaRecorder, isFront: Boolean) {
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (isFront) frontDevice = cam else backDevice = cam
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorder.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    cam.createCaptureSession(listOf(recorder.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (isFront) frontSession = s else backSession = s
                            try { s.setRepeatingRequest(b.build(), null, backgroundHandler); recorder.start() }
                            catch (e: Exception) { Log.e(TAG, "session: " + e.message) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, backgroundHandler)
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, e: Int) { c.close() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "open: " + e.message) }
    }

    // ─── Notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Dual Camera", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, CameraRecordingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dual Camera").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "ايقاف", pi).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    // ─── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { if (isRecording) stopRecording(); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isRecording) stopRecording()
        contentResolver.unregisterContentObserver(volumeObserver)
        screenReceiver?.let { unregisterReceiver(it) }
        settingsReceiver?.let { unregisterReceiver(it) }
        speechRecognizer?.destroy()
        backgroundThread.quitSafely()
        timerHandler.removeCallbacksAndMessages(null)
        minuteHandler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
