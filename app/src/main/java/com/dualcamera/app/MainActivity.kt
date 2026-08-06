package com.dualcamera.app
import android.Manifest
import android.content.Intent
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
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvPath = findViewById<TextView>(R.id.tvPath)
        val dir = getExternalFilesDir(null) ?: filesDir
        tvPath.text = "📁 مسار الحفظ:
" + dir.absolutePath
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (allPermissionsGranted()) { startCameraService()
                Toast.makeText(this, "✅ الخدمة شغالة!
🔊 ارفع الصوت = ابدأ
🔉 اخفض الصوت = وقف", Toast.LENGTH_LONG).show()
            } else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CameraRecordingService::class.java))
            Toast.makeText(this, "⏹ تم إيقاف الخدمة", Toast.LENGTH_SHORT).show()
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
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted()) { startCameraService()
                Toast.makeText(this, "✅ تم منح الصلاحيات!", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "❌ يرجى منح الصلاحيات من الإعدادات", Toast.LENGTH_LONG).show()
        }
    }
}
