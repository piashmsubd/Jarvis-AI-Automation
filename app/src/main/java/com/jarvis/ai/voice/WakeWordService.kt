package com.jarvis.ai.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.ui.main.MainActivity
import ai.picovoice.porcupine.*

/**
 * WakeWordService — Background foreground service for "Hey Jarvis" detection.
 *
 * Uses Picovoice's Porcupine engine for offline, on-device wake word detection.
 * Porcupine ships with a built-in JARVIS keyword — no custom model training needed.
 *
 * Flow:
 *   1. Service starts as a foreground service with an ongoing notification.
 *   2. PorcupineManager captures microphone audio and runs keyword detection.
 *   3. When "Jarvis" is detected, the service:
 *      a. Vibrates the device for haptic feedback.
 *      b. Sends an intent to MainActivity to start STT listening.
 *   4. Detection continues running even when the app is in the background.
 *
 * ACCESS KEY:
 *   You need a Picovoice Access Key from https://console.picovoice.ai/
 *   Store it in one of these locations:
 *
 *   Option A (Recommended): local.properties
 *     PICOVOICE_ACCESS_KEY=your_key_here
 *     Then inject via BuildConfig in build.gradle.kts (see comments in build.gradle)
 *
 *   Option B: EncryptedSharedPreferences via the Settings UI
 *     The key is read from PreferenceManager.picovoiceAccessKey
 *
 *   Option C: Secrets.kt file (not committed to source control)
 *     object Secrets { const val PICOVOICE_ACCESS_KEY = "..." }
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1003

        /** Action broadcast when wake word is detected. */
        const val ACTION_WAKE_WORD_DETECTED = "com.jarvis.ai.WAKE_WORD_DETECTED"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Convenience method to start the wake word service.
         */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience method to stop the wake word service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    private var porcupineManager: PorcupineManager? = null

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WakeWordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startWakeWordDetection()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopWakeWordDetection()
        isRunning = false
        Log.i(TAG, "WakeWordService destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    //  Porcupine Wake Word Engine                                         //
    // ------------------------------------------------------------------ //

    private fun startWakeWordDetection() {
        val accessKey = getAccessKey()
        if (accessKey.isBlank()) {
            Log.e(TAG, "Picovoice Access Key not configured. Wake word disabled.")
            Log.e(TAG, "Get a free key at https://console.picovoice.ai/")
            stopSelf()
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)   // 0.0 (fewest false positives) to 1.0 (fewest misses)
                .build(applicationContext, wakeWordCallback)

            porcupineManager?.start()

            Log.i(TAG, "Porcupine wake word detection STARTED (keyword: JARVIS)")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to start Porcupine: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopWakeWordDetection() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
            Log.i(TAG, "Porcupine wake word detection STOPPED")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error stopping Porcupine", e)
        }
    }

    /**
     * Called when the wake word ("Jarvis") is detected.
     * keywordIndex indicates which keyword was detected (0 = first keyword).
     */
    private val wakeWordCallback = PorcupineManagerCallback { keywordIndex ->
        Log.i(TAG, "WAKE WORD DETECTED! (index=$keywordIndex)")

        // 1. Haptic feedback
        triggerHapticFeedback()

        // 2. Stop wake word detection temporarily (to avoid mic conflict with STT)
        porcupineManager?.stop()

        // 3. Launch MainActivity and start listening
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_WAKE_WORD_DETECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(launchIntent)

        // 4. Also broadcast so other components can react
        val broadcastIntent = Intent(ACTION_WAKE_WORD_DETECTED)
        sendBroadcast(broadcastIntent)

        // 5. Restart wake word detection after a delay (gives STT time to finish)
        android.os.Handler(mainLooper).postDelayed({
            try {
                porcupineManager?.start()
                Log.d(TAG, "Wake word detection resumed after STT")
            } catch (e: PorcupineException) {
                Log.e(TAG, "Failed to restart Porcupine after STT", e)
            }
        }, 10_000)  // 10 seconds — enough for most voice commands
    }

    // ------------------------------------------------------------------ //
    //  Access Key Resolution                                              //
    // ------------------------------------------------------------------ //

    /**
     * Resolves the Picovoice Access Key from multiple sources (in priority order):
     *   1. BuildConfig field (injected from local.properties at compile time)
     *   2. EncryptedSharedPreferences (entered in Settings UI at runtime)
     */
    private fun getAccessKey(): String {
        // Priority 1: BuildConfig (compile-time from local.properties)
        try {
            val field = com.jarvis.ai.BuildConfig::class.java.getField("PICOVOICE_ACCESS_KEY")
            val value = field.get(null) as? String
            if (!value.isNullOrBlank() && value != "\"\"") {
                Log.d(TAG, "Using Picovoice key from BuildConfig")
                return value
            }
        } catch (_: Exception) {
            // Field doesn't exist — that's fine, check next source
        }

        // Priority 2: EncryptedSharedPreferences
        val prefManager = com.jarvis.ai.util.PreferenceManager(this)
        val prefKey = prefManager.picovoiceAccessKey
        if (prefKey.isNotBlank()) {
            Log.d(TAG, "Using Picovoice key from EncryptedSharedPreferences")
            return prefKey
        }

        return ""
    }

    // ------------------------------------------------------------------ //
    //  Haptic Feedback                                                    //
    // ------------------------------------------------------------------ //

    @Suppress("DEPRECATION")
    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback failed", e)
        }
    }

    // ------------------------------------------------------------------ //
    //  Notification                                                       //
    // ------------------------------------------------------------------ //

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_VOICE_SERVICE)
            .setContentTitle("Jarvis Listening")
            .setContentText("Say \"Hey Jarvis\" to activate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, WakeWordService::class.java).apply { action = "STOP" },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }
}
