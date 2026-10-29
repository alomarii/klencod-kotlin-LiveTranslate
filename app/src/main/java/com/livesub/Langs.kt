package com.livesub

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

object Langs {
    /** كل اللغات التي تدعمها الترجمة على الجهاز (ML Kit) */
    val all: List<String> by lazy {
        TranslateLanguage.getAllLanguages().sortedBy { Locale(it).getDisplayLanguage(Locale("ar")) }
    }

    fun native(code: String): String =
        if (code == "auto") "تلقائي" else Locale(code).getDisplayLanguage(Locale(code))

    fun label(code: String): String {
        if (code == "auto") return "اكتشاف تلقائي للغة"
        val n = native(code)
        val l = Locale(code).getDisplayLanguage(Locale("ar"))
        return if (n.equals(l, true)) n else "$n · $l"
    }

    private val whisperNames: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in Locale.getISOLanguages()) m[Locale(c).getDisplayLanguage(Locale.ENGLISH).lowercase()] = c
        m["myanmar"] = "my"; m["chinese"] = "zh"; m["haitian creole"] = "ht"; m["moldavian"] = "ro"
        m["valencian"] = "ca"; m["flemish"] = "nl"; m["castilian"] = "es"
        m
    }

    /** اسم اللغة من Whisper (مثل "english") إلى رمزها */
    fun fromWhisper(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val n = name.lowercase().trim()
        val code = if (n.length == 2) n else whisperNames[n]
        return if (code != null) TranslateLanguage.fromLanguageTag(code) else null
    }

    /** رمز اللغة في نماذج Vosk */
    fun vosk(ml: String): String = when (ml) {
        "zh" -> "cn"; "vi" -> "vn"; "el" -> "gr"; "kk" -> "kz"; "en" -> "en-us"; "tl" -> "tl-ph"
        else -> ml
    }
}
