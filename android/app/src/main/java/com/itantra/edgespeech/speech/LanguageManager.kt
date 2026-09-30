package com.itantra.edgespeech.speech

import android.content.Context
import com.itantra.edgespeech.protocol.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LanguageManager(
    private val sttEngine: STTEngine,
    private val ttsEngine: TTSEngine,
    private val modelManager: ModelManager,
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("edgespeech_prefs", Context.MODE_PRIVATE)
    private val savedLangCode = prefs.getInt("selected_lang_code", Language.ENGLISH.code.toInt())
    private val _activeLanguage = MutableStateFlow(Language.fromCode(savedLangCode.toByte()))
    val activeLanguage: StateFlow<Language> = _activeLanguage

    fun switchLanguage(newLanguage: Language) {
        if (_activeLanguage.value == newLanguage) return

        prefs.edit().putInt("selected_lang_code", newLanguage.code.toInt()).apply()
        sttEngine.unload()

        if (modelManager.isLanguagePackInstalled(newLanguage)) {
            _activeLanguage.value = newLanguage
            sttEngine.loadLanguageModel(newLanguage)
            ttsEngine.setLanguage(newLanguage)
        }
    }

    fun getAvailableLanguages(): List<Language> = Language.entries
}
