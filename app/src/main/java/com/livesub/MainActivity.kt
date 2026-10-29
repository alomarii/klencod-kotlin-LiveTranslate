package com.livesub

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

private class Check(val title: String, val ok: () -> Boolean, val fix: () -> Unit)
private class LangItem(val code: String, val label: String) { override fun toString() = label }

class MainActivity : ComponentActivity() {
    private lateinit var cfg: Cfg
    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<TextView>()
    private lateinit var pages: List<View>
    private var tab = -1
    private lateinit var pulse: PulseView
    private lateinit var wave: WaveView
    private lateinit var statusTv: TextView
    private lateinit var capTr: TextView
    private lateinit var capOr: TextView
    private lateinit var srcName: TextView
    private lateinit var tgtName: TextView
    private val checks = ArrayList<Triple<Check, TextView, TextView>>()
    private var lastStamp = 0L
    private var askedNotif = false
    private var downloading = false
    private val translators = Translators()

    private val cWhite = Color.WHITE
    private val cSub = Color.parseColor("#A8B0D0")
    private val cCy = Color.parseColor("#22D3EE")
    private val cVi = Color.parseColor("#8B5CF6")
    private val cOk = Color.parseColor("#34D399")
    private val cBad = Color.parseColor("#FB7185")

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.RECORD_AUDIO)) toggle() else toast("إذن الميكروفون مطلوب لالتقاط الصوت")
    }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) startSvc(r.data, r.resultCode)
        else toast("لم يتم السماح بالتقاط الصوت")
    }

    // ───────────────────────── أدوات واجهة ─────────────────────────
    private fun dp(v: Int) = dpOf(this, v)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun <T : View> T.withLp(w: Int = -1, h: Int = -2, s: Int = 0, t: Int = 0, e: Int = 0, b: Int = 0, g: Int = 0): T {
        layoutParams = LinearLayout.LayoutParams(w, h).apply {
            marginStart = dp(s); marginEnd = dp(e); topMargin = dp(t); bottomMargin = dp(b)
            if (g != 0) gravity = g
        }
        return this
    }

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun grad(r: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cCy, cVi)).apply { cornerRadius = dp(r).toFloat() }

    private fun tv(t: String, sp: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun card(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1AFFFFFF"), 20, Color.parseColor("#26FFFFFF"))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        v.forEach { addView(it) }
    }

    private fun btn(t: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(cWhite); setPadding(dp(16), dp(12), dp(16), dp(12))
        background = if (primary) grad(14) else rounded(Color.parseColor("#26FFFFFF"), 14, Color.parseColor("#33FFFFFF"))
        isClickable = true
        setOnClickListener { v ->
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
            onClick()
        }
    }

    private fun edit(hint: String, value: String, pwd: Boolean = false, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(cWhite); setHintTextColor(Color.parseColor("#6B7396")); textSize = 14f
        background = rounded(Color.parseColor("#33000000"), 12, Color.parseColor("#26FFFFFF"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = InputType.TYPE_CLASS_TEXT or (if (pwd) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        textDirection = View.TEXT_DIRECTION_LTR
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s.toString().trim()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun segmented(opts: List<String>, sel: Int, onSel: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#33000000"), 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val items = ArrayList<TextView>()
        fun paint(s: Int) {
            items.forEachIndexed { i, t ->
                t.background = if (i == s) grad(11) else null
                t.setTextColor(if (i == s) cWhite else cSub)
            }
        }
        opts.forEachIndexed { i, o ->
            val t = tv(o, 13f, cSub, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { paint(i); onSel(i) }
            }
            items.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint(sel)
        return row
    }

    private fun seek(label: String, lo: Int, hi: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val t = tv("$label: ${fmt(value)}", 13f, cSub)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = value - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { val v = p + lo; t.text = "$label: ${fmt(v)}"; onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(sb) }
    }

    private fun page(vararg v: View): ScrollView = ScrollView(this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            v.forEach { addView(it) }
        })
    }

    private fun pickLang(title: String, auto: Boolean, onPick: (String) -> Unit) {
        val items = ArrayList<LangItem>()
        if (auto) items.add(LangItem("auto", Langs.label("auto")))
        Langs.all.forEach { items.add(LangItem(it, Langs.label(it))) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        val search = EditText(this).apply { hint = "بحث…"; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT }
        val list = ListView(this)
        list.adapter = adapter
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("إلغاء", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { adapter.filter.filter(s) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ -> adapter.getItem(pos)?.let { onPick(it.code) }; dlg.dismiss() }
        dlg.show()
    }

    // ───────────────────────── الصفحات ─────────────────────────
    private fun langColumn(caption: String, onClick: () -> Unit): Pair<View, TextView> {
        val name = tv("", 17f, cWhite, true).apply { gravity = Gravity.CENTER }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#26000000"), 14, Color.parseColor("#26FFFFFF"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(tv(caption, 11f, cSub).apply { gravity = Gravity.CENTER })
            addView(name)
            setOnClickListener { onClick() }
        }
        return Pair(col, name)
    }

    private fun checkRow(c: Check): View {
        val dot = tv("●", 16f, cBad)
        val act = tv("منح", 13f, cCy, true)
        checks.add(Triple(c, dot, act))
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(dot)
            addView(tv(c.title, 14f, cWhite), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(act)
            setOnClickListener { if (!c.ok()) c.fix() }
        }
    }

    private fun homePage(): View {
        val logo = tv("文A", 17f, cWhite, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cCy, cVi)).apply { shape = GradientDrawable.OVAL }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("الترجمة الفورية", 22f, cWhite, true))
            addView(tv("ترجم أي فيديو لحظة بلحظة", 13f, cSub))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(titles, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        pulse = PulseView(this).apply { setOnClickListener { toggle() } }.withLp(h = dp(230), t = 6)
        statusTv = tv("اضغط الزر للبدء", 15f, cSub).apply { gravity = Gravity.CENTER }
        wave = WaveView(this).withLp(h = dp(54), t = 10, b = 14)

        val (srcCol, sn) = langColumn("لغة الصوت") { pickLang("لغة الصوت", cfg.engine == "cloud") { cfg.source = it; refreshLang() } }
        val (tgtCol, tn) = langColumn("الترجمة إلى") { pickLang("الترجمة إلى", false) { cfg.target = it; refreshLang() } }
        srcName = sn; tgtName = tn
        val swap = tv("⇄", 24f, cCy, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                if (cfg.source == "auto") toast("اختر لغة صوت محددة أولاً")
                else { val a = cfg.source; cfg.source = cfg.target; cfg.target = a; refreshLang() }
            }
        }
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(srcCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(swap)
            addView(tgtCol, LinearLayout.LayoutParams(0, -2, 1f))
        }

        capTr = tv("ستظهر الترجمة هنا", 20f, cSub, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        capOr = tv("", 13f, cSub).apply { gravity = Gravity.CENTER }
        val capCard = card(tv("الترجمة المباشرة", 12f, cCy, true), capTr, capOr).withLp(t = 14)

        val rows = ArrayList<View>()
        rows.add(tv("جاهزية التطبيق", 12f, cCy, true))
        rows.add(checkRow(Check("إذن الصوت (الميكروفون)", { has(Manifest.permission.RECORD_AUDIO) },
            { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })))
        rows.add(checkRow(Check("الظهور فوق التطبيقات (لعرض الترجمة)", { Settings.canDrawOverlays(this) }, { openOverlaySettings() })))
        if (Build.VERSION.SDK_INT >= 33) rows.add(checkRow(Check("الإشعارات", { has(Manifest.permission.POST_NOTIFICATIONS) },
            { permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })))
        rows.add(checkRow(Check("مفتاح Whisper API (للمحرك السحابي)", { cfg.engine != "cloud" || cfg.apiKey.isNotBlank() }, { selectTab(1) })))
        val checkCard = card(*rows.toTypedArray()).withLp(t = 14)

        return page(
            header, pulse, statusTv, wave,
            card(langRow), capCard, checkCard
        )
    }

    private fun settingsPage(): View {
        val cloudBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("مفتاح API", 12f, cSub))
            addView(edit("sk-… أو gsk_…", cfg.apiKey, true) { cfg.apiKey = it; refreshChecks() }.withLp(t = 4, b = 8))
            addView(tv("عنوان الخدمة", 12f, cSub))
            addView(edit("Endpoint", cfg.endpoint) { cfg.endpoint = it }.withLp(t = 4, b = 8))
            addView(tv("النموذج", 12f, cSub))
            addView(edit("whisper-1", cfg.model) { cfg.model = it }.withLp(t = 4))
            visibility = if (cfg.engine == "cloud") View.VISIBLE else View.GONE
        }
        val engDesc = tv(
            if (cfg.engine == "cloud") "تعرّف تلقائي على أي لغة بدقة عالية. يحتاج إنترنت ومفتاح API (OpenAI أو Groq أو أي خدمة متوافقة)."
            else "يعمل بدون إنترنت بعد تحميل نموذج اللغة مرة واحدة. يتعرّف على اللغة التي تختارها فقط.", 12f, cSub)
        val engSeg = segmented(listOf("سحابي (Whisper)", "بدون إنترنت (Vosk)"), if (cfg.engine == "cloud") 0 else 1) { i ->
            cfg.engine = if (i == 0) "cloud" else "offline"
            if (i == 1 && cfg.source == "auto") cfg.source = "en"
            cloudBox.visibility = if (i == 0) View.VISIBLE else View.GONE
            engDesc.text = if (i == 0) "تعرّف تلقائي على أي لغة بدقة عالية. يحتاج إنترنت ومفتاح API (OpenAI أو Groq أو أي خدمة متوافقة)."
            else "يعمل بدون إنترنت بعد تحميل نموذج اللغة مرة واحدة. يتعرّف على اللغة التي تختارها فقط."
            refreshLang(); refreshChecks()
        }
        val engineCard = card(tv("محرك التعرف على الصوت", 12f, cCy, true), engSeg.withLp(t = 8, b = 8), engDesc, cloudBox.withLp(t = 12))

        val capDesc = tv(
            if (Build.VERSION.SDK_INT >= 29) "يلتقط صوت أي تطبيق مباشرة (يوتيوب، تيك توك، إنستغرام…). بعض التطبيقات تمنع ذلك، وعندها استخدم الميكروفون."
            else "جهازك أقدم من أندرويد 10، لذلك يعمل وضع الميكروفون فقط: قرّب الهاتف من مصدر الصوت.", 12f, cSub)
        val capSeg: View = if (Build.VERSION.SDK_INT >= 29)
            segmented(listOf("صوت التطبيقات", "الميكروفون"), if (cfg.capture == "system") 0 else 1) { i -> cfg.capture = if (i == 0) "system" else "mic" }
        else tv("وضع الميكروفون", 14f, cCy, true)
        val capCard = card(tv("مصدر الصوت", 12f, cCy, true), capSeg.withLp(t = 8, b = 8), capDesc).withLp(t = 14)

        val preview = tv("مرحباً بك · Hello", cfg.textSize.toFloat(), cWhite, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun stylePreview() {
            preview.textSize = cfg.textSize.toFloat()
            preview.background = rounded(Color.argb(cfg.bgAlpha * 255 / 100, 14, 18, 40), 18, Color.parseColor("#33FFFFFF"))
        }
        stylePreview()
        val sw = Switch(this).apply {
            text = "إظهار النص الأصلي مع الترجمة"; setTextColor(cWhite); isChecked = cfg.showOriginal
            setOnCheckedChangeListener { _, c -> cfg.showOriginal = c }
        }
        val look = card(
            tv("شكل الترجمة", 12f, cCy, true),
            preview.withLp(t = 8, b = 10),
            seek("حجم الخط", 14, 34, cfg.textSize, { "$it" }) { cfg.textSize = it; stylePreview() },
            seek("شفافية الخلفية", 30, 100, cfg.bgAlpha, { "$it%" }) { cfg.bgAlpha = it; stylePreview() }.withLp(t = 8),
            sw.withLp(t = 10),
            tv("يمكنك سحب الترجمة بإصبعك لأي مكان على الشاشة أثناء العمل.", 12f, cSub).withLp(t = 8)
        ).withLp(t = 14)

        val dl = btn("تحميل النماذج الآن (مرة واحدة)", true) {
            if (downloading) toast("التحميل جارٍ…")
            else { downloading = true; thread { try { prefetchAll() } finally { downloading = false } } }
        }
        val modelCard = card(
            tv("النماذج", 12f, cCy, true),
            tv("يُنصح بتحميل نماذج اللغة والترجمة الآن وأنت متصل بالإنترنت، لتعمل الترجمة فوراً لاحقاً. يتابع التقدم في الشاشة الرئيسية.", 12f, cSub).withLp(t = 6, b = 10),
            dl
        ).withLp(t = 14)
        return page(tv("الإعدادات", 22f, cWhite, true).withLp(b = 12), engineCard, capCard, look, modelCard,
            tv("تُطبَّق التغييرات عند بدء الترجمة من جديد.", 12f, cSub).withLp(t = 12))
    }

    private fun aboutPage(): View {
        val logo = tv("文A", 30f, cWhite, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cCy, cVi)).apply { shape = GradientDrawable.OVAL }
        }.withLp(w = dp(90), h = dp(90), t = 10, g = Gravity.CENTER_HORIZONTAL)
        val dev = card(
            tv("برمجة وتطوير", 12f, cCy, true),
            tv("محمد العمري", 22f, cWhite, true).withLp(t = 4),
            tv("رقم التواصل: 730215825", 15f, cSub).withLp(t = 4, b = 12),
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btn("اتصال", true) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:730215825"))) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(btn("نسخ الرقم") {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("phone", "730215825"))
                    toast("تم نسخ الرقم")
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            }
        ).withLp(t = 18)
        val info = card(
            tv("ماذا يفعل التطبيق؟", 12f, cCy, true),
            tv("• يلتقط صوت أي فيديو في أي تطبيق تواصل ويترجمه لحظياً فوق الشاشة.\n" +
                "• يدعم عشرات اللغات للترجمة، وتعمل الترجمة نفسها على الجهاز بعد تحميل نموذج اللغة.\n" +
                "• محرك سحابي بكشف تلقائي للغة، ومحرك بدون إنترنت.\n" +
                "• ترجمة قابلة للسحب مع تحكم بالحجم والشفافية.", 13f, cWhite).withLp(t = 8)
        ).withLp(t = 14)
        val notes = card(
            tv("ملاحظات مهمة", 12f, cCy, true),
            tv("• التقاط صوت التطبيقات يتطلب أندرويد 10 أو أحدث؛ في الأقدم يُستخدم الميكروفون.\n" +
                "• بعض التطبيقات تمنع التقاط صوتها؛ استخدم وضع الميكروفون عندها.\n" +
                "• نماذج اللغات تُحمَّل مرة واحدة وتحتاج إنترنت.", 13f, cSub).withLp(t = 8)
        ).withLp(t = 14)
        return page(logo, tv("الترجمة الفورية", 24f, cWhite, true).apply { gravity = Gravity.CENTER }.withLp(t = 12),
            tv("الإصدار 2.0", 13f, cSub).apply { gravity = Gravity.CENTER }.withLp(t = 2),
            dev, info, notes,
            tv("© محمد العمري", 12f, cSub).apply { gravity = Gravity.CENTER }.withLp(t = 18))
    }

    // ───────────────────────── المنطق ─────────────────────────
    private fun refreshLang() {
        srcName.text = if (cfg.source == "auto") "تلقائي" else Langs.native(cfg.source)
        tgtName.text = Langs.native(cfg.target)
    }

    private fun refreshChecks() {
        for ((c, dot, act) in checks) {
            val ok = c.ok()
            dot.setTextColor(if (ok) cOk else cBad)
            act.text = if (ok) "جاهز" else "منح"
            act.setTextColor(if (ok) cOk else cCy)
        }
    }

    private fun openOverlaySettings() {
        toast("فعّل «الظهور فوق التطبيقات» ثم عد واضغط ابدأ")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun toggle() {
        if (Bus.running) { stopService(Intent(this, CaptureService::class.java)); return }
        val perms = ArrayList<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && !askedNotif) {
            askedNotif = true; perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) { permLauncher.launch(perms.toTypedArray()); return }
        if (!Settings.canDrawOverlays(this)) { openOverlaySettings(); return }
        if (cfg.engine == "cloud" && cfg.apiKey.isBlank()) {
            toast("أدخل مفتاح API في الإعدادات أو اختر المحرك بدون إنترنت"); selectTab(1); return
        }
        if (cfg.engine == "offline" && cfg.source == "auto") { cfg.source = "en"; refreshLang() }
        if (cfg.capture == "system" && Build.VERSION.SDK_INT >= 29) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } else startSvc(null, 0)
    }

    private fun startSvc(data: Intent?, code: Int) {
        val i = Intent(this, CaptureService::class.java).putExtra("code", code)
        if (data != null) i.putExtra("data", data)
        ContextCompat.startForegroundService(this, i)
        toast("بدأت الترجمة — افتح أي فيديو الآن")
    }

    private fun prefetchAll() {
        val tgt = cfg.target
        var src = "en"
        if (cfg.engine == "offline") {
            src = if (cfg.source == "auto") "en" else cfg.source
            val d = ModelManager.ensureVosk(this, src, { Bus.status = it }) { true }
            if (d == null) { val m = Bus.status; h.post { toast(m) }; return }
        }
        Bus.status = "تحميل نموذج الترجمة…"
        val ok = translators.prefetchBlocking(src, tgt, 300)
        Bus.status = ""
        h.post { toast(if (ok) "تم تجهيز كل شيء ✓" else "تعذّر تحميل نموذج الترجمة — تحقق من الإنترنت") }
    }

    private fun selectTab(i: Int) {
        if (i == tab) return
        tab = i
        content.removeAllViews()
        val p = pages[i]
        p.alpha = 0f
        content.addView(p)
        p.animate().alpha(1f).setDuration(220).start()
        tabViews.forEachIndexed { k, t -> t.setTextColor(if (k == i) cCy else cSub) }
        refreshChecks()
    }

    private val ticker = object : Runnable {
        override fun run() {
            pulse.running = Bus.running; pulse.level = Bus.level
            wave.active = Bus.running; wave.level = Bus.level; wave.tick()
            statusTv.text = if (Bus.status.isNotBlank()) Bus.status else if (Bus.running) "تعمل الترجمة…" else "اضغط الزر للبدء"
            if (Bus.stamp != lastStamp) {
                lastStamp = Bus.stamp
                capTr.setTextColor(cWhite)
                capTr.text = if (Bus.translated.isNotBlank()) Bus.translated else Bus.original
                capOr.text = if (Bus.translated.isNotBlank()) Bus.original else ""
                capTr.alpha = 0.3f; capTr.animate().alpha(1f).setDuration(200).start()
            }
            h.postDelayed(this, 40)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Cfg(this)
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0A0F24"), Color.parseColor("#1A1145"), Color.parseColor("#0A0F24"))))
        content = FrameLayout(this)
        pages = listOf(homePage(), settingsPage(), aboutPage())
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ColorDrawable(Color.parseColor("#CC0A0F24"))
            setPadding(0, dp(4), 0, dp(4))
        }
        listOf("🏠\nالرئيسية", "⚙️\nالإعدادات", "ℹ️\nحول").forEachIndexed { i, t ->
            val v = tv(t, 12f, cSub, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)); setOnClickListener { selectTab(i) }
            }
            tabViews.add(v)
            bar.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        refreshLang()
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshChecks(); refreshLang()
        h.post(ticker)
    }

    override fun onPause() {
        h.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        translators.close()
        super.onDestroy()
    }
}