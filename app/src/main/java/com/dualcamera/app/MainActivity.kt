package com.dualcamera.app
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
class MainActivity : AppCompatActivity() {
    private val PERMISSIONS_REQUEST = 100
    private var statusReceiver: BroadcastReceiver? = null
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "جاري فحص الجهاز..."
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when {
                    intent.hasExtra(CameraRecordingService.EXTRA_CONCURRENT) -> {
                        val c = intent.getBooleanExtra(CameraRecordingService.EXTRA_CONCURRENT, false)
                        tvStatus.text = if (c) "الجهاز يدعم التصوير المزدوج المتزامن" else "الجهاز يدعم التصوير المتعاقب"
                    }
                    intent.getStringExtra(CameraRecordingService.EXTRA_STATUS) == "recording" -> {
                        tvStatus.text = "يسجل الان - اخفض الصوت للايقاف"
                    }
                    intent.getStringExtra(CameraRecordingService.EXTRA_STATUS) == "stopped" -> {
                        tvStatus.text = "تم الحفظ في معرض الصور"
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
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (allPermissionsGranted()) {
                startCameraService()
                Toast.makeText(this, "الخدمة شغالة - ارفع الصوت للتسجيل", Toast.LENGTH_SHORT).show()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CameraRecordingService::class.java))
            Toast.makeText(this, "تم ايقاف الخدمة", Toast.LENGTH_SHORT).show()
        }
        if (allPermissionsGranted()) startCameraService()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
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
        super.onDestroy()
    }
}
