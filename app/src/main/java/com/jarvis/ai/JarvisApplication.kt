package com.jarvis.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * Application class — Initializes notification channels and global state.
 */
class JarvisApplication : Application() {

    companion object {
        const val TAG = "JarvisApp"

        // Notification channel IDs
        const val CHANNEL_VOICE_SERVICE = "jarvis_voice_service"
        const val CHANNEL_OVERLAY_SERVICE = "jarvis_overlay_service"
        const val CHANNEL_NOTIFICATIONS = "jarvis_notifications"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.i(TAG, "Jarvis AI Application initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Voice processing foreground service channel
            val voiceChannel = NotificationChannel(
                CHANNEL_VOICE_SERVICE,
                "Voice Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the voice assistant active"
                setShowBadge(false)
            }

            // Overlay service channel
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY_SERVICE,
                "Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating action button visible"
                setShowBadge(false)
            }

            // General notification channel
            val notifChannel = NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                "Jarvis Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts and responses from Jarvis"
            }

            manager.createNotificationChannels(
                listOf(voiceChannel, overlayChannel, notifChannel)
            )

            Log.d(TAG, "Notification channels created")
        }
    }
}
