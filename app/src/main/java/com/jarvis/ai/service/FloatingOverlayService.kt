package com.jarvis.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.R
import com.jarvis.ai.ui.main.MainActivity

/**
 * FloatingOverlayService — Draws a floating microphone button over all apps.
 *
 * This is the always-visible FAB that the user taps to activate Jarvis.
 * It uses SYSTEM_ALERT_WINDOW permission to draw over other apps.
 *
 * The button is draggable and can be repositioned anywhere on screen.
 * Tapping it sends an intent to MainActivity to start voice recognition.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START_LISTENING = "com.jarvis.ai.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.jarvis.ai.STOP_LISTENING"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingButton()
        Log.i(TAG, "Floating overlay service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_STOP_LISTENING -> updateButtonState(false)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        Log.i(TAG, "Floating overlay service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    //  Floating Button                                                    //
    // ------------------------------------------------------------------ //

    private fun createFloatingButton() {
        // Inflate a simple ImageButton programmatically
        val button = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            minimumWidth = 150
            minimumHeight = 150
            alpha = 0.9f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 300
        }

        // Handle touch: drag + click
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX - dx  // Inverted because gravity is END
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onFloatingButtonClicked()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(button, params)
            floatingView = button
            Log.d(TAG, "Floating button added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating button", e)
        }
    }

    private fun onFloatingButtonClicked() {
        isListening = !isListening
        updateButtonState(isListening)

        // Send intent to MainActivity to toggle listening
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = if (isListening) ACTION_START_LISTENING else ACTION_STOP_LISTENING
        }
        startActivity(intent)
    }

    private fun updateButtonState(listening: Boolean) {
        isListening = listening
        (floatingView as? ImageButton)?.apply {
            alpha = if (listening) 1.0f else 0.9f
            // In production, you'd swap to a different icon/color when listening
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_OVERLAY_SERVICE)
            .setContentTitle("Jarvis Floating Button")
            .setContentText("Tap the floating mic to talk to Jarvis")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
