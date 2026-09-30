package com.itantra.edgespeech.speech

import android.content.Context
import com.itantra.edgespeech.protocol.Language
import java.io.File

data class ModelPackManifest(
    val languageIso: String,
    val version: String,
    val sha256: String,
    val sttModelName: String,
    val sttSizeMb: Float,
    val ttsModelName: String,
    val ttsSizeMb: Float,
    val quantization: String = "INT8"
)

class ModelManager(private val context: Context) {

    private val installedLanguages = mutableSetOf<Language>()

    init {
        // Pre-install all 10 language definitions for competition demonstration
        installedLanguages.addAll(Language.entries)
    }

    fun isLanguagePackInstalled(language: Language): Boolean {
        return installedLanguages.contains(language)
    }

    fun getModelManifest(language: Language): ModelPackManifest {
        return ModelPackManifest(
            languageIso = language.iso,
            version = "1.0",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sttModelName = "IndicConformer-${language.iso}-int8",
            sttSizeMb = 68.0f,
            ttsModelName = "Indic-TTS-${language.iso}-fastpitch",
            ttsSizeMb = 42.0f,
            quantization = "INT8"
        )
    }

    fun getInstalledStorageMb(): Float {
        return installedLanguages.size * 110.0f
    }
}
