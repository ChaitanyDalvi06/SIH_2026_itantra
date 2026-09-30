package com.itantra.edgespeech.speech

import java.util.Locale
import java.util.regex.Pattern

enum class TacticalIntent(val displayName: String, val icon: String) {
    EMERGENCY_SOS("Emergency SOS", "🚨"),
    MEDICAL_EVAC("Medical Evac", "🚑"),
    LOGISTICS_SUPPLY("Logistics / Supply", "📦"),
    SITUATION_REPORT("Situation Report", "📋"),
    ROUTE_NAVIGATION("Navigation / Route", "🧭"),
    RESOURCE_REQUEST("Reinforcement", "🛡️"),
    COMMAND_ORDER("Command / Order", "⚡"),
    GENERAL_COMMS("General Comms", "💬")
}

enum class UrgencyLevel(val score: Float, val label: String) {
    CRITICAL(1.0f, "CRITICAL"),
    HIGH(0.75f, "HIGH"),
    MEDIUM(0.5f, "MEDIUM"),
    LOW(0.2f, "LOW")
}

data class ExtractedEntity(
    val category: String, // "LOCATION", "CASUALTY", "GRID", "TIME", "COUNT"
    val value: String,
    val confidence: Float = 0.90f
)

data class ContextAnalysis(
    val intent: TacticalIntent,
    val urgency: UrgencyLevel,
    val urgencyScore: Float,
    val entities: List<ExtractedEntity>,
    val suggestedQuickReplies: List<String>,
    val codeMixingInfo: CodeMixingAnalysis? = null
)

object ContextEngine {

    // Multilingual & Hinglish Intent Keywords (Devanagari + Romanized Hinglish + English)
    private val INTENT_KEYWORDS = mapOf(
        TacticalIntent.EMERGENCY_SOS to listOf(
            "मदद", "बचाओ", "हमला", "आपातकाल", "खतरा", "आपातकालीन", "फायरिंग",
            "help", "emergency", "sos", "attack", "under fire", "danger", "critical",
            "ambush", "threat", "mayday", "distress",
            // Hinglish Romanized
            "madad", "bachao", "khatra", "attack hua", "firing ho rahi", "help chahiye",
            "fas gaye", "aag lag gayi", "emergency hai", "danger hai", "bachao bachao"
        ),
        TacticalIntent.MEDICAL_EVAC to listOf(
            "घायल", "चोट", "डॉक्टर", "एम्बुलेंस", "दवा", "अस्पताल", "खून", "मृत", "उपचार",
            "casualty", "injured", "medic", "doctor", "ambulance", "first aid", "bleeding",
            "evac", "medevac", "hospital", "stretcher", "wound",
            // Hinglish Romanized
            "ghayal", "chot lagi", "doctor bulao", "ambulance bhejo", "khoon beh raha",
            "casualty hai", "dawa chahiye", "hospital le jao", "stretcher chahiye", "injury hai"
        ),
        TacticalIntent.LOGISTICS_SUPPLY to listOf(
            "गोला बारूद", "राशन", "पानी", "ईंधन", "सप्लाई", "खाना", "बैटरी", "गोलियां",
            "ammo", "ammunition", "rations", "water", "fuel", "supply", "food",
            "batteries", "supplies", "diesel", "petrol", "equipment",
            // Hinglish Romanized
            "khana chahiye", "paani khatam", "pani khatam", "petrol nahi hai", "diesel bhejo",
            "goliya khatam", "ammo required", "ration bhejo", "battery low", "supplies bhejo"
        ),
        TacticalIntent.SITUATION_REPORT to listOf(
            "स्थिति", "सब ठीक", "सुरक्षित", "रिपोर्ट", "गश्त", "चौकी", "सन्नाटा", "शांति",
            "status", "sitrep", "all clear", "secure", "patrol", "report", "check-in",
            "situation", "perimeter", "routine", "observation",
            // Hinglish Romanized
            "sab thik hai", "sab theek hai", "all clear hai", "surakshit hain", "chowki safe hai",
            "shanti hai", "patrol chal rahi hai", "sab normal hai"
        ),
        TacticalIntent.ROUTE_NAVIGATION to listOf(
            "रास्ता", "पुल", "सड़क", "नदी", "मोड़", "उत्तर", "दक्षिण", "पूर्व", "पश्चिम", "ग्रिड",
            "route", "waypoint", "coordinates", "grid", "bridge", "road", "river",
            "north", "south", "east", "west", "turn", "map", "checkpoint",
            // Hinglish Romanized
            "rasta band hai", "bridge toot gaya", "pul gir gaya", "traffic jam", "kaun sa rasta",
            "road blocked", "pul toot gaya", "nadi paar"
        ),
        TacticalIntent.RESOURCE_REQUEST to listOf(
            "अतिरिक्त बल", "बैकअप", "सुदृढ़ीकरण", "सहायता टीम", "फोर्स",
            "reinforcement", "backup", "support", "additional troops", "air support",
            "heavy weapons", "reinforcements", "qrf",
            // Hinglish Romanized
            "backup bhejo", "madad bhejo", "aur log chahiye", "extra force", "team send karo"
        ),
        TacticalIntent.COMMAND_ORDER to listOf(
            "रुको", "आगे बढ़ो", "पीछे हटो", "तैनात", "आदेश", "पोजिशन लो", "कवर लो",
            "hold position", "advance", "retreat", "deploy", "halt", "order",
            "take cover", "fall back", "standby", "proceed", "cease fire",
            // Hinglish Romanized
            "wahi ruko", "aage mat badho", "aage badho", "piche hato", "hold karo", "cover lo", "standby raho"
        ),
        TacticalIntent.GENERAL_COMMS to listOf(
            "नमस्ते", "संपर्क", "हेलो", "सुनाई दे रहा है", "आवाज",
            "hello", "copy", "roger", "over", "test", "radio check", "loud and clear",
            // Hinglish Romanized
            "kaha ho", "kaha par ho", "sunai de raha hai", "awaz aa rahi hai", "hello hello", "kaise ho"
        )
    )

    // Urgency Booster Keywords (Devanagari + Hinglish + English)
    private val CRITICAL_KEYWORDS = listOf(
        "तुरंत", "आपातकाल", "हमला", "खतरा", "मर रहे", "खून", "फायरिंग",
        "immediately", "critical", "emergency", "urgent", "under fire", "bleeding", "mayday", "asap",
        // Hinglish
        "turant", "jaldi", "bachao", "madad", "firing", "khoon", "urgent hai", "danger hai"
    )

    private val HIGH_KEYWORDS = listOf(
        "जल्दी", "घायल", "मदद", "खराबी", "बैकअप", "धमकी",
        "quick", "fast", "injured", "help", "backup", "threat", "casualty",
        // Hinglish
        "chot", "ghayal", "fas gaye", "toot gaya", "help chahiye"
    )

    // Entity Extraction Regular Expressions (Devanagari, Latin, and Hinglish variants)
    private val LOCATION_PATTERN = Pattern.compile(
        """(?i)(?:(?<=[\s,।!?])|^)(चौकी|सेक्टर|कैंप|बेस|पुल|नदी|Sector|Camp|Base|Post|Bridge|Point|Ridge|Hill|chowki|chouki|bridge|pul|rasta|road|checkpoint)\s*([0-9A-Za-z]+)?(?=(?:[\s,।!?]|$))"""
    )
    private val CASUALTY_PATTERN = Pattern.compile(
        """(?i)\b(\d+)\s*(घायल|मरीज|जवान|casualties|wounded|injured|soldiers|men|ghayal|log|jawans)\b"""
    )
    private val GRID_PATTERN = Pattern.compile(
        """(?i)\b(Grid|ग्रिड|Coordinates|grid)?\s*([0-9]{2,4}[-\s/][0-9]{2,4})\b"""
    )
    private val TIME_PATTERN = Pattern.compile(
        """(?i)\b(तुरंत|जल्दी|ASAP|immediately|turant|jaldi|\d+\s*(मिनट|घंटे|minutes|hours|mins|minute))\b"""
    )

    /**
     * Performs fast, completely offline natural language and contextual analysis
     * on recognized text across Devanagari, English, and Romanized Hinglish.
     */
    fun analyze(text: String): ContextAnalysis {
        val cleanText = text.trim()
        val lowerText = cleanText.lowercase(Locale.ROOT)

        if (cleanText.isEmpty()) {
            return ContextAnalysis(
                intent = TacticalIntent.GENERAL_COMMS,
                urgency = UrgencyLevel.LOW,
                urgencyScore = 0.2f,
                entities = emptyList(),
                suggestedQuickReplies = listOf("रोजर (Roger)", "स्वीकार किया (Copy)"),
                codeMixingInfo = null
            )
        }

        // Code-mixing analysis
        val codeMixingInfo = CodeMixingEngine.analyzeAndOptimize(cleanText)

        // 1. Intent Classification via weighted keyword matching
        val intentScores = mutableMapOf<TacticalIntent, Int>()
        for ((intent, keywords) in INTENT_KEYWORDS) {
            var score = 0
            for (kw in keywords) {
                if (lowerText.contains(kw.lowercase(Locale.ROOT))) {
                    score += kw.length // longer keywords provide stronger semantic signal
                }
            }
            if (score > 0) {
                intentScores[intent] = score
            }
        }

        val bestIntent = intentScores.maxByOrNull { it.value }?.key ?: TacticalIntent.GENERAL_COMMS

        // 2. Urgency Scoring
        var rawUrgency = 0.2f
        for (kw in CRITICAL_KEYWORDS) {
            if (lowerText.contains(kw)) {
                rawUrgency += 0.5f
            }
        }
        for (kw in HIGH_KEYWORDS) {
            if (lowerText.contains(kw)) {
                rawUrgency += 0.25f
            }
        }
        if (bestIntent == TacticalIntent.EMERGENCY_SOS || bestIntent == TacticalIntent.MEDICAL_EVAC) {
            rawUrgency += 0.35f
        }

        val urgencyScore = rawUrgency.coerceIn(0.1f, 1.0f)
        val urgencyLevel = when {
            urgencyScore >= 0.85f -> UrgencyLevel.CRITICAL
            urgencyScore >= 0.60f -> UrgencyLevel.HIGH
            urgencyScore >= 0.40f -> UrgencyLevel.MEDIUM
            else -> UrgencyLevel.LOW
        }

        // 3. Entity Extraction
        val entities = mutableListOf<ExtractedEntity>()

        val locMatcher = LOCATION_PATTERN.matcher(cleanText)
        while (locMatcher.find()) {
            val matched = locMatcher.group(0)?.trim()
            if (!matched.isNullOrBlank()) {
                entities.add(ExtractedEntity(category = "LOCATION", value = matched))
            }
        }

        val casualtyMatcher = CASUALTY_PATTERN.matcher(cleanText)
        while (casualtyMatcher.find()) {
            val matched = casualtyMatcher.group(0)?.trim()
            if (!matched.isNullOrBlank()) {
                entities.add(ExtractedEntity(category = "CASUALTY", value = matched))
            }
        }

        val gridMatcher = GRID_PATTERN.matcher(cleanText)
        while (gridMatcher.find()) {
            val matched = gridMatcher.group(0)?.trim()
            if (!matched.isNullOrBlank()) {
                entities.add(ExtractedEntity(category = "GRID", value = matched))
            }
        }

        val timeMatcher = TIME_PATTERN.matcher(cleanText)
        while (timeMatcher.find()) {
            val matched = timeMatcher.group(0)?.trim()
            if (!matched.isNullOrBlank()) {
                entities.add(ExtractedEntity(category = "TIME", value = matched))
            }
        }

        // 4. Suggested Tactical Quick Replies (Bilingual / Hinglish accessible)
        val suggestedReplies = when (bestIntent) {
            TacticalIntent.EMERGENCY_SOS -> listOf(
                "🚨 टीम रवाना कर दी गई है / Team dispatched",
                "📍 स्थिति बनाए रखें / Hold & take cover",
                "कन्फर्म करें ग्रिड / Confirm coordinates"
            )
            TacticalIntent.MEDICAL_EVAC -> listOf(
                "🚑 मेडिकल टीम रास्ते में है / Medic en route",
                "प्राथमिक उपचार दें / First aid admin",
                "घायलों की संख्या बताएं / Confirm casualty count"
            )
            TacticalIntent.LOGISTICS_SUPPLY -> listOf(
                "📦 आपूर्ति तैयार की जा रही है / Supplies prepped",
                "अनुमानित समय 30 मिनट / ETA 30 mins",
                "ड्रॉप लोकेशन कन्फर्म करें / Confirm drop point"
            )
            TacticalIntent.SITUATION_REPORT -> listOf(
                "स्वीकार किया, सब सामान्य / Copy, all clear",
                "अगली रिपोर्ट 1 घंटे बाद / Next report 1 hr",
                "सतर्कता बनाए रखें / Stay vigilant"
            )
            TacticalIntent.ROUTE_NAVIGATION -> listOf(
                "मार्ग सुरक्षित है / Route clear",
                "वैकल्पिक रास्ता अपनाएं / Take alternate route",
                "चेकपॉइंट पर रिपोर्ट करें / Report at checkpoint"
            )
            TacticalIntent.RESOURCE_REQUEST -> listOf(
                "🛡️ बैकअप यूनिट भेजी जा रही है / Backup dispatched",
                "सहायता 10 मिनट में / Support in 10 mins",
                "पोजिशन सुरक्षित रखें / Secure position"
            )
            TacticalIntent.COMMAND_ORDER -> listOf(
                "आदेश स्वीकार / Order acknowledged",
                "पोजिशन ले ली गई है / In position",
                "निर्देश दोहराएं / Repeat order"
            )
            TacticalIntent.GENERAL_COMMS -> listOf(
                "रोजर / Roger that",
                "आवाज साफ आ रही है / Loud & clear",
                "स्वीकार किया / Copy"
            )
        }

        return ContextAnalysis(
            intent = bestIntent,
            urgency = urgencyLevel,
            urgencyScore = urgencyScore,
            entities = entities,
            suggestedQuickReplies = suggestedReplies,
            codeMixingInfo = codeMixingInfo
        )
    }
}
