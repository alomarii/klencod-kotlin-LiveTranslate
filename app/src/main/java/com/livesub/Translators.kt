package com.livesub

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** مترجمات ML Kit مخزّنة لكل زوج لغات؛ يُحمَّل نموذج كل لغة مرة واحدة فقط */
class Translators {
    private val map = HashMap<String, Translator>()
    private val ready: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    @Synchronized
    private fun get(src: String, tgt: String): Translator = map.getOrPut("$src>$tgt") {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        )
    }

    fun translate(text: String, src: String, tgt: String, ok: (String) -> Unit, err: (String) -> Unit, info: (String) -> Unit) {
        if (src == tgt) { ok(text); return }
        val key = "$src>$tgt"
        val t = get(src, tgt)
        if (!ready.contains(key)) info("جارٍ تحميل نموذج الترجمة (مرة واحدة)…")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready.add(key)
                t.translate(text)
                    .addOnSuccessListener { r -> ok(r) }
                    .addOnFailureListener { err("فشلت الترجمة") }
            }
            .addOnFailureListener { err("تعذّر تحميل نموذج الترجمة — تأكد من الإنترنت") }
    }

    /** يُستدعى من خيط خلفي فقط */
    fun prefetchBlocking(src: String, tgt: String, seconds: Int): Boolean {
        if (src == tgt) return true
        val t = get(src, tgt)
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ok.set(true); latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(seconds.toLong(), TimeUnit.SECONDS)
        if (ok.get()) ready.add("$src>$tgt")
        return ok.get()
    }

    @Synchronized
    fun close() { map.values.forEach { it.close() }; map.clear() }
}
