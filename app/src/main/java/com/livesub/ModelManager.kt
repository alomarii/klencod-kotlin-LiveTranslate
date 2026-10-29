package com.livesub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** تنزيل نماذج Vosk تلقائياً من القائمة الرسمية لكل لغة */
object ModelManager {
    private const val LIST_URL = "https://alphacephei.com/vosk/models/model-list.json"

    fun ensureVosk(ctx: Context, ml: String, progress: (String) -> Unit, alive: () -> Boolean): File? {
        val prefs = ctx.getSharedPreferences("models", Context.MODE_PRIVATE)
        val saved = prefs.getString("vosk_$ml", null)
        if (saved != null) { val f = File(saved); if (f.isDirectory) return f }
        return try {
            progress("جلب قائمة نماذج اللغات…")
            val arr = JSONArray(httpGet(LIST_URL))
            val code = Langs.vosk(ml)
            var best: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("obsolete") == "true") continue
                val l = o.optString("lang")
                if (l != code && !l.startsWith("$code-")) continue
                val b = best
                if (b == null || rank(o) < rank(b)) best = o
            }
            val m = best
            if (m == null) {
                progress("لا يوجد نموذج بلا إنترنت لهذه اللغة — استخدم المحرك السحابي")
                return null
            }
            val name = m.optString("name")
            val mb = m.optLong("size") / 1_048_576
            val zip = File(ctx.cacheDir, "$name.zip")
            if (!download(m.optString("url"), zip, "تحميل نموذج اللغة ($mb MB)", progress, alive)) return null
            progress("فك ضغط النموذج…")
            val dest = File(ctx.filesDir, "vosk/$name")
            dest.deleteRecursively()
            unzip(zip, dest)
            zip.delete()
            val root = findRoot(dest)
            if (root == null) { progress("ملف النموذج غير صالح"); null }
            else { prefs.edit().putString("vosk_$ml", root.absolutePath).apply(); root }
        } catch (e: Exception) {
            progress("تعذّر تجهيز نموذج اللغة — تحقق من الإنترنت")
            null
        }
    }

    private fun rank(o: JSONObject): Long =
        (if (o.optString("type") == "small") 0L else 1_000_000_000_000L) + o.optLong("size")

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "LiveTranslate")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun download(url: String, out: File, title: String, progress: (String) -> Unit, alive: () -> Boolean): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        try {
            val total = c.contentLengthLong
            var done = 0L; var last = -1
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val b = ByteArray(65536)
                    while (alive()) {
                        val n = i.read(b); if (n < 0) break
                        o.write(b, 0, n); done += n
                        val pc = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pc != last && pc % 3 == 0) { last = pc; progress("$title… $pc%") }
                    }
                }
            }
            return alive()
        } finally { c.disconnect() }
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(base)) throw SecurityException("bad zip")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    private fun findRoot(d: File): File? {
        if (File(d, "conf").isDirectory || File(d, "am").isDirectory) return d
        for (c in d.listFiles() ?: emptyArray()) if (c.isDirectory) findRoot(c)?.let { return it }
        return null
    }
}
