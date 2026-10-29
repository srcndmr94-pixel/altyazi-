package com.example.altyazi

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var overlay: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("cap")
    private lateinit var bg: Handler
    private lateinit var wm: WindowManager
    private var running = false
    private var busy = false
    private var started = false
    private var prevSig: IntArray? = null
    private var lastText = ""
    private val cache = HashMap<String, String>()
    private var translatorReady = false
    private lateinit var translator: Translator
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
        else i.getParcelableExtra("data")

    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun capSize(m: DisplayMetrics): Pair<Int, Int> {
        val s = min(1f, 1280f / max(m.widthPixels, m.heightPixels).toFloat())
        return Pair((m.widthPixels * s).toInt(), (m.heightPixels * s).toInt())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || started) return START_NOT_STICKY
        started = true
        startFg()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val data = getData(intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(intent.getIntExtra("code", 0), data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)
        setupTranslator()
        addOverlay()
        thread.start()
        bg = Handler(thread.looper)
        running = true
        createCapture()
        bg.post(tick)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Altyazı", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("Altyazı çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setupTranslator() {
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true }
    }

    private fun addOverlay() {
        val tv = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }
        wm.addView(tv, lp)
        overlay = tv
    }

    private fun setOverlay(s: String) {
        ui.post {
            overlay?.let {
                it.text = s
                it.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun createCapture() {
        val m = metrics()
        val (w, h) = capSize(m)
        capW = w; capH = h
        reader?.close()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        if (vd == null) {
            vd = projection!!.createVirtualDisplay(
                "cap", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, null
            )
        } else {
            vd!!.resize(w, h, m.densityDpi)
            vd!!.surface = reader!!.surface
        }
        prevSig = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { step() } catch (e: Exception) { busy = false }
            bg.postDelayed(this, 350)
        }
    }

    private fun step() {
        val (w, h) = capSize(metrics())
        if (abs(w - capW) > 2 || abs(h - capH) > 2) { createCapture(); return }
        val img = reader?.acquireLatestImage() ?: return
        val full: Bitmap
        try {
            val pl = img.planes[0]
            val rw = pl.rowStride / pl.pixelStride
            full = Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(pl.buffer)
        } finally { img.close() }

        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val b = p.getInt("bottom", 95).coerceIn(5, 100)
        val t = min(p.getInt("top", 70), b - 5).coerceAtLeast(0)
        val y0 = capH * t / 100
        val y1 = capH * b / 100
        val crop = Bitmap.createBitmap(full, 0, y0, capW, max(1, y1 - y0))
        full.recycle()

        if (busy) { crop.recycle(); return }

        val small = Bitmap.createScaledBitmap(crop, 48, 12, true)
        val px = IntArray(48 * 12)
        small.getPixels(px, 0, 48, 0, 0, 48, 12)
        small.recycle()
        val sig = IntArray(px.size) { (px[it] shr 8) and 0xFF }
        val prev = prevSig
        if (prev != null) {
            var d = 0
            for (i in sig.indices) d += abs(sig[i] - prev[i])
            if (d / sig.size < 5) { crop.recycle(); return }
        }
        prevSig = sig

        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { r -> crop.recycle(); handle(r.text) }
            .addOnFailureListener { crop.recycle(); busy = false }
    }

    private fun handle(raw: String) {
        val text = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (text.length < 3) { lastText = ""; setOverlay(""); busy = false; return }
        if (text == lastText) { busy = false; return }
        cache[text]?.let { lastText = text; setOverlay(it); busy = false; return }
        if (!translatorReady) { setOverlay("Çeviri modeli indiriliyor…"); busy = false; return }
        lastText = text
        translator.translate(text)
            .addOnSuccessListener { tr ->
                if (cache.size > 200) cache.clear()
                cache[text] = tr
                setOverlay(tr)
                busy = false
            }
            .addOnFailureListener { lastText = ""; busy = false }
    }

    override fun onDestroy() {
        running = false
        try { if (thread.isAlive) { bg.removeCallbacksAndMessages(null); thread.quitSafely() } } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        try { overlay?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { recognizer.close() } catch (e: Exception) {}
        try { if (::translator.isInitialized) translator.close() } catch (e: Exception) {}
        super.onDestroy()
    }
}
