"""
iTantra Adaptive Endpoint Detector
Multi-signal endpointing combining VAD silence duration, ASR hypothesis stability,
sentence structure / punctuation cues, and minimum/maximum utterance safety bounds.

Enhanced with language-aware thresholds for all 10 Indian languages.
Hindi and other Indic languages have different pause patterns than English,
so each language gets tuned silence thresholds.
"""

import time
from enum import Enum
from typing import Optional, Tuple, Dict, Any


class EndpointState(Enum):
    IDLE = "IDLE"
    SPEECH = "SPEECH"
    POSSIBLE_END = "POSSIBLE_END"
    FINALIZED = "FINALIZED"


# Language-specific endpointing configurations
# Hindi speakers tend to have longer inter-clause pauses; English is shorter.
# These were tuned from observing natural speech patterns in each language.
LANGUAGE_ENDPOINT_CONFIGS: Dict[str, Dict[str, Any]] = {
    "en": {
        "short_silence_threshold_ms": 280,
        "stable_silence_threshold_ms": 600,
        "long_silence_threshold_ms": 1100,
        "hypothesis_stable_ms": 320,
        "terminal_markers": [".", "?", "!", "...", "—"],
        "label": "English",
    },
    "hi": {
        "short_silence_threshold_ms": 350,
        "stable_silence_threshold_ms": 750,
        "long_silence_threshold_ms": 1400,
        "hypothesis_stable_ms": 400,
        "terminal_markers": ["।", "॥", ".", "?", "!", "?"],
        "label": "Hindi",
    },
    "mr": {
        "short_silence_threshold_ms": 340,
        "stable_silence_threshold_ms": 720,
        "long_silence_threshold_ms": 1350,
        "hypothesis_stable_ms": 390,
        "terminal_markers": ["।", "॥", ".", "?", "!"],
        "label": "Marathi",
    },
    "gu": {
        "short_silence_threshold_ms": 330,
        "stable_silence_threshold_ms": 700,
        "long_silence_threshold_ms": 1300,
        "hypothesis_stable_ms": 380,
        "terminal_markers": ["।", ".", "?", "!"],
        "label": "Gujarati",
    },
    "kn": {
        "short_silence_threshold_ms": 340,
        "stable_silence_threshold_ms": 720,
        "long_silence_threshold_ms": 1350,
        "hypothesis_stable_ms": 390,
        "terminal_markers": [".", "?", "!", "।"],
        "label": "Kannada",
    },
    "ml": {
        "short_silence_threshold_ms": 350,
        "stable_silence_threshold_ms": 740,
        "long_silence_threshold_ms": 1400,
        "hypothesis_stable_ms": 400,
        "terminal_markers": [".", "?", "!", "।"],
        "label": "Malayalam",
    },
    "ta": {
        "short_silence_threshold_ms": 330,
        "stable_silence_threshold_ms": 700,
        "long_silence_threshold_ms": 1300,
        "hypothesis_stable_ms": 380,
        "terminal_markers": [".", "?", "!", "।"],
        "label": "Tamil",
    },
    "te": {
        "short_silence_threshold_ms": 340,
        "stable_silence_threshold_ms": 720,
        "long_silence_threshold_ms": 1350,
        "hypothesis_stable_ms": 390,
        "terminal_markers": [".", "?", "!", "।"],
        "label": "Telugu",
    },
    "or": {
        "short_silence_threshold_ms": 350,
        "stable_silence_threshold_ms": 740,
        "long_silence_threshold_ms": 1400,
        "hypothesis_stable_ms": 400,
        "terminal_markers": ["।", ".", "?", "!"],
        "label": "Odia",
    },
    "bn": {
        "short_silence_threshold_ms": 340,
        "stable_silence_threshold_ms": 720,
        "long_silence_threshold_ms": 1350,
        "hypothesis_stable_ms": 390,
        "terminal_markers": ["।", ".", "?", "!"],
        "label": "Bengali",
    },
}


def get_endpoint_config(language: str = "hi") -> Dict[str, Any]:
    """
    Returns the endpointing configuration for a given language code.
    Used by clients (web / Android) to fetch language-specific tuning parameters.
    """
    config = LANGUAGE_ENDPOINT_CONFIGS.get(language, LANGUAGE_ENDPOINT_CONFIGS["hi"])
    return {
        "language": language,
        "min_utterance_ms": 400,
        "max_utterance_ms": 30000,
        **config,
    }


class AdaptiveEndpointDetector:
    def __init__(
        self,
        min_utterance_ms: int = 400,
        max_utterance_ms: int = 30000,
        short_silence_threshold_ms: int = 300,
        stable_silence_threshold_ms: int = 650,
        long_silence_threshold_ms: int = 1200,
        hypothesis_stable_ms: int = 350,
        terminal_markers: list = None,
        language: str = "hi",
    ):
        # Load language-specific defaults if not explicitly overridden
        lang_config = LANGUAGE_ENDPOINT_CONFIGS.get(language, LANGUAGE_ENDPOINT_CONFIGS["hi"])

        self.min_utterance_ms = min_utterance_ms
        self.max_utterance_ms = max_utterance_ms
        self.short_silence_threshold_ms = short_silence_threshold_ms or lang_config["short_silence_threshold_ms"]
        self.stable_silence_threshold_ms = stable_silence_threshold_ms or lang_config["stable_silence_threshold_ms"]
        self.long_silence_threshold_ms = long_silence_threshold_ms or lang_config["long_silence_threshold_ms"]
        self.hypothesis_stable_ms = hypothesis_stable_ms or lang_config["hypothesis_stable_ms"]
        self.terminal_markers = terminal_markers or lang_config["terminal_markers"]

        self.state = EndpointState.IDLE
        self.speech_start_time: Optional[float] = None
        self.last_speech_time: Optional[float] = None
        self.last_hypothesis: str = ""
        self.hypothesis_stable_time: Optional[float] = None

    def configure_for_language(self, language: str):
        """Reconfigure thresholds for a new language at runtime."""
        config = LANGUAGE_ENDPOINT_CONFIGS.get(language, LANGUAGE_ENDPOINT_CONFIGS["hi"])
        self.short_silence_threshold_ms = config["short_silence_threshold_ms"]
        self.stable_silence_threshold_ms = config["stable_silence_threshold_ms"]
        self.long_silence_threshold_ms = config["long_silence_threshold_ms"]
        self.hypothesis_stable_ms = config["hypothesis_stable_ms"]
        self.terminal_markers = config["terminal_markers"]

    def on_frame(self, is_speech: bool, current_partial_text: str = "") -> Tuple[EndpointState, bool]:
        """
        Processes a 20ms audio frame status and current partial STT hypothesis.
        Returns: (current_state, should_finalize_now)
        """
        now = time.time() * 1000.0

        # Track text stability
        if current_partial_text != self.last_hypothesis:
            self.last_hypothesis = current_partial_text
            self.hypothesis_stable_time = now

        text_stable_duration = (now - self.hypothesis_stable_time) if self.hypothesis_stable_time else 0.0

        if self.state == EndpointState.IDLE:
            if is_speech:
                self.state = EndpointState.SPEECH
                self.speech_start_time = now
                self.last_speech_time = now
                return self.state, False
            return self.state, False

        elif self.state == EndpointState.SPEECH:
            if is_speech:
                self.last_speech_time = now
                # Check hard maximum utterance duration safety limit
                if (now - self.speech_start_time) >= self.max_utterance_ms:
                    self.state = EndpointState.FINALIZED
                    return self.state, True
                return self.state, False
            else:
                # Silence begins
                self.state = EndpointState.POSSIBLE_END
                return self.state, False

        elif self.state == EndpointState.POSSIBLE_END:
            if is_speech:
                # User resumed speaking before silence finalized
                self.state = EndpointState.SPEECH
                self.last_speech_time = now
                return self.state, False

            # Ongoing silence
            silence_duration = now - (self.last_speech_time or now)
            utterance_duration = now - (self.speech_start_time or now)

            # Enforce minimum utterance duration
            if utterance_duration < self.min_utterance_ms:
                # Too brief (e.g. mic click / cough)
                return self.state, False

            # Signal 1: Terminal punctuation detected (language-aware markers)
            has_terminal_cue = any(self.last_hypothesis.strip().endswith(p) for p in self.terminal_markers)
            if has_terminal_cue and silence_duration >= self.short_silence_threshold_ms:
                self.state = EndpointState.FINALIZED
                return self.state, True

            # Signal 2: ASR hypothesis is stable (no new tokens) + medium silence
            if text_stable_duration >= self.hypothesis_stable_ms and silence_duration >= self.stable_silence_threshold_ms:
                self.state = EndpointState.FINALIZED
                return self.state, True

            # Signal 3: Long silence fallback
            if silence_duration >= self.long_silence_threshold_ms:
                self.state = EndpointState.FINALIZED
                return self.state, True

            return self.state, False

        elif self.state == EndpointState.FINALIZED:
            return self.state, False

        return self.state, False

    def reset(self):
        self.state = EndpointState.IDLE
        self.speech_start_time = None
        self.last_speech_time = None
        self.last_hypothesis = ""
        self.hypothesis_stable_time = None
