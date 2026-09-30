package com.itantra.edgespeech

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ITantraApplication : Application() {

    companion object {
        const val CHANNEL_AUDIO_SERVICE = "channel_edgespeech_audio"
        const val CHANNEL_ALERTS = "channel_edgespeech_alerts"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioChannel = NotificationChannel(
                CHANNEL_AUDIO_SERVICE,
                "iTantra Audio Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required foreground service for continuous edge speech processing"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "iTantra Priority Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority preemptive walkie-talkie emergency announcements"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(audioChannel)
            manager?.createNotificationChannel(alertChannel)
        }
    }
}
