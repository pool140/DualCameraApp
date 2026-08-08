package com.dualcamera.app
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
        const val ACTION_STATUS = "com.dualcamera.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONCURRENT = "concurrent"
    }
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
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
    private lateinit var volumeObserver: ContentObserver
    private var lastVolume = 0
    private var isRecording = false
    private var supportsConcurrent = false
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    override fun onCreate() {
        super.onCreate()
        startBackgroundThread()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        supportsConcurrent = checkConcurrent()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualCamera::WakeLock")
        wakeLock.acquire(10 * 60 * 60 * 1000L)
        createNotificationChannel()
        val msg = if (supportsConcurrent) "جاهز (متزامن) - ارفع الصوت" else "جاهز (متعاقب) - ارفع الصوت"
        val notif = buildNotification(msg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        broadcast(ACTION_STATUS, EXTRA_CONCURRENT, supportsConcurrent)
        registerVolumeObserver()
    }
    private fun checkConcurrent(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cameraManager.concurrentStreamingCameraIds.isNotEmpty()
            } else false
        } catch (e: Exception) { false }
    }
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CamBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }
    private fun registerVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur != lastVolume) {
                    val up = cur > lastVolume
                    lastVolume = cur
                    if (up && !isRecording) startRecording()
                    else if (!up && isRecording) stopRecording()
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }
    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    private fun startRecording() {
        backgroundHandler.post {
            try {
                val ts = timestamp()
                backRecorder = createRecorder("BACK", ts, withAudio = true, rotation = 90)
                if (supportsConcurrent) {
                    frontRecorder = createRecorder("FRONT", ts, withAudio = false, rotation = 270)
                    openCamera(getFrontId() ?: return@post, frontRecorder!!, true)
                    openCamera(getBackId() ?: return@post, backRecorder!!, false)
                } else {
                    openCamera(getBackId() ?: return@post, backRecorder!!, false)
                    mainHandler.postDelayed({
                        try {
                            frontRecorder = createRecorder("FRONT", ts, withAudio = false, rotation = 270)
                            getFrontId()?.let { openCamera(it, frontRecorder!!, true) }
                        } catch (e: Exception) { Log.e(TAG, "front delayed: " + e.message) }
                    }, 2000)
                }
                isRecording = true
                val mode = if (supportsConcurrent) "متزامن" else "متعاقب"
                updateNotification("يسجل (" + mode + ") - اخفض للايقاف")
                broadcastStatus("recording")
            } catch (e: Exception) {
                Log.e(TAG, "start error: " + e.message)
                cleanupAll()
            }
        }
    }
    private fun stopRecording() {
        backgroundHandler.post {
            try {
                frontSession?.close(); backSession?.close()
                frontDevice?.close(); backDevice?.close()
                try { frontRecorder?.stop() } catch (e: Exception) {}
                try { backRecorder?.stop() } catch (e: Exception) {}
                frontRecorder?.release(); backRecorder?.release()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    finalizeUri(frontUri)
                    finalizeUri(backUri)
                    frontPfd?.close(); backPfd?.close()
                } else {
                    val paths = mutableListOf<String>()
                    if (frontLegacyPath.isNotEmpty()) paths.add(frontLegacyPath)
                    if (backLegacyPath.isNotEmpty()) paths.add(backLegacyPath)
                    if (paths.isNotEmpty()) {
                        MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "stop error: " + e.message)
            } finally {
                cleanupAll()
                updateNotification("تم الحفظ في المعرض - جاهز")
                broadcastStatus("stopped")
            }
        }
    }
    private fun createRecorder(prefix: String, ts: String, withAudio: Boolean, rotation: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.DISPLAY_NAME, prefix + "_" + ts + ".mp4")
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualCamera")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val pfd = contentResolver.openFileDescriptor(uri!!, "w")
            if (prefix == "FRONT") { frontUri = uri; frontPfd = pfd }
            else { backUri = uri; backPfd = pfd }
            r.apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(5_000_000)
                setOrientationHint(rotation)
                setOutputFile(pfd!!.fileDescriptor)
                prepare()
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DualCamera")
            dir.mkdirs()
            val path = File(dir, prefix + "_" + ts + ".mp4").absolutePath
            if (prefix == "FRONT") frontLegacyPath = path else backLegacyPath = path
            r.apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720); setVideoFrameRate(30); setVideoEncodingBitRate(5_000_000)
                setOrientationHint(rotation)
                setOutputFile(path)
                prepare()
            }
        }
        return r
    }
    private fun finalizeUri(uri: Uri?) {
        uri?.let {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(it, values, null, null)
        }
    }
    private fun cleanupAll() {
        frontSession = null; backSession = null; frontDevice = null; backDevice = null
        frontRecorder = null; backRecorder = null
        frontUri = null; backUri = null; frontPfd = null; backPfd = null
        frontLegacyPath = ""; backLegacyPath = ""
        isRecording = false
    }
    private fun getFrontId(): String? = cameraManager.cameraIdList.firstOrNull {
        cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    }
    private fun getBackId(): String? = cameraManager.cameraIdList.firstOrNull {
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
                            catch (e: Exception) { Log.e(TAG, "session err: " + e.message) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, backgroundHandler)
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, e: Int) { c.close() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "open camera err: " + e.message) }
    }
    private fun broadcast(action: String, key: String, value: Boolean) {
        sendBroadcast(Intent(action).apply { putExtra(key, value) })
    }
    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply { putExtra(EXTRA_STATUS, status) })
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
            .setContentTitle("Dual Camera").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "ايقاف", pi).build()
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { if (isRecording) stopRecording(); stopSelf() }
        return START_STICKY
    }
    override fun onDestroy() {
        if (isRecording) stopRecording()
        contentResolver.unregisterContentObserver(volumeObserver)
        backgroundThread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
