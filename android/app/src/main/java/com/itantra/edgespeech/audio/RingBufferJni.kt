package com.itantra.edgespeech.audio

object RingBufferJni {
    init {
        try {
            System.loadLibrary("edgespeech")
        } catch (e: UnsatisfiedLinkError) {
            // In unit tests or mock runs without NDK
        }
    }

    external fun nativeInitRingBuffer(capacitySamples: Int)
    external fun nativeWritePcm(pcmData: ShortArray, count: Int): Int
    external fun nativeReadPcm(dest: ShortArray, count: Int): Int
    external fun nativeIsSpeech(framePcm: ShortArray, count: Int): Boolean
    external fun nativeComputeEnergy(framePcm: ShortArray, count: Int): Float
}
