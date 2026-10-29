package com.livesub

import android.content.Context
import java.util.Locale

class Cfg(ctx: Context) {
    private val p = ctx.getSharedPreferences("livetranslate", Context.MODE_PRIVATE)
    private fun s(k: String, d: String): String = p.getString(k, d) ?: d
    private fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    /** offline = Vosk بدون إنترنت، cloud = Whisper API */
    var engine: String
        get() = s("engine", "offline")
        set(v) = put("engine", v)
    /** system = صوت التطبيقات (أندرويد 10+)، mic = الميكروفون */
    var capture: String
        get() = s("capture", "system")
        set(v) = put("capture", v)
    var source: String
        get() = s("source", "en")
        set(v) = put("source", v)
    var target: String
        get() {
            val d = Locale.getDefault().language
            val def = if (d != "en" && Langs.all.contains(d)) d else "ar"
            return s("target", def)
        }
        set(v) = put("target", v)
    var apiKey: String
        get() = s("key", "")
        set(v) = put("key", v)
    var endpoint: String
        get() = s("endpoint", "https://api.openai.com/v1/audio/transcriptions")
        set(v) = put("endpoint", v)
    var model: String
        get() = s("model", "whisper-1")
        set(v) = put("model", v)
    var textSize: Int
        get() = p.getInt("ts", 20)
        set(v) { p.edit().putInt("ts", v).apply() }
    var bgAlpha: Int
        get() = p.getInt("ba", 80)
        set(v) { p.edit().putInt("ba", v).apply() }
    var overlayY: Int
        get() = p.getInt("oy", 0)
        set(v) { p.edit().putInt("oy", v).apply() }
    var showOriginal: Boolean
        get() = p.getBoolean("so", true)
        set(v) { p.edit().putBoolean("so", v).apply() }
}
