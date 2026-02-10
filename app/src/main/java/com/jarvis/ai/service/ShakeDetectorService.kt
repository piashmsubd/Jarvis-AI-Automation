package com.jarvis.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.ui.main.MainActivity
import kotlin.math.sqrt

/**
 * ShakeDetectorService — Detects phone shake gesture to activate Jarvis.
 * Shake phone → Jarvis activates and starts listening.
 * 
 * Modded by Piash
 */
class ShakeDetectorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "ShakeDetector"
        private const val NOTIFICATION_ID = 3001
        private const val SHAKE_THRESHOLD = 12.0f  // m/s²
        private const val SHAKE_TIME_MS = 500L      // Min time between shakes
        private const val SHAKES_TO_ACTIVATE = 2    // Number of shakes needed

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ShakeDetectorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShakeDetectorService::class.java))
        }
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "Shake detector started")
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        isRunning = false
        Log.i(TAG, "Shake detector stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate acceleration magnitude (minus gravity ~9.8)
        val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > SHAKE_TIME_MS) {
                shakeCount++
                lastShakeTime = now
                Log.d(TAG, "Shake detected! Count: $shakeCount")

                if (shakeCount >= SHAKES_TO_ACTIVATE) {
                    shakeCount = 0
                    onShakeActivate()
                }
            }
        }

        // Reset count after 2 seconds of no shaking
        if (System.currentTimeMillis() - lastShakeTime > 2000) {
            shakeCount = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onShakeActivate() {
        Log.i(TAG, "SHAKE ACTIVATED! Starting Jarvis...")
        if (!LiveVoiceAgent.isActive) {
            LiveVoiceAgent.start(this)
        }
        // Also bring app to front
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_VOICE_SERVICE)
            .setContentTitle("Jarvis Shake Detector")
            .setContentText("Shake phone to activate Jarvis")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
