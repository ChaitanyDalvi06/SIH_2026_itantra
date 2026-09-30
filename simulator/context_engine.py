"""
iTantra Contextual Understanding Engine
Lightweight bilingual (Hindi + English) NLP pipeline for speech intent classification,
urgency scoring, and entity extraction. Fully offline, keyword/pattern-based.
Runs in <1ms per message — zero latency impact on the speech pipeline.
"""

import re
from typing import Dict, Any, List, Optional, Tuple


# ─── Intent Categories ───────────────────────────────────────────────────────

INTENT_CATEGORIES = [
    "greeting",
    "request_help",
    "status_update",
    "location_query",
    "emergency",
    "acknowledgment",
    "instruction",
    "information",
]

# Bilingual keyword dictionaries for intent classification
# Each entry: (keywords_list, weight) — higher weight = stronger signal
INTENT_KEYWORDS: Dict[str, List[Tuple[List[str], float]]] = {
    "greeting": [
        (["hello", "hi", "hey", "good morning", "good evening", "good afternoon", "greetings"], 1.0),
        (["नमस्ते", "नमस्कार", "प्रणाम", "सुप्रभात", "शुभ संध्या", "राम राम", "जय हिंद"], 1.0),
    ],
    "request_help": [
        (["help", "assist", "support", "need", "require", "send", "please come", "backup", "reinforcement"], 1.0),
        (["मदद", "सहायता", "मदत", "सहयोग", "भेजो", "आओ", "बचाओ", "चाहिए", "दरकार", "जरूरत", "आवश्यकता"], 1.0),
    ],
    "status_update": [
        (["status", "update", "report", "situation", "condition", "reached", "arrived", "safe", "secure", "clear", "all clear"], 1.0),
        (["स्थिति", "हालात", "रिपोर्ट", "पहुँच गए", "सुरक्षित", "ठीक", "हम यहाँ", "पहुंचे", "स्थिर"], 1.0),
    ],
    "location_query": [
        (["where", "location", "position", "coordinates", "address", "direction", "route", "way", "path", "which way"], 1.0),
        (["कहाँ", "किधर", "कहां", "रास्ता", "दिशा", "स्थान", "पता", "कौन सा रास्ता", "किस तरफ", "कैसे जाएं"], 1.0),
    ],
    "emergency": [
        (["emergency", "urgent", "critical", "danger", "mayday", "sos", "fire", "attack", "casualty", "injured", "trapped", "immediately", "ambulance", "medic", "doctor"], 1.5),
        (["आपातकाल", "आपातकालीन", "खतरा", "तुरंत", "जल्दी", "घायल", "फंसे", "आग", "हमला", "गंभीर", "तत्काल", "फौरन", "अभी", "मदद", "बचाओ", "madad", "bachao", "jaldi"], 1.5),
    ],
    "acknowledgment": [
        (["ok", "okay", "roger", "copy", "understood", "affirmative", "confirmed", "wilco", "received", "got it", "yes sir"], 1.0),
        (["ठीक", "समझ गया", "समझ गए", "हाँ", "जी", "पुष्टि", "मिल गया", "सुन लिया", "ठीक है", "जी हाँ", "समझा"], 1.0),
    ],
    "instruction": [
        (["move", "go", "proceed", "evacuate", "deploy", "patrol", "hold position", "stand by", "retreat", "advance", "cover", "engage", "disengage", "stop"], 1.0),
        (["चलो", "जाओ", "रुको", "आगे बढ़ो", "पीछे हटो", "तैनात", "निकासी", "खाली करो", "रुकें", "बढ़ो", "जाइए", "करो", "करिए"], 1.0),
    ],
    "information": [
        (["there are", "i see", "found", "spotted", "number", "count", "about", "estimated", "approximately", "information", "notice", "observe"], 0.7),
        (["वहाँ", "दिखा", "मिला", "पाया", "संख्या", "करीब", "लगभग", "जानकारी", "सूचना", "देखा", "पता चला", "बताना"], 0.7),
    ],
}


# ─── Urgency Scoring ──────────────────────────────────────────────────────────

URGENCY_KEYWORDS: Dict[str, List[str]] = {
    "critical": [
        "emergency", "sos", "mayday", "danger", "attack", "fire", "casualty", "life threatening",
        "आपातकाल", "खतरा", "हमला", "आग", "जान का खतरा", "गंभीर", "मौत", "बचाओ",
    ],
    "high": [
        "urgent", "immediately", "asap", "right now", "hurry", "fast", "quick", "critical", "injured", "trapped",
        "तुरंत", "जल्दी", "फौरन", "अभी", "तत्काल", "घायल", "फंसे", "शीघ्र",
    ],
    "medium": [
        "need", "require", "soon", "as soon as possible", "important", "backup",
        "चाहिए", "जरूरत", "जल्द", "महत्वपूर्ण", "आवश्यक", "जरूरी",
    ],
    "low": [
        "update", "inform", "status", "report", "check", "routine", "normal",
        "सूचना", "जानकारी", "स्थिति", "रिपोर्ट", "सामान्य",
    ],
}


# ─── Entity Extraction Patterns ───────────────────────────────────────────────

# Location patterns (common Hindi/English location indicators)
LOCATION_INDICATORS_EN = [
    r"\bat\s+(.+?)(?:\.|,|$|\s+and\s+|\s+near\s+)",
    r"\bnear\s+(.+?)(?:\.|,|$)",
    r"\btowards?\s+(.+?)(?:\.|,|$)",
    r"\bfrom\s+(.+?)(?:\.|,|$)",
    r"\bsector\s+(\w+)",
    r"\bzone\s+(\w+)",
    r"\bpoint\s+(\w+)",
    r"\bwaypoint\s+(\w+)",
    r"\bgate\s+(\w+)",
    r"\bblock\s+(\w+)",
]

LOCATION_INDICATORS_HI = [
    r"पर\s+(.+?)(?:।|,|$)",
    r"के\s+पास\s+(.+?)(?:।|,|$)",
    r"की\s+तरफ\s+(.+?)(?:।|,|$)",
    r"से\s+(.+?)(?:।|,|$)",
    r"सेक्टर\s+(\S+)",
    r"ज़ोन\s+(\S+)",
    r"गेट\s+(\S+)",
    r"चौक\s+(\S+)",
    r"स्टेशन\s+(\S+)",
]

# Number patterns
NUMBER_PATTERN = re.compile(r"\b(\d+(?:\.\d+)?)\b")
HINDI_NUMBER_WORDS = {
    "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5,
    "छह": 6, "सात": 7, "आठ": 8, "नौ": 9, "दस": 10,
    "बीस": 20, "तीस": 30, "पचास": 50, "सौ": 100,
}

# Time reference patterns
TIME_PATTERNS_EN = [
    r"\b(\d{1,2}:\d{2})\b",
    r"\b(\d{1,2})\s*(minutes?|mins?|hours?|hrs?|seconds?|secs?)\b",
    r"\b(morning|afternoon|evening|night|dawn|dusk)\b",
]

TIME_PATTERNS_HI = [
    r"(\d{1,2})\s*(मिनट|घंटे?|सेकंड|बजे)",
    r"(सुबह|दोपहर|शाम|रात|भोर)",
]


class ContextAnalyzer:
    """
    Lightweight bilingual contextual understanding engine.
    Analyzes finalized speech text to extract:
    - Intent category (8 types)
    - Urgency level (critical/high/medium/low)
    - Entities (locations, numbers, time references)
    - Confidence score
    """

    def analyze(self, text: str, language: str = "hi") -> Dict[str, Any]:
        """
        Analyze text and return structured context.
        Args:
            text: The finalized transcribed text
            language: Language code ('hi', 'en', etc.)
        Returns:
            Dict with intent, urgency, entities, confidence
        """
        if not text or not text.strip():
            return self._empty_context()

        text_lower = text.lower().strip()

        intent, intent_confidence = self._classify_intent(text_lower)
        urgency, urgency_confidence = self._score_urgency(text_lower)
        entities = self._extract_entities(text, text_lower, language)

        # If emergency intent detected, escalate urgency
        if intent == "emergency" and urgency not in ("critical", "high"):
            urgency = "high"
            urgency_confidence = max(urgency_confidence, 0.7)

        # Overall confidence is weighted average
        overall_confidence = round(
            0.6 * intent_confidence + 0.3 * urgency_confidence + 0.1 * (1.0 if entities else 0.3),
            2,
        )

        return {
            "intent": intent,
            "intent_confidence": round(intent_confidence, 2),
            "urgency": urgency,
            "urgency_confidence": round(urgency_confidence, 2),
            "entities": entities,
            "confidence": overall_confidence,
            "language": language,
            "text_length": len(text),
        }

    def _classify_intent(self, text_lower: str) -> Tuple[str, float]:
        """Classify intent using weighted keyword matching."""
        scores: Dict[str, float] = {}

        for intent, keyword_groups in INTENT_KEYWORDS.items():
            total_score = 0.0
            matches = 0
            for keywords, weight in keyword_groups:
                for kw in keywords:
                    kw_lower = kw.lower()
                    if len(kw_lower) <= 3 and kw_lower.isascii():
                        if re.search(r"\b" + re.escape(kw_lower) + r"\b", text_lower):
                            total_score += weight
                            matches += 1
                    else:
                        if kw_lower in text_lower:
                            total_score += weight
                            matches += 1

            if matches > 0:
                # Normalize score: more matches = higher confidence, capped at 1.0
                scores[intent] = min(1.0, total_score * 0.4 + (matches - 1) * 0.15)

        if not scores:
            return "information", 0.3  # Default to generic information

        best_intent = max(scores, key=scores.get)
        return best_intent, scores[best_intent]

    def _score_urgency(self, text_lower: str) -> Tuple[str, float]:
        """Score urgency level using keyword matching with priority ordering."""
        # Check from highest to lowest urgency
        for level in ["critical", "high", "medium", "low"]:
            keywords = URGENCY_KEYWORDS[level]
            match_count = sum(1 for kw in keywords if kw.lower() in text_lower)
            if match_count > 0:
                confidence = min(1.0, 0.5 + match_count * 0.2)
                return level, confidence

        return "low", 0.3  # Default

    def _extract_entities(self, text: str, text_lower: str, language: str) -> Dict[str, List[str]]:
        """Extract entities: locations, numbers, time references."""
        entities: Dict[str, List[str]] = {
            "locations": [],
            "numbers": [],
            "time_refs": [],
        }

        # ── Locations ──
        patterns = LOCATION_INDICATORS_EN + (LOCATION_INDICATORS_HI if language in ("hi", "mr") else [])
        for pattern in patterns:
            try:
                for match in re.finditer(pattern, text, re.IGNORECASE):
                    loc = match.group(1).strip()
                    if loc and len(loc) > 1 and loc not in entities["locations"]:
                        entities["locations"].append(loc)
            except Exception:
                pass

        # ── Numbers ──
        for match in NUMBER_PATTERN.finditer(text):
            num_str = match.group(1)
            if num_str not in entities["numbers"]:
                entities["numbers"].append(num_str)

        # Hindi number words
        for word, val in HINDI_NUMBER_WORDS.items():
            if word in text:
                str_val = str(val)
                if str_val not in entities["numbers"]:
                    entities["numbers"].append(str_val)

        # ── Time references ──
        time_patterns = TIME_PATTERNS_EN + (TIME_PATTERNS_HI if language in ("hi", "mr") else [])
        for pattern in time_patterns:
            try:
                for match in re.finditer(pattern, text, re.IGNORECASE):
                    time_ref = match.group(0).strip()
                    if time_ref and time_ref not in entities["time_refs"]:
                        entities["time_refs"].append(time_ref)
            except Exception:
                pass

        # Clean up empty categories
        return {k: v for k, v in entities.items() if v}

    def _empty_context(self) -> Dict[str, Any]:
        return {
            "intent": "unknown",
            "intent_confidence": 0.0,
            "urgency": "low",
            "urgency_confidence": 0.0,
            "entities": {},
            "confidence": 0.0,
            "language": "unknown",
            "text_length": 0,
        }


# Singleton instance for server use
context_analyzer = ContextAnalyzer()


def analyze_text(text: str, language: str = "hi") -> Dict[str, Any]:
    """Convenience function for quick analysis."""
    return context_analyzer.analyze(text, language)
