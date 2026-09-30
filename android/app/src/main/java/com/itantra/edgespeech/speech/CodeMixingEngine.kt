package com.itantra.edgespeech.speech

import com.itantra.edgespeech.protocol.Language
import java.util.Locale

enum class DetectedScript {
    DEVANAGARI,
    LATIN_HINGLISH,
    PURE_ENGLISH,
    OTHER_INDIC
}

data class CodeMixingAnalysis(
    val detectedScript: DetectedScript,
    val isCodeMixed: Boolean,
    val codeMixingRatio: Float,
    val normalizedText: String,
    val ttsOptimizedText: String,
    val recommendedTtsLanguage: Language
)

object CodeMixingEngine {

    // Common Romanized Hinglish to Devanagari mapping for high-frequency spoken words
    private val HINGLISH_DICTIONARY = mapOf(
        "bhai" to "भाई",
        "bhaiya" to "भैया",
        "jaldi" to "जल्दी",
        "madad" to "मदद",
        "bachao" to "बचाओ",
        "kaha" to "कहाँ",
        "kahan" to "कहाँ",
        "hai" to "है",
        "hain" to "हैं",
        "ho" to "हो",
        "raha" to "रहा",
        "rahe" to "रहे",
        "rahi" to "रही",
        "aao" to "आओ",
        "aa" to "आ",
        "rha" to "रहा",
        "rhi" to "रही",
        "bhejo" to "भेजो",
        "bhej" to "भेज",
        "chahiye" to "चाहिए",
        "karo" to "करो",
        "kar" to "कर",
        "paas" to "पास",
        "rasta" to "रास्ता",
        "pul" to "पुल",
        "toot" to "टूट",
        "tut" to "टूट",
        "gaya" to "गया",
        "gaye" to "गए",
        "gayi" to "गई",
        "hum" to "हम",
        "ham" to "हम",
        "aap" to "आप",
        "tum" to "तुम",
        "sab" to "सब",
        "theek" to "ठीक",
        "thik" to "ठीक",
        "chot" to "चोट",
        "ghayal" to "घायल",
        "khoon" to "खून",
        "rok" to "रोक",
        "ruko" to "रुको",
        "ruk" to "रुक",
        "pani" to "पानी",
        "paani" to "पानी",
        "khana" to "खाना",
        "goli" to "गोली",
        "goliya" to "गोलियां",
        "goliyan" to "गोलियां",
        "khatam" to "खत्म",
        "dost" to "दोस्त",
        "kripya" to "कृपया",
        "turant" to "तुरंत",
        "chowki" to "चौकी",
        "chouki" to "चौकी",
        "khatra" to "खतरा",
        "surakshit" to "सुरक्षित",
        "aur" to "और",
        "par" to "पर",
        "me" to "में",
        "mein" to "में",
        "se" to "से",
        "ko" to "को",
        "ka" to "का",
        "ke" to "के",
        "ki" to "की",
        "kya" to "क्या",
        "kyu" to "क्यों",
        "kyun" to "क्यों",
        "nahi" to "नहीं",
        "nhi" to "नहीं",
        "yaha" to "यहाँ",
        "waha" to "वहाँ",
        "idhar" to "इधर",
        "udhar" to "उधर",
        "aage" to "आगे",
        "piche" to "पीछे",
        "upar" to "ऊपर",
        "niche" to "नीचे",
        "kitna" to "कितना",
        "kitne" to "कितने",
        "time" to "समय",
        "log" to "लोग",
        "jawan" to "जवान",
        "fas" to "फंस",
        "aag" to "आग",
        "dhua" to "धुआं",
        "dhuan" to "धुआं"
    )

    // Colloquial abbreviations and slang normalization
    private val SLANG_NORMALIZER = mapOf(
        "pls" to "please",
        "plz" to "please",
        "asap" to "as soon as possible",
        "r" to "are",
        "u" to "you",
        "ur" to "your",
        "thx" to "thank you",
        "tysm" to "thank you so much",
        "msg" to "message",
        "wbu" to "what about you",
        "hru" to "how are you"
    )

    // Standard technical and emergency terms that should remain in English
    private val PRESERVED_ENGLISH_TERMS = setOf(
        "help", "doctor", "ambulance", "hospital", "medic", "medevac", "casualty", "casualties",
        "police", "fire", "emergency", "alert", "sos", "attack", "bridge", "road", "sector",
        "post", "camp", "base", "grid", "point", "convoy", "truck", "radio", "battery", "gps",
        "signal", "officer", "captain", "unit", "backup", "first aid", "reinforcement", "coordinates"
    )

    /**
     * Analyzes code-mixed, Hinglish, or native text to determine:
     * 1. Script type
     * 2. Code-mixing ratio
     * 3. TTS-optimized pronunciation string
     * 4. Recommended TTS engine language
     */
    fun analyzeAndOptimize(text: String, fallbackLanguage: Language = Language.HINDI): CodeMixingAnalysis {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return CodeMixingAnalysis(
                detectedScript = DetectedScript.PURE_ENGLISH,
                isCodeMixed = false,
                codeMixingRatio = 0f,
                normalizedText = "",
                ttsOptimizedText = "",
                recommendedTtsLanguage = fallbackLanguage
            )
        }

        var devanagariCount = 0
        var latinCount = 0
        var otherIndicCount = 0

        for (ch in clean) {
            when (ch.code) {
                in 0x0900..0x097F -> devanagariCount++
                in 0x0041..0x005A, in 0x0061..0x007A -> latinCount++
                in 0x0980..0x0D7F -> otherIndicCount++
            }
        }

        val totalLetters = devanagariCount + latinCount + otherIndicCount
        if (totalLetters == 0) {
            return CodeMixingAnalysis(
                detectedScript = DetectedScript.PURE_ENGLISH,
                isCodeMixed = false,
                codeMixingRatio = 0f,
                normalizedText = clean,
                ttsOptimizedText = clean,
                recommendedTtsLanguage = fallbackLanguage
            )
        }

        val words = clean.split(Regex("\\s+"))
        var hinglishWordCount = 0
        var englishWordCount = 0
        var devanagariWordCount = 0

        val normalizedWords = mutableListOf<String>()
        val ttsWords = mutableListOf<String>()

        for (w in words) {
            val cleanWord = w.lowercase(Locale.ROOT).replace(Regex("[^a-zA-Z0-9\u0900-\u097F]"), "")
            val slangExpanded = SLANG_NORMALIZER[cleanWord] ?: w

            if (HINGLISH_DICTIONARY.containsKey(cleanWord)) {
                hinglishWordCount++
                normalizedWords.add(slangExpanded)
                // For TTS: replace Romanized Hindi word with Devanagari for authentic Hindi voice
                val deva = HINGLISH_DICTIONARY[cleanWord] ?: slangExpanded
                ttsWords.add(deva)
            } else if (PRESERVED_ENGLISH_TERMS.contains(cleanWord)) {
                englishWordCount++
                normalizedWords.add(slangExpanded)
                ttsWords.add(slangExpanded)
            } else if (cleanWord.any { it.code in 0x0900..0x097F }) {
                devanagariWordCount++
                normalizedWords.add(slangExpanded)
                ttsWords.add(slangExpanded)
            } else {
                if (cleanWord.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                    englishWordCount++
                }
                normalizedWords.add(slangExpanded)
                ttsWords.add(slangExpanded)
            }
        }

        val normalizedText = normalizedWords.joinToString(" ")
        val ttsOptimizedText = ttsWords.joinToString(" ")

        val isHinglish = hinglishWordCount > 0
        val isCodeMixed = (isHinglish && englishWordCount > 0) || (devanagariWordCount > 0 && englishWordCount > 0)
        val codeMixingRatio = if (words.isNotEmpty()) {
            (minOf(hinglishWordCount + devanagariWordCount, englishWordCount).toFloat() / words.size) * 2f
        } else 0f

        val detectedScript = when {
            otherIndicCount > (totalLetters * 0.4f) -> DetectedScript.OTHER_INDIC
            devanagariCount > (totalLetters * 0.4f) -> DetectedScript.DEVANAGARI
            isHinglish -> DetectedScript.LATIN_HINGLISH
            else -> DetectedScript.PURE_ENGLISH
        }

        // Recommend TTS language:
        // If Hinglish or Devanagari dominates, use Hindi TTS (since we converted Hinglish phonetics to Devanagari)
        // If pure English, use Indian English TTS
        val recommendedLang = when (detectedScript) {
            DetectedScript.DEVANAGARI, DetectedScript.LATIN_HINGLISH -> Language.HINDI
            DetectedScript.PURE_ENGLISH -> Language.ENGLISH
            DetectedScript.OTHER_INDIC -> fallbackLanguage
        }

        return CodeMixingAnalysis(
            detectedScript = detectedScript,
            isCodeMixed = isCodeMixed,
            codeMixingRatio = codeMixingRatio.coerceIn(0f, 1f),
            normalizedText = normalizedText,
            ttsOptimizedText = ttsOptimizedText,
            recommendedTtsLanguage = recommendedLang
        )
    }
}
