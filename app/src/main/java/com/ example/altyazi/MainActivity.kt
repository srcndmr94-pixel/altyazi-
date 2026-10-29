package com.example.altyazi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val req = 1

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setPadding(0, 28, 0, 8)
    }

    private fun seek(key: String, def: Int) = SeekBar(this).apply {
        max = 100
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        progress = p.getInt(key, def)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                p.edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
        }
        root.addView(TextView(this).apply { text = "Altyazı Çevir (İngilizce → Türkçe)"; textSize = 22f })
        root.addView(label("Altyazı bölgesi: üst sınır (%)"))
        root.addView(seek("top", 70))
        root.addView(label("Altyazı bölgesi: alt sınır (%)"))
        root.addView(seek("bottom", 95))
        root.addView(Button(this).apply {
            text = "Başlat"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce “diğer uygulamaların üzerinde göster” iznini verin", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == req && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Başladı. PS Remote Play’i açın.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
