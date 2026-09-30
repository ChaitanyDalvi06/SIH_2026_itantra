package com.itantra.edgespeech.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VadDetector(
    private val minEnergyThreshold: Float = 320.0f,
    private val zcrMin: Float = 0.06f,
    private val zcrMax: Float = 0.45f,
    private val snrRatio: Float = 2.2f
) {
    private var noiseFloor: Float = 200.0f

    fun isSpeech(framePcm: ShortArray): Boolean {
        try {
            return RingBufferJni.nativeIsSpeech(framePcm, framePcm.size)
        } catch (e: UnsatisfiedLinkError) {
            // Kotlin fallback with adaptive noise floor
            return fallbackSpeechCheck(framePcm)
        }
    }

    fun getNoiseFloor(): Float = noiseFloor

    private fun fallbackSpeechCheck(frame: ShortArray): Boolean {
        if (frame.size < 2) return false
        var sumSquares = 0.0
        var zeroCrossings = 0

        for (i in frame.indices) {
            val v = frame[i].toDouble()
            sumSquares += v * v
            if (i > 0) {
                if ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0)) {
                    zeroCrossings++
                }
            }
        }
        val rms = sqrt(sumSquares / frame.size).toFloat()
        val zcr = zeroCrossings.toFloat() / frame.size

        // Dynamic threshold based on tracked noise floor
        val dynamicThreshold = max(minEnergyThreshold, noiseFloor * snrRatio)
        val isSpeechCandidate = (rms >= dynamicThreshold) && (zcr in zcrMin..zcrMax)

        if (!isSpeechCandidate) {
            // Slowly track background noise during silence (EMA)
            val updated = 0.95f * noiseFloor + 0.05f * rms
            noiseFloor = max(80.0f, min(1500.0f, updated))
            return false
        }

        return true
    }
}
