package com.dualcamera.app
import android.app.*
import android.content.*
import android.database.ContentObserver
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.*
import android.provider.Settings
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
    }
    private lateinit var cameraManager: CameraManager
    private var frontCameraDevice: CameraDevice? = null
    private var backCameraDevice: CameraDevice? = null
    private var frontRecorder: MediaRecorder? = null
    private var backRecorder: MediaRecorder? = null
    private var frontSession: CameraCaptureSession? = null
    private var backSession: CameraCaptureSession? = null
    private lateinit var audioManager: AudioManager
    private lateinit var volumeObserver: ContentObserver
    private var lastVolume = 0
    private var isRecording = false
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    override fun onCreate() {
        super.onCreate()
        startBackgroundThread()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualCamera::WakeLock")
        wakeLock.acquire(10 * 60 * 60 * 1000L)
        createNotificationChannel()
        val notif = buildNotification("جاهز - ارفع الصوت للتسجيل")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            startForeground(NOTIFICATION_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIFICATION_ID, notif)
        registerVolumeObserver()
    }
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }
    private fun registerVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur != lastVolume) {
                    val up = cur > lastVolume; lastVolume = cur
                    if (up && !isRecording) startDualRecording()
                    else if (!up && isRecording) stopDualRecording()
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }
    private fun startDualRecording() {
        backgroundHandler.post {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = getExternalFilesDir(null) ?: filesDir
                frontRecorder = buildRecorder(File(dir, "FRONT_$ts.mp4").absolutePath, false, 270)
                backRecorder  = buildRecorder(File(dir, "BACK_$ts.mp4").absolutePath,  true,  90)
                var frontId: String? = null; var backId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    if (f == CameraCharacteristics.LENS_FACING_FRONT) frontId = id
                    if (f == CameraCharacteristics.LENS_FACING_BACK)  backId  = id
                }
                frontId?.let { openCamera(it, frontRecorder!!, true)  }
                backId?.let  { openCamera(it, backRecorder!!,  false) }
                isRecording = true
                updateNotification("🔴 يسجل - اخفض الصوت للإيقاف")
            } catch (e: Exception) { Log.e(TAG, "Start error: " + e.message); cleanupAll() }
        }
    }
    private fun stopDualRecording() {
        backgroundHandler.post {
            try {
                frontSession?.close(); backSession?.close()
                frontCameraDevice?.close(); backCameraDevice?.close()
                try { frontRecorder?.stop() } catch (e: Exception) {}
                try { backRecorder?.stop()  } catch (e: Exception) {}
                frontRecorder?.release(); backRecorder?.release()
            } catch (e: Exception) { Log.e(TAG, "Stop error: " + e.message) }
            finally { cleanupAll(); updateNotification("✅ تم الحفظ - جاهز مرة أخرى") }
        }
    }
    private fun cleanupAll() {
        frontSession = null; backSession = null; frontCameraDevice = null; backCameraDevice = null
        frontRecorder = null; backRecorder = null; isRecording = false
    }
    @Suppress("DEPRECATION")
    private fun openCamera(cameraId: String, recorder: MediaRecorder, isFront: Boolean) {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isFront) frontCameraDevice = camera else backCameraDevice = camera
                    val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(recorder.surface) }
                    camera.createCaptureSession(listOf(recorder.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (isFront) frontSession = s else backSession = s
                            try { s.setRepeatingRequest(b.build(), null, backgroundHandler); recorder.start() }
                            catch (e: Exception) { Log.e(TAG, "Session error: " + e.message) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, backgroundHandler)
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, e: Int) { c.close() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Open camera error: " + e.message) }
    }
    private fun buildRecorder(path: String, audio: Boolean, rot: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        return r.apply {
            if (audio) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (audio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(5_000_000)
            setOrientationHint(rot); setOutputFile(path); prepare()
        }
    }
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
            .setContentTitle("📷 Dual Camera").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "إيقاف", pi).build()
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { if (isRecording) stopDualRecording(); stopSelf() }
        return START_STICKY
    }
    override fun onDestroy() {
        if (isRecording) stopDualRecording()
        contentResolver.unregisterContentObserver(volumeObserver)
        backgroundThread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
