package com.livesub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class CaptureService : Service() {
    private val RATE = 16000
    @Volatile private var running = false
    @Volatile private var alive = false
    @Volatile private var record: AudioRecord? = null
    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private lateinit var cfg: Cfg
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var chip: TextView
    private lateinit var trTv: TextView
    private lateinit var orTv: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var overlayAdded = false
    private val translators = Translators()
    private val langId = LanguageIdentification.getClient()
    private val net = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    private val hideTask = Runnable {
        if (overlayAdded) root.animate().alpha(0f).setDuration(300).withEndAction { root.visibility = View.GONE }.start()
    }
    private var lastErr = ""
    private var lastErrT = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY
        cfg = Cfg(this)
        Bus.status = ""; Bus.original = ""; Bus.translated = ""
        val system = cfg.capture == "system" && Build.VERSION.SDK_INT >= 29
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (system) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                if (type != 0) startForeground(1, n, type) else startForeground(1, n)
            } else startForeground(1, n)
        } catch (e: Exception) { fail("تعذّر بدء الخدمة: ${e.message}"); return START_NOT_STICKY }
        alive = true

        if (system) {
            val data = readData(intent)
            if (data == null) { fail("لم يصل إذن التقاط الصوت — أعد المحاولة"); return START_NOT_STICKY }
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(intent?.getIntExtra("code", 0) ?: 0, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { ui.post { stopSelf() } }
                }, ui)
            } catch (e: Exception) { fail("تعذّر بدء التقاط الصوت: ${e.message}"); return START_NOT_STICKY }
        }
        try { buildOverlay() } catch (e: Exception) {
            fail("تعذّر عرض الترجمة — فعّل إذن «الظهور فوق التطبيقات»"); return START_NOT_STICKY
        }
        Bus.running = true
        running = true
        thread(name = "capture") {
            try { loop(system) } catch (t: Throwable) { if (running) err("خطأ: ${t.message}") }
            finally { ui.post { if (alive) stopSelf() } }
        }
        return START_NOT_STICKY
    }

    private fun fail(m: String) { Bus.status = m; stopSelf() }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra("data", Intent::class.java)
        else i?.getParcelableExtra<Intent>("data")

    // ───────────────────────── التقاط الصوت والتعرف ─────────────────────────

    private fun createRecord(system: Boolean, rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = max(min, rate * 2)
        val r: AudioRecord = try {
            if (system && Build.VERSION.SDK_INT >= 29) {
                val cap = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(size)
                    .setAudioPlaybackCaptureConfig(cap).build()
            } else {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size)
            }
        } catch (e: Throwable) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        return r
    }

    private fun loop(system: Boolean) {
        val offline = cfg.engine == "offline"
        val tgt = cfg.target
        val src = if (cfg.source == "auto") "en" else cfg.source
        var model: Model? = null
        var rec: Recognizer? = null
        var r: AudioRecord? = null
        try {
            if (offline) {
                say("تجهيز نموذج اللغة…")
                val dir = ModelManager.ensureVosk(this, src, { say(it) }) { running } ?: return
                val mm = Model(dir.absolutePath)
                model = mm
                rec = Recognizer(mm, RATE.toFloat())
                say("تجهيز نموذج الترجمة…")
                translators.prefetchBlocking(src, tgt, 240)
            } else if (cfg.apiKey.isBlank()) { say("أدخل مفتاح API من الإعدادات"); return }
            if (!running) return

            var inRate = RATE
            r = createRecord(system, inRate)
            if (r == null && system) { inRate = 48000; r = createRecord(system, inRate) }
            if (r == null) {
                say(if (system) "تعذّر التقاط صوت التطبيقات — جرّب وضع الميكروفون" else "تعذّر تشغيل الميكروفون")
                return
            }
            record = r
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { say("تعذّر بدء التسجيل"); return }
            say(if (system) "في انتظار صوت الفيديو…" else "في انتظار الصوت من الميكروفون…")

            val vr = rec
            val raw = ShortArray(inRate / 10)
            val buf = ShortArray(RATE / 10)
            var seg = ByteArrayOutputStream()
            var segN = 0; var silence = 0
            var inSpeech = false
            var noise = 150.0
            val pre = java.util.ArrayDeque<ShortArray>()
            var zeroRun = 0; var hinted = false

            while (running) {
                val got = r.read(raw, 0, raw.size)
                if (got < 0) { if (running) say("انقطع التقاط الصوت"); break }
                if (got == 0) continue
                val n: Int
                if (inRate == RATE) { System.arraycopy(raw, 0, buf, 0, got); n = got }
                else {
                    val f = inRate / RATE
                    val m = got / f
                    for (i in 0 until m) { var sm = 0; for (k in 0 until f) sm += raw[i * f + k]; buf[i] = (sm / f).toShort() }
                    n = m
                }
                if (n == 0) continue

                var sum = 0.0; var peak = 0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v.toDouble() * v; if (abs(v) > peak) peak = abs(v) }
                val rms = sqrt(sum / n)
                Bus.level = Bus.level * 0.55f + (rms / 5000.0).toFloat().coerceIn(0f, 1f) * 0.45f

                if (system) {
                    if (peak == 0) {
                        zeroRun += n
                        if (zeroRun >= RATE * 25 && !hinted) {
                            hinted = true
                            err("لا يصل صوت. إن كان الفيديو يعمل فقد يمنع التطبيق التقاط صوته — جرّب وضع الميكروفون من الإعدادات")
                        }
                    } else { zeroRun = 0; hinted = false }
                }

                if (vr != null) { // وضع Vosk: بث مباشر
                    if (vr.acceptWaveForm(buf, n)) {
                        val t = JSONObject(vr.result).optString("text")
                        if (t.isNotBlank()) handle(t, src)
                    } else {
                        val p = JSONObject(vr.partialResult).optString("partial")
                        if (p.isNotBlank()) ui.post { if (alive) renderPartial(p) }
                    }
                    continue
                }

                // وضع Whisper: تقطيع الكلام حسب الصمت (VAD)
                val speech = rms > max(300.0, noise * 2.2)
                if (!speech && !inSpeech) noise = noise * 0.95 + rms * 0.05
                if (!inSpeech) { pre.addLast(buf.copyOf(n)); while (pre.size > 3) pre.removeFirst() }
                if (speech && !inSpeech) {
                    inSpeech = true; silence = 0
                    for (p in pre) { write(seg, p, p.size); segN += p.size }
                    pre.clear()
                } else if (inSpeech) {
                    write(seg, buf, n); segN += n
                    silence = if (speech) 0 else silence + n
                }
                if (inSpeech && (silence >= RATE / 2 || segN >= RATE * 12)) {
                    if (segN - silence >= RATE * 4 / 10) submit(seg.toByteArray())
                    seg = ByteArrayOutputStream(); segN = 0; silence = 0; inSpeech = false
                }
            }
        } finally {
            try { r?.stop() } catch (e: Exception) {}
            r?.release(); record = null
            try { rec?.close() } catch (e: Exception) {}
            try { model?.close() } catch (e: Exception) {}
        }
    }

    private fun write(o: ByteArrayOutputStream, b: ShortArray, n: Int) {
        for (i in 0 until n) { o.write(b[i].toInt() and 0xFF); o.write((b[i].toInt() shr 8) and 0xFF) }
    }

    private fun submit(pcm: ByteArray) {
        if (net.queue.size >= 3) return // تخطَّ القديم عند بطء الشبكة
        net.execute {
            try {
                val hint = if (cfg.source == "auto") null else cfg.source
                val r = WhisperClient.transcribe(pcm, cfg, hint) ?: return@execute
                val code = Langs.fromWhisper(r.lang) ?: hint
                if (code != null) handle(r.text, code)
                else langId.identifyLanguage(r.text).addOnSuccessListener { tag -> handle(r.text, tag) }
            } catch (e: java.net.UnknownHostException) { err("لا يوجد اتصال بالإنترنت")
            } catch (e: java.net.SocketTimeoutException) { err("انتهت مهلة الاتصال بالخدمة")
            } catch (e: IOException) { err(e.message ?: "خطأ في الشبكة")
            } catch (e: Exception) { err("خطأ: ${e.message}") }
        }
    }

    private fun handle(text: String, src: String) {
        val tgt = cfg.target
        val s = TranslateLanguage.fromLanguageTag(src)
        if (s == null) { publish(text, "", src, tgt); return }
        translators.translate(text, s, tgt,
            { publish(text, it, s, tgt) },
            { err(it); publish(text, "", s, tgt) },
            { say(it) })
    }

    // ───────────────────────── الواجهة العائمة ─────────────────────────

    private fun say(m: String) { Bus.status = m; ui.post { if (alive && overlayAdded) render(m, "", "") } }

    private fun err(m: String) {
        val now = SystemClock.elapsedRealtime()
        if (m == lastErr && now - lastErrT < 8000) return
        lastErr = m; lastErrT = now
        say(m)
    }

    private fun publish(orig: String, tr: String, src: String, tgt: String) {
        Bus.original = orig; Bus.translated = tr; Bus.stamp = System.nanoTime()
        ui.post { if (alive && overlayAdded) render(tr, orig, "${src.uppercase()} → ${tgt.uppercase()}") }
    }

    private fun dp(v: Int) = dpOf(this, v)

    @Suppress("DEPRECATION")
    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        chip = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#22D3EE")); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(Color.parseColor("#2622D3EE")); cornerRadius = dp(8).toFloat() }
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#CCFFFFFF"))
            setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener { stopSelf() }
        }
        trTv = TextView(this).apply {
            textSize = cfg.textSize.toFloat(); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        orTv = TextView(this).apply {
            textSize = cfg.textSize * 0.62f; setTextColor(Color.parseColor("#B8C0E0"))
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(chip)
            addView(View(this@CaptureService), LinearLayout.LayoutParams(0, 1, 1f))
            addView(close)
        }
        val a = cfg.bgAlpha * 255 / 100
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(a, 16, 20, 46), Color.argb(a, 34, 20, 72))).apply {
                cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(top)
            addView(trTv, LinearLayout.LayoutParams(-1, -2))
            addView(orTv, LinearLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = if (cfg.overlayY > 0) cfg.overlayY else dp(110) }

        var downY = 0f; var startY = 0
        root.setOnTouchListener { _, e -> // سحب الترجمة لأي مكان على الشاشة
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (startY - (e.rawY - downY)).toInt().coerceIn(0, resources.displayMetrics.heightPixels - dp(120))
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> cfg.overlayY = lp.y
            }
            true
        }
        wm.addView(root, lp)
        overlayAdded = true
    }

    private fun render(tr: String, orig: String, pair: String) {
        trTv.text = if (tr.isBlank()) orig else tr
        orTv.text = if (cfg.showOriginal && tr.isNotBlank()) orig else ""
        orTv.visibility = if (orTv.text.isEmpty()) View.GONE else View.VISIBLE
        chip.text = pair
        chip.visibility = if (pair.isBlank()) View.INVISIBLE else View.VISIBLE
        root.animate().cancel()
        if (root.visibility != View.VISIBLE) { root.alpha = 0f; root.visibility = View.VISIBLE }
        root.animate().alpha(1f).setDuration(180).start()
        trTv.alpha = 0.4f; trTv.translationY = dp(8).toFloat()
        trTv.animate().alpha(1f).translationY(0f).setDuration(200).start()
        ui.removeCallbacks(hideTask); ui.postDelayed(hideTask, 7000)
    }

    private fun renderPartial(p: String) {
        trTv.text = p; orTv.visibility = View.GONE; chip.visibility = View.INVISIBLE
        root.animate().cancel(); root.alpha = 1f; root.visibility = View.VISIBLE
        ui.removeCallbacks(hideTask); ui.postDelayed(hideTask, 7000)
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("live", "الترجمة الفورية", NotificationManager.IMPORTANCE_LOW))
        }
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), fl)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction("STOP"), fl)
        return NotificationCompat.Builder(this, "live")
            .setContentTitle("الترجمة الفورية تعمل")
            .setContentText("اضغط للفتح أو أوقفها من الزر")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).addAction(0, "إيقاف", stop).setOngoing(true).build()
    }

    override fun onDestroy() {
        running = false; alive = false
        Bus.running = false; Bus.level = 0f
        ui.removeCallbacks(hideTask)
        net.shutdownNow()
        try { record?.stop() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        if (overlayAdded) { try { wm.removeView(root) } catch (e: Exception) {}; overlayAdded = false }
        translators.close(); langId.close()
        super.onDestroy()
    }
}
