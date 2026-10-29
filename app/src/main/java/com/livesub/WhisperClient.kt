package com.livesub

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Whisper عبر واجهة متوافقة مع OpenAI (كشف تلقائي للغة) */
object WhisperClient {
    class Asr(val text: String, val lang: String?)

    private fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    fun transcribe(pcm: ByteArray, cfg: Cfg, hint: String?): Asr? {
        val b = "----lt${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        fun field(n: String, v: String) =
            out.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
        field("model", cfg.model); field("response_format", "verbose_json"); field("temperature", "0")
        if (hint != null) field("language", hint)
        out.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav(pcm)); out.write("\r\n--$b--\r\n".toByteArray())

        val c = URL(cfg.endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { it.write(out.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(
                when (code) {
                    401, 403 -> "مفتاح API غير صحيح"
                    429 -> "تجاوزت حد الاستخدام — حاول لاحقاً"
                    else -> "خطأ من الخدمة ($code)"
                }
            )
            val j = JSONObject(body)
            val text = j.optString("text").trim()
            if (text.length < 2) return null
            val segs = j.optJSONArray("segments")
            if (segs != null && segs.length() > 0) {
                var allSilent = true
                for (i in 0 until segs.length())
                    if (segs.getJSONObject(i).optDouble("no_speech_prob", 0.0) < 0.6) allSilent = false
                if (allSilent) return null
            }
            val lg = j.optString("language")
            return Asr(text, if (lg.isBlank()) null else lg)
        } finally { c.disconnect() }
    }
}
