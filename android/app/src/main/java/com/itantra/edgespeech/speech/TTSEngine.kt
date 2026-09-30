package com.itantra.edgespeech.speech

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.itantra.edgespeech.audio.AudioPlaybackEngine
import com.itantra.edgespeech.protocol.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.sin

class TTSEngine(
    private val context: Context,
    private val playbackEngine: AudioPlaybackEngine
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set
    private var currentLanguage: Language = Language.HINDI
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _isSpeakingFlow = MutableStateFlow(false)
    val isSpeakingFlow: StateFlow<Boolean> = _isSpeakingFlow

    private val _currentlySpeakingTextFlow = MutableStateFlow("")
    val currentlySpeakingTextFlow: StateFlow<String> = _currentlySpeakingTextFlow

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            // TTS initialization error
        }
    }

    private var pendingSpeechQueue = ArrayDeque<Pair<String, Language>>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            setupProgressListener()
            updateTtsLocale(currentLanguage)
            // Flush any speech requests queued during initialization
            while (pendingSpeechQueue.isNotEmpty()) {
                val (text, lang) = pendingSpeechQueue.removeFirst()
                synthesizeAndPlay(text, lang)
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeakingFlow.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeakingFlow.value = false
                _currentlySpeakingTextFlow.value = ""
            }

            override fun onError(utteranceId: String?) {
                _isSpeakingFlow.value = false
                _currentlySpeakingTextFlow.value = ""
            }
        })
    }

    fun setLanguage(language: Language) {
        this.currentLanguage = language
        if (isTtsReady) {
            updateTtsLocale(language)
        }
    }

    private fun updateTtsLocale(language: Language) {
        val locale = when (language) {
            Language.HINDI -> Locale("hi", "IN")
            Language.GUJARATI -> Locale("gu", "IN")
            Language.MARATHI -> Locale("mr", "IN")
            Language.KANNADA -> Locale("kn", "IN")
            Language.MALAYALAM -> Locale("ml", "IN")
            Language.TAMIL -> Locale("ta", "IN")
            Language.TELUGU -> Locale("te", "IN")
            Language.ODIA -> Locale("or", "IN")
            Language.BENGALI -> Locale("bn", "IN")
            Language.ENGLISH -> Locale("en", "IN")
        }

        try {
            var res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts?.setLanguage(Locale(locale.language))
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts?.setLanguage(Locale.ENGLISH)
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        } catch (e: Exception) {
            // Ignore locale switch errors
        }
    }

    /**
     * Synthesizes incoming text into real audible speech out of the loudspeaker.
     * 1. Preprocesses Hinglish / code-mixed text for phonetic naturalness.
     * 2. Maximizes volume for non-interruptible emergency alerts (per SIH requirements).
     * 3. Uses dynamic pitch and cadence based on urgency.
     */
    fun synthesizeAndPlay(
        text: String,
        language: Language = currentLanguage,
        isAlert: Boolean = false,
        urgency: UrgencyLevel = UrgencyLevel.LOW
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        // 1. Analyze and optimize Hinglish / code-mixing for natural TTS synthesis
        val mixingAnalysis = CodeMixingEngine.analyzeAndOptimize(cleanText, fallbackLanguage = language)
        val speechText = if (mixingAnalysis.ttsOptimizedText.isNotBlank()) mixingAnalysis.ttsOptimizedText else cleanText
        val speechLanguage = mixingAnalysis.recommendedTtsLanguage

        if (!isTtsReady || tts == null) {
            pendingSpeechQueue.addLast(Pair(speechText, speechLanguage))
            return
        }

        playbackEngine.requestPlaybackFocus(isAlert)
        updateTtsLocale(speechLanguage)
        _currentlySpeakingTextFlow.value = cleanText

        // 2. For Emergency Alerts: announce at maximum volume non-interruptible (SIH requirement)
        if (isAlert || urgency == UrgencyLevel.CRITICAL) {
            maximizeAlertVolume()
        }

        val streamType = if (isAlert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        when {
            isAlert || urgency == UrgencyLevel.CRITICAL -> {
                tts?.setPitch(1.25f)
                tts?.setSpeechRate(1.15f)
            }
            urgency == UrgencyLevel.HIGH -> {
                tts?.setPitch(1.10f)
                tts?.setSpeechRate(1.05f)
            }
            else -> {
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.95f)
            }
        }

        val utteranceId = "msg_${System.currentTimeMillis()}"
        _isSpeakingFlow.value = true
        tts?.speak(speechText, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    private fun maximizeAlertVolume() {
        try {
            audioManager?.let { am ->
                val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)

                val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            }
        } catch (e: Exception) {
            // Permission or device specific volume lock
        }
    }

    private fun playAcousticFallback(text: String, isAlert: Boolean) {
        val sampleRate = 16000
        val durationSec = (text.length * 0.08).coerceIn(0.6, 5.0)
        val totalSamples = (sampleRate * durationSec).toInt()
        val pcm = ShortArray(totalSamples)

        val freq = if (isAlert) 880.0 else 440.0
        val volume = if (isAlert) 32000.0 else 18000.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val modulation = sin(2.0 * Math.PI * 4.0 * t)
            val sample = (volume * sin(2.0 * Math.PI * freq * t) * (0.8 + 0.2 * modulation)).toInt()
            pcm[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playbackEngine.playPcmChunk(pcm, isAlert)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        _isSpeakingFlow.value = false
        _currentlySpeakingTextFlow.value = ""
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Ignore
        }
        tts = null
        _isSpeakingFlow.value = false
        _currentlySpeakingTextFlow.value = ""
    }
}
