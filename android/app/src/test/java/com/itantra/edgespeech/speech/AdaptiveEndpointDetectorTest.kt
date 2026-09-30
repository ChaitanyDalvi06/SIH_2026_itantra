package com.itantra.edgespeech.speech

import com.itantra.edgespeech.protocol.Language
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdaptiveEndpointDetectorTest {

    private lateinit var detector: AdaptiveEndpointDetector

    @Before
    fun setUp() {
        detector = AdaptiveEndpointDetector()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(EndpointState.IDLE, detector.state)
    }

    @Test
    fun testTransitionsToSpeechOnVoice() {
        val (state, isFinal) = detector.onFrame(isSpeech = true, currentPartialText = "नमस्ते")
        assertEquals(EndpointState.SPEECH, state)
        assertFalse(isFinal)
    }

    @Test
    fun testTransitionsToPauseOnSilence() {
        detector.onFrame(isSpeech = true, currentPartialText = "नमस्ते")
        val (state, isFinal) = detector.onFrame(isSpeech = false, currentPartialText = "नमस्ते")
        assertEquals(EndpointState.PAUSE_DETECTED, state)
        assertFalse(isFinal)
    }

    @Test
    fun testResumesSpeechFromPauseIfSpeakerContinues() {
        detector.onFrame(isSpeech = true, currentPartialText = "नमस्ते")
        detector.onFrame(isSpeech = false, currentPartialText = "नमस्ते")
        assertEquals(EndpointState.PAUSE_DETECTED, detector.state)

        val (state, isFinal) = detector.onFrame(isSpeech = true, currentPartialText = "नमस्ते भाई")
        assertEquals(EndpointState.SPEECH, state)
        assertFalse(isFinal)
    }

    @Test
    fun testHindiTerminalCueTriggersFinalizationOnShortPause() {
        detector.configureForLanguage(Language.HINDI)
        detector.onFrame(isSpeech = true, currentPartialText = "हम तैयार हैं।")
        Thread.sleep(380) // Exceeds Hindi short silence threshold (350ms)
        val (state, isFinal) = detector.onFrame(isSpeech = false, currentPartialText = "हम तैयार हैं।")
        assertEquals(EndpointState.FINALIZED, state)
        assertTrue(isFinal)
    }

    @Test
    fun testEnglishLanguageConfigApplied() {
        detector.configureForLanguage(Language.ENGLISH)
        detector.onFrame(isSpeech = true, currentPartialText = "Target reached.")
        Thread.sleep(300) // Exceeds English short silence threshold (280ms)
        val (state, isFinal) = detector.onFrame(isSpeech = false, currentPartialText = "Target reached.")
        assertEquals(EndpointState.FINALIZED, state)
        assertTrue(isFinal)
    }

    @Test
    fun testLongSilenceFallbackFinalizes() {
        detector.configureForLanguage(Language.ENGLISH)
        detector.onFrame(isSpeech = true, currentPartialText = "no punctuation here")
        Thread.sleep(1150) // Exceeds English long silence threshold (1100ms)
        val (state, isFinal) = detector.onFrame(isSpeech = false, currentPartialText = "no punctuation here")
        assertEquals(EndpointState.FINALIZED, state)
        assertTrue(isFinal)
    }

    @Test
    fun testResetReturnsToIdle() {
        detector.onFrame(isSpeech = true, currentPartialText = "Test")
        detector.reset()
        assertEquals(EndpointState.IDLE, detector.state)
    }
}
