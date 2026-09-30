package com.itantra.edgespeech.speech

import com.itantra.edgespeech.protocol.Language
import org.junit.Assert.*
import org.junit.Test

class CodeMixingEngineTest {

    @Test
    fun testRomanizedHinglishDetection() {
        val text = "bhai jaldi help bhejo sector 4 me bridge toot gaya hai"
        val analysis = CodeMixingEngine.analyzeAndOptimize(text)

        assertEquals(DetectedScript.LATIN_HINGLISH, analysis.detectedScript)
        assertTrue(analysis.isCodeMixed)
        assertTrue(analysis.codeMixingRatio > 0.3f)
        assertEquals(Language.HINDI, analysis.recommendedTtsLanguage)

        // Verify that Romanized Hindi words are converted to Devanagari while English words stay intact
        assertTrue(analysis.ttsOptimizedText.contains("भाई"))
        assertTrue(analysis.ttsOptimizedText.contains("जल्दी"))
        assertTrue(analysis.ttsOptimizedText.contains("help"))
        assertTrue(analysis.ttsOptimizedText.contains("भेजो"))
        assertTrue(analysis.ttsOptimizedText.contains("sector"))
        assertTrue(analysis.ttsOptimizedText.contains("bridge"))
        assertTrue(analysis.ttsOptimizedText.contains("टूट"))
    }

    @Test
    fun testPureDevanagari() {
        val text = "हम सब सुरक्षित स्थान पर पहुँच गए हैं।"
        val analysis = CodeMixingEngine.analyzeAndOptimize(text)

        assertEquals(DetectedScript.DEVANAGARI, analysis.detectedScript)
        assertEquals(Language.HINDI, analysis.recommendedTtsLanguage)
        assertFalse(analysis.isCodeMixed)
    }

    @Test
    fun testPureEnglish() {
        val text = "All team members are safe and accounted for."
        val analysis = CodeMixingEngine.analyzeAndOptimize(text)

        assertEquals(DetectedScript.PURE_ENGLISH, analysis.detectedScript)
        assertEquals(Language.ENGLISH, analysis.recommendedTtsLanguage)
        assertFalse(analysis.isCodeMixed)
    }

    @Test
    fun testColloquialSlangExpansion() {
        val text = "plz send help asap"
        val analysis = CodeMixingEngine.analyzeAndOptimize(text)

        assertTrue(analysis.normalizedText.contains("please"))
        assertTrue(analysis.normalizedText.contains("as soon as possible"))
    }

    @Test
    fun testContextEngineWithHinglish() {
        val result = ContextEngine.analyze("bhai jaldi ambulance bhejo bridge ke paas 2 casualties hain")
        assertEquals(TacticalIntent.MEDICAL_EVAC, result.intent)
        assertTrue(result.urgency == UrgencyLevel.CRITICAL || result.urgency == UrgencyLevel.HIGH)
        assertTrue(result.entities.any { it.category == "CASUALTY" || it.category == "LOCATION" })
    }
}
