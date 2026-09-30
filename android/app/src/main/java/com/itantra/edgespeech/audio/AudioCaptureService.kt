package com.itantra.edgespeech.audio

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itantra.edgespeech.ITantraApplication
import kotlinx.coroutines.*

class AudioCaptureService : Service() {

    private val binder = LocalBinder()
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    @Volatile
    var isPaused = false
        private set
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    var onFrameCaptured: ((ShortArray, Boolean) -> Unit)? = null
    private val vad = VadDetector()

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        startCapture()
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, ITantraApplication.CHANNEL_AUDIO_SERVICE)
            .setContentTitle("iTantra EdgeSpeech Active")
            .setContentText("Local edge audio capture & VAD running (zero cloud audio transmission)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startCapture() {
        if (isRecording) return
        isRecording = true
        isPaused = false
    }

    /**
     * Pauses hardware AudioRecord capture so SpeechRecognizer can have exclusive
     * access to the microphone hardware without collision or ERROR_AUDIO.
     */
    fun pauseCapture() {
        isPaused = true
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    /**
     * Resumes hardware AudioRecord capture after SpeechRecognizer finishes.
     */
    fun resumeCapture() {
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.startRecording()
            }
        } catch (e: Exception) {
            // Safe ignore
        }
        isPaused = false
    }

    fun stopCapture() {
        isRecording = false
        isPaused = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Safe release
        }
        audioRecord = null
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
