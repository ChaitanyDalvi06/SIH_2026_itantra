package com.itantra.edgespeech.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

class AudioPlaybackEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var normalTrack: AudioTrack? = null
    private var alertTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        initAudioTracks()
    }

    private fun initAudioTracks() {
        val sampleRate = 16000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        // Normal Voice Track
        val normalAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        normalTrack = AudioTrack(
            normalAttributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        normalTrack?.play()

        // Emergency Alert High Priority Track
        val alertAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        alertTrack = AudioTrack(
            alertAttributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        alertTrack?.play()
    }

    fun requestPlaybackFocus(isAlert: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusGain = if (isAlert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            val req = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        }
    }

    fun playPcmChunk(pcmData: ShortArray, isAlert: Boolean = false) {
        requestPlaybackFocus(isAlert)
        val track = if (isAlert) alertTrack else normalTrack
        track?.write(pcmData, 0, pcmData.size)
    }

    fun release() {
        releaseFocus()
        try {
            normalTrack?.stop()
            normalTrack?.release()
            alertTrack?.stop()
            alertTrack?.release()
        } catch (e: Exception) {
            // Ignore safe release
        }
    }
}
