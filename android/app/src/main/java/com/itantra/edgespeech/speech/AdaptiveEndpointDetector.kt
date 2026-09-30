package com.itantra.edgespeech.speech

import com.itantra.edgespeech.protocol.Language

enum class EndpointState(val label: String) {
    IDLE("Idle"),
    SPEECH("Speaking"),
    PAUSE_DETECTED("Pause Detected"),
    FINALIZED("Finalized")
}

data class LanguageEndpointConfig(
    val shortSilenceMs: Long,
    val stableSilenceMs: Long,
    val longSilenceMs: Long,
    val terminalCues: List<String>,
    val name: String
)

class AdaptiveEndpointDetector(
    private var shortSilenceThresholdMs: Long = 350L,
    private var stableSilenceThresholdMs: Long = 750L,
    private var longSilenceThresholdMs: Long = 1400L,
    private val minUtteranceMs: Long = 300L,
    private val maxUtteranceMs: Long = 25000L
) {
    var state = EndpointState.IDLE
        private set

    private var speechStartTime: Long? = null
    private var lastSpeechTime: Long? = null
    private var lastHypothesis: String = ""
    private var hypothesisStableTime: Long? = null
    private var activeLanguage: Language = Language.HINDI
    private var terminalCues: List<String> = listOf(".", "?", "!", "।", "॥")

    var onStateChanged: ((EndpointState) -> Unit)? = null
    var onPauseProgress: ((Float) -> Unit)? = null

    init {
        configureForLanguage(Language.HINDI)
    }

    companion object {
        val LANGUAGE_CONFIGS = mapOf(
            Language.HINDI to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!", "।", "॥"), "Hindi"),
            Language.ENGLISH to LanguageEndpointConfig(500L, 1000L, 1800L, listOf(".", "?", "!"), "English"),
            Language.TAMIL to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!"), "Tamil"),
            Language.TELUGU to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!"), "Telugu"),
            Language.MARATHI to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!", "।"), "Marathi"),
            Language.BENGALI to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!", "।"), "Bengali"),
            Language.KANNADA to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!"), "Kannada"),
            Language.GUJARATI to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!"), "Gujarati"),
            Language.MALAYALAM to LanguageEndpointConfig(650L, 1300L, 2200L, listOf(".", "?", "!"), "Malayalam"),
            Language.ODIA to LanguageEndpointConfig(600L, 1200L, 2000L, listOf(".", "?", "!", "।"), "Odia")
        )
    }

    fun configureForLanguage(language: Language) {
        this.activeLanguage = language
        val config = LANGUAGE_CONFIGS[language] ?: LANGUAGE_CONFIGS[Language.HINDI]!!
        this.shortSilenceThresholdMs = config.shortSilenceMs
        this.stableSilenceThresholdMs = config.stableSilenceMs
        this.longSilenceThresholdMs = config.longSilenceMs
        this.terminalCues = config.terminalCues
    }

    fun onFrame(isSpeech: Boolean, currentPartialText: String = ""): Pair<EndpointState, Boolean> {
        val now = System.currentTimeMillis()

        if (currentPartialText.isNotBlank() && currentPartialText != lastHypothesis) {
            lastHypothesis = currentPartialText
            hypothesisStableTime = now
        }

        val textStableDuration = if (hypothesisStableTime != null) (now - hypothesisStableTime!!) else 0L

        when (state) {
            EndpointState.IDLE -> {
                if (isSpeech || currentPartialText.isNotBlank()) {
                    transitionTo(EndpointState.SPEECH)
                    speechStartTime = now
                    lastSpeechTime = now
                    onPauseProgress?.invoke(0f)
                    return Pair(state, false)
                }
                return Pair(state, false)
            }

            EndpointState.SPEECH -> {
                if (isSpeech) {
                    lastSpeechTime = now
                    onPauseProgress?.invoke(0f)
                    if (speechStartTime != null && (now - speechStartTime!!) >= maxUtteranceMs) {
                        transitionTo(EndpointState.FINALIZED)
                        return Pair(state, true)
                    }
                    return Pair(state, false)
                } else {
                    transitionTo(EndpointState.PAUSE_DETECTED)
                    return evaluatePause(now, textStableDuration)
                }
            }

            EndpointState.PAUSE_DETECTED -> {
                if (isSpeech) {
                    // Speaker resumed after brief pause (comma/mid-sentence breath)
                    transitionTo(EndpointState.SPEECH)
                    lastSpeechTime = now
                    onPauseProgress?.invoke(0f)
                    return Pair(state, false)
                }

                return evaluatePause(now, textStableDuration)
            }

            EndpointState.FINALIZED -> {
                return Pair(state, false)
            }
        }
    }

    private fun transitionTo(newState: EndpointState) {
        if (state != newState) {
            state = newState
            onStateChanged?.invoke(newState)
        }
    }

    private fun evaluatePause(now: Long, textStableDuration: Long): Pair<EndpointState, Boolean> {
        val silenceDuration = now - (lastSpeechTime ?: now)
        val utteranceDuration = now - (speechStartTime ?: now)

        // Minimum speech duration filter (e.g. mic pop or throat clear)
        if (utteranceDuration < minUtteranceMs && lastHypothesis.isBlank()) {
            return Pair(state, false)
        }

        val hasTerminalCue = terminalCues.any { lastHypothesis.endsWith(it) }

        // Calculate progress to finalization for visual UI bar (0.0 to 1.0)
        val targetSilence = when {
            hasTerminalCue -> shortSilenceThresholdMs
            textStableDuration >= 300L -> stableSilenceThresholdMs
            else -> longSilenceThresholdMs
        }
        val progress = (silenceDuration.toFloat() / targetSilence).coerceIn(0f, 1f)
        onPauseProgress?.invoke(progress)

        // 1. Terminal cue (punctuation) + short silence -> immediate finalization
        if (hasTerminalCue && silenceDuration >= shortSilenceThresholdMs) {
            transitionTo(EndpointState.FINALIZED)
            return Pair(state, true)
        }

        // 2. Stable text hypothesis + medium silence
        if (textStableDuration >= 300L && silenceDuration >= stableSilenceThresholdMs) {
            transitionTo(EndpointState.FINALIZED)
            return Pair(state, true)
        }

        // 3. Long silence fallback
        if (silenceDuration >= longSilenceThresholdMs) {
            transitionTo(EndpointState.FINALIZED)
            return Pair(state, true)
        }

        return Pair(state, false)
    }

    fun reset() {
        transitionTo(EndpointState.IDLE)
        speechStartTime = null
        lastSpeechTime = null
        lastHypothesis = ""
        hypothesisStableTime = null
        onPauseProgress?.invoke(0f)
    }
}
