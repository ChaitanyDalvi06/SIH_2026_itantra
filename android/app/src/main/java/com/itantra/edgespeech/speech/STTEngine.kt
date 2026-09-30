package com.itantra.edgespeech.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.itantra.edgespeech.protocol.Language
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class TranscriptionResult(
    val text: String,
    val isFinal: Boolean,
    val language: Language,
    val messageId: Long = System.currentTimeMillis()
)

class STTEngine(private val context: Context) : RecognitionListener {

    private var activeLanguage: Language = Language.HINDI
    private val _transcriptionFlow = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 64)
    val transcriptionFlow: SharedFlow<TranscriptionResult> = _transcriptionFlow

    private val _audioLevelFlow = MutableStateFlow(0.0f)
    val audioLevelFlow: StateFlow<Float> = _audioLevelFlow

    private val _endpointStateFlow = MutableStateFlow(EndpointState.IDLE)
    val endpointStateFlow: StateFlow<EndpointState> = _endpointStateFlow

    private val _pauseProgressFlow = MutableStateFlow(0.0f)
    val pauseProgressFlow: StateFlow<Float> = _pauseProgressFlow

    val endpointDetector = AdaptiveEndpointDetector()

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening = false
        private set
    var isContinuousMode: Boolean = false

    private var lastRecognizedText = ""
    private var currentUtteranceId: Long = System.currentTimeMillis()
    private val mainHandler = Handler(Looper.getMainLooper())

    var onMicrophoneStateChange: ((Boolean) -> Unit)? = null
    var onUtteranceFinalized: ((String, Language) -> Unit)? = null

    init {
        endpointDetector.onStateChanged = { newState ->
            _endpointStateFlow.value = newState
        }
        endpointDetector.onPauseProgress = { progress ->
            _pauseProgressFlow.value = progress
        }
        endpointDetector.configureForLanguage(activeLanguage)
        mainHandler.post { ensureSpeechRecognizer() }
    }

    private var isOnDeviceRecognizerActive = false
    private var fallbackToStandard = false
    private var useRelaxedOffline = false
    private var lastAttemptedLangTag: String = "en-IN"
    private var fallbackLangTag: String? = null

    private fun ensureSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) return speechRecognizer
        try {
            // Tier 1: Try createOnDeviceSpeechRecognizer if available and not falling back
            if (!fallbackToStandard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                        setRecognitionListener(this@STTEngine)
                    }
                    isOnDeviceRecognizerActive = true
                    Log.i("STTEngine", "OnDeviceSpeechRecognizer created successfully")
                    return speechRecognizer
                } catch (e: Exception) {
                    Log.w("STTEngine", "OnDeviceSpeechRecognizer failed to initialize: ${e.message}")
                }
            }

            // Tier 2: Explicit Android System Intelligence AiAi On-Device Speech Service (Samsung/Pixel)
            if (!fallbackToStandard) {
                val aiaiComponent = android.content.ComponentName(
                    "com.google.android.as",
                    "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"
                )
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, aiaiComponent).apply {
                        setRecognitionListener(this@STTEngine)
                    }
                    isOnDeviceRecognizerActive = true
                    Log.i("STTEngine", "AiAi On-Device SpeechRecognizer created successfully")
                    return speechRecognizer
                } catch (e: Exception) {
                    Log.w("STTEngine", "AiAi Component creation failed: ${e.message}")
                }
            }

            // Tier 3: Standard system SpeechRecognizer (handles Samsung, Pixel, Vivo, Realme natively)
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@STTEngine)
                    }
                    isOnDeviceRecognizerActive = false
                    Log.i("STTEngine", "Standard system SpeechRecognizer created successfully")
                    return speechRecognizer
                } catch (e: Exception) {
                    Log.w("STTEngine", "Default SpeechRecognizer creation failed: ${e.message}")
                }
            }

            // Tier 3: Query package manager for any registered RecognitionService component
            val queryIntent = Intent("android.speech.RecognitionService")
            val resolveInfos = context.packageManager.queryIntentServices(queryIntent, 0)
            for (ri in resolveInfos) {
                val sInfo = ri.serviceInfo ?: continue
                val component = android.content.ComponentName(sInfo.packageName, sInfo.name)
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, component).apply {
                        setRecognitionListener(this@STTEngine)
                    }
                    isOnDeviceRecognizerActive = false
                    Log.i("STTEngine", "Custom SpeechRecognizer created with component: $component")
                    return speechRecognizer
                } catch (e: Exception) {
                    Log.w("STTEngine", "Failed to create recognizer with component $component: ${e.message}")
                }
            }

            Log.w("STTEngine", "SpeechRecognizer not available on this device")
        } catch (e: Exception) {
            Log.e("STTEngine", "Failed to create SpeechRecognizer: ${e.message}")
        }
        return speechRecognizer
    }

    fun loadLanguageModel(language: Language) {
        this.activeLanguage = language
        this.fallbackToStandard = false
        this.useRelaxedOffline = false
        this.fallbackLangTag = null
        endpointDetector.configureForLanguage(language)
    }

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            isListening = true
            lastRecognizedText = ""
            currentUtteranceId = System.currentTimeMillis()
            endpointDetector.reset()
            onMicrophoneStateChange?.invoke(true)

            val recognizer = ensureSpeechRecognizer()
            if (recognizer == null) {
                isListening = false
                Log.e("STTEngine", "Cannot startListening: recognizer is null")
                return@post
            }

            val langTag = fallbackLangTag ?: getLanguageTag(activeLanguage)
            lastAttemptedLangTag = langTag

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra("android.speech.extra.DICTATION_MODE", true)

                // Only enforce EXTRA_PREFER_OFFLINE when on-device engine is active and not relaxed
                if (isOnDeviceRecognizerActive && !useRelaxedOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

                // Generous timeouts so user speech is not cut off prematurely
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)

                val additional = ArrayList<String>()
                if (langTag != "en-IN") additional.add("en-IN")
                if (langTag != "en-US") additional.add("en-US")
                if (langTag != "hi-IN") additional.add("hi-IN")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putStringArrayListExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", additional)
                }
            }

            try {
                recognizer.startListening(intent)
                Log.i("STTEngine", "Started listening in $langTag (OfflinePreferred: ${!useRelaxedOffline}, Continuous: $isContinuousMode)")
            } catch (e: Exception) {
                isListening = false
                Log.e("STTEngine", "Failed to start listening: ${e.message}")
            }
        }
    }

    fun stopListening(fallbackIfEmpty: String? = null): String {
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }

        mainHandler.postDelayed({
            if (lastRecognizedText.isNotBlank()) {
                val text = lastRecognizedText
                lastRecognizedText = ""
                finalizeUtterance(text)
            }
        }, 250L)

        return lastRecognizedText
    }

    private fun finalizeUtterance(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        Log.i("STTEngine", "Finalized utterance: $clean")
        _transcriptionFlow.tryEmit(
            TranscriptionResult(
                text = clean,
                isFinal = true,
                language = activeLanguage,
                messageId = currentUtteranceId
            )
        )
        onUtteranceFinalized?.invoke(clean, activeLanguage)
        endpointDetector.reset()
        _audioLevelFlow.value = 0f
        lastRecognizedText = ""
        onMicrophoneStateChange?.invoke(false)

        if (isContinuousMode) {
            // Realtime call pipeline: auto-resume listening for next conversational turn
            mainHandler.postDelayed({
                if (isContinuousMode && !isListening) {
                    startListening()
                }
            }, 300L)
        }
    }

    private fun getLanguageTag(lang: Language): String {
        return when (lang) {
            Language.HINDI -> "hi-IN"
            Language.GUJARATI -> "gu-IN"
            Language.MARATHI -> "mr-IN"
            Language.KANNADA -> "kn-IN"
            Language.MALAYALAM -> "ml-IN"
            Language.TAMIL -> "ta-IN"
            Language.TELUGU -> "te-IN"
            Language.ODIA -> "or-IN"
            Language.BENGALI -> "bn-IN"
            Language.ENGLISH -> "en-IN"
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partial = matches[0].trim()
            if (partial.isNotEmpty()) {
                lastRecognizedText = partial
                Log.d("STTEngine", "Live partial STT: $partial")
                _transcriptionFlow.tryEmit(
                    TranscriptionResult(
                        text = partial,
                        isFinal = false,
                        language = activeLanguage,
                        messageId = currentUtteranceId
                    )
                )

                if (isContinuousMode) {
                    val (_, shouldFinalize) = endpointDetector.onFrame(isSpeech = true, currentPartialText = partial)
                    if (shouldFinalize) {
                        stopListening()
                    }
                }
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = if (!matches.isNullOrEmpty()) matches[0] else lastRecognizedText
        lastRecognizedText = ""
        if (text.isNotBlank()) {
            finalizeUtterance(text)
        } else if (isContinuousMode) {
            mainHandler.postDelayed({
                if (isContinuousMode && !isListening) {
                    startListening()
                }
            }, 250L)
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        val normalized = ((rmsdB + 2.0f) / 12.0f).coerceIn(0.0f, 1.0f)
        _audioLevelFlow.value = normalized

        if (isListening && isContinuousMode) {
            val isSpeech = rmsdB > 1.8f
            val (_, shouldFinalize) = endpointDetector.onFrame(
                isSpeech = isSpeech,
                currentPartialText = lastRecognizedText
            )
            if (shouldFinalize && lastRecognizedText.isNotBlank()) {
                stopListening()
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _endpointStateFlow.value = EndpointState.IDLE
    }

    override fun onBeginningOfSpeech() {
        if (isContinuousMode) {
            endpointDetector.onFrame(isSpeech = true, currentPartialText = lastRecognizedText)
        }
    }

    override fun onEndOfSpeech() {
        if (isContinuousMode) {
            val (_, shouldFinalize) = endpointDetector.onFrame(isSpeech = false, currentPartialText = lastRecognizedText)
            if (shouldFinalize && lastRecognizedText.isNotBlank()) {
                stopListening()
            }
        }
    }

    override fun onError(error: Int) {
        Log.w("STTEngine", "SpeechRecognizer error: $error (isOnDevice: $isOnDeviceRecognizerActive, fallback: $fallbackToStandard, relaxedOffline: $useRelaxedOffline)")
        isListening = false
        _audioLevelFlow.value = 0f
        endpointDetector.reset()
        onMicrophoneStateChange?.invoke(false)

        if (lastRecognizedText.isNotBlank()) {
            val textToSave = lastRecognizedText
            lastRecognizedText = ""
            finalizeUtterance(textToSave)
            return
        }

        // 1. Missing offline language pack (error 12/13) -> switch to en-IN / en-US while staying on-device
        if (error == 12 || error == 13) {
            val currentAttempt = lastAttemptedLangTag
            if (currentAttempt != "en-IN" && currentAttempt != "en-US") {
                Log.w("STTEngine", "Language $currentAttempt unavailable offline (error $error). Switching on-device to en-IN...")
                fallbackLangTag = "en-IN"
            } else if (currentAttempt == "en-IN") {
                Log.w("STTEngine", "Language en-IN unavailable offline (error $error). Switching on-device to en-US...")
                fallbackLangTag = "en-US"
            }
            // Retain on-device engine for offline reliability
            if (isContinuousMode) {
                mainHandler.postDelayed({
                    if (isContinuousMode && !isListening) startListening()
                }, 250L)
            }
            return
        }

        // 2. Cloud recognition network failure (error 2) -> Always switch back to on-device offline recognizer!
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            Log.w("STTEngine", "Network error $error in cloud recognizer. Switching to on-device offline recognizer with en-IN...")
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
            fallbackToStandard = false
            isOnDeviceRecognizerActive = true
            fallbackLangTag = "en-IN"
            if (isContinuousMode) {
                mainHandler.postDelayed({
                    if (isContinuousMode && !isListening) startListening()
                }, 250L)
            }
            return
        }

        // 3. Severe on-device server or bind error -> fallback to standard system recognizer
        if (isOnDeviceRecognizerActive && (error == SpeechRecognizer.ERROR_SERVER || error == 10)) {
            Log.w("STTEngine", "OnDevice recognizer bind error $error, switching to standard system recognizer")
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
            isOnDeviceRecognizerActive = false
            fallbackToStandard = true
            useRelaxedOffline = true
        } else {
            // Standard recognizer encountered an error
            useRelaxedOffline = true
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == 10 ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            ) {
                try { speechRecognizer?.destroy() } catch (_: Exception) {}
                speechRecognizer = null
            }
        }

        // Keep realtime hands-free call pipeline active across normal speech pauses/timeouts
        if (isContinuousMode) {
            mainHandler.postDelayed({
                if (isContinuousMode && !isListening) {
                    startListening()
                }
            }, 250L)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun unload() {
        stopListening()
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }
}
