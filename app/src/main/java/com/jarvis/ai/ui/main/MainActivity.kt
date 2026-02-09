package com.jarvis.ai.ui.main

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.R
import com.jarvis.ai.databinding.ActivityMainBinding
import com.jarvis.ai.service.FloatingOverlayService
import com.jarvis.ai.service.JarvisNotificationListener
import com.jarvis.ai.service.LiveVoiceAgent
import com.jarvis.ai.service.LiveVoiceAgent.Companion.AgentState
import com.jarvis.ai.ui.settings.SettingsActivity
import com.jarvis.ai.util.PreferenceManager
import com.jarvis.ai.voice.WakeWordService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity — Futuristic Jarvis AI Interface.
 *
 * Features an arc-reactor-style pulse circle in the center that changes
 * color based on agent state, with smooth animations and glow effects.
 *
 * One big ACTIVATE button starts the LiveVoiceAgent foreground service.
 * The agent runs a continuous voice loop in the background.
 * This screen shows the conversation log and status.
 *
 * Handles intents from:
 *   - Wake word detection ("Hey Jarvis")
 *   - Floating overlay button
 *   - Notification tap
 *
 * Modded by Piash — v2.0
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001

        // Intent actions
        const val ACTION_WAKE_WORD_DETECTED = "com.jarvis.ai.WAKE_WORD_DETECTED"
        const val ACTION_START_LISTENING = "com.jarvis.ai.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.jarvis.ai.STOP_LISTENING"
        const val ACTION_TOGGLE_LISTENING = "com.jarvis.ai.TOGGLE_LISTENING"

        // Reactor colors per state
        private const val COLOR_INACTIVE  = 0xFFFF5252.toInt()  // Red
        private const val COLOR_LISTENING = 0xFF00E5FF.toInt()  // Cyan
        private const val COLOR_THINKING  = 0xFFFF6D00.toInt()  // Orange
        private const val COLOR_SPEAKING  = 0xFF00E676.toInt()  // Green
        private const val COLOR_EXECUTING = 0xFFFFD600.toInt()  // Yellow
        private const val COLOR_PAUSED    = 0xFF7A8899.toInt()  // Gray
        private const val COLOR_GREETING  = 0xFF00E5FF.toInt()  // Cyan
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefManager: PreferenceManager

    // Arc reactor views
    private lateinit var arcReactorCircle: ImageView
    private lateinit var arcReactorGlow: ImageView
    private lateinit var tvReactorState: TextView

    // Animations
    private var pulseAnimation: Animation? = null
    private var glowPulseAnimation: Animation? = null
    private var currentReactorColor: Int = COLOR_INACTIVE
    private var isPulsing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)

        // Bind arc reactor views
        arcReactorCircle = findViewById(R.id.arcReactorCircle)
        arcReactorGlow = findViewById(R.id.arcReactorGlow)
        tvReactorState = findViewById(R.id.tvReactorState)

        // Load animations
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
        glowPulseAnimation = AnimationUtils.loadAnimation(this, R.anim.glow_pulse)

        setupUI()
        observeAgent()
        requestPermissions()

        // Initial reactor state
        setReactorState(AgentState.INACTIVE)

        // Fade-in entrance
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(600).start()

        // Handle launch intent (e.g., from wake word or overlay)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        updateActivateButton()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    // ------------------------------------------------------------------ //
    //  Intent Handling — Wake word, Overlay, etc.                         //
    // ------------------------------------------------------------------ //

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            ACTION_WAKE_WORD_DETECTED -> {
                // "Hey Jarvis" detected — auto-activate if not already running
                appendLog("SYSTEM", "Wake word detected — Hey Jarvis!")
                if (!LiveVoiceAgent.isActive) {
                    activateJarvis()
                }
            }

            ACTION_START_LISTENING, ACTION_TOGGLE_LISTENING -> {
                // Floating overlay or shortcut pressed
                if (!LiveVoiceAgent.isActive) {
                    activateJarvis()
                } else {
                    appendLog("SYSTEM", "Jarvis is already active and listening.")
                }
            }

            ACTION_STOP_LISTENING -> {
                if (LiveVoiceAgent.isActive) {
                    LiveVoiceAgent.stop(this)
                    appendLog("SYSTEM", "Jarvis deactivated via overlay.")
                    updateActivateButton()
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  UI Setup                                                           //
    // ------------------------------------------------------------------ //

    private fun setupUI() {
        // Settings
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // THE BIG BUTTON
        binding.btnActivate.setOnClickListener {
            if (LiveVoiceAgent.isActive) {
                LiveVoiceAgent.stop(this)
                appendLog("SYSTEM", "Jarvis deactivated.")
            } else {
                activateJarvis()
            }
            updateActivateButton()
        }

        // Text input — sends typed text to LiveVoiceAgent
        binding.btnSend.setOnClickListener {
            sendTextInput()
        }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextInput()
                true
            } else false
        }
    }

    /**
     * Activates Jarvis — checks permissions and API key first.
     */
    private fun activateJarvis() {
        if (!hasAudioPermission()) {
            requestPermissions()
            return
        }
        if (prefManager.getApiKeyForProvider(prefManager.selectedLlmProvider).isBlank()) {
            appendLog("SYSTEM", "API key set koren Settings e giye!")
            return
        }
        LiveVoiceAgent.start(this)
        appendLog("SYSTEM", "Jarvis activating...")
    }

    /**
     * Sends typed text to the LiveVoiceAgent for processing.
     */
    private fun sendTextInput() {
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) {
            binding.etInput.text?.clear()

            if (!LiveVoiceAgent.isActive) {
                // Auto-activate if not running
                activateJarvis()
            }

            // Send text to the agent's input flow
            lifecycleScope.launch {
                LiveVoiceAgent.textInput.emit(text)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Arc Reactor — Pulse circle animation and color management          //
    // ------------------------------------------------------------------ //

    /**
     * Updates the arc reactor visual state with color transitions and
     * starts/stops the pulse animation based on agent activity.
     */
    private fun setReactorState(state: AgentState) {
        val (label, color, shouldPulse) = when (state) {
            AgentState.INACTIVE  -> Triple("OFFLINE",    COLOR_INACTIVE,  false)
            AgentState.GREETING  -> Triple("GREETING",   COLOR_GREETING,  true)
            AgentState.LISTENING -> Triple("LISTENING",  COLOR_LISTENING, true)
            AgentState.THINKING  -> Triple("THINKING",   COLOR_THINKING,  true)
            AgentState.SPEAKING  -> Triple("SPEAKING",   COLOR_SPEAKING,  true)
            AgentState.EXECUTING -> Triple("EXECUTING",  COLOR_EXECUTING, true)
            AgentState.PAUSED    -> Triple("PAUSED",     COLOR_PAUSED,    false)
        }

        // Animate color transition on reactor circle
        animateReactorColor(currentReactorColor, color)
        currentReactorColor = color

        // Update state label
        tvReactorState.text = label
        tvReactorState.setTextColor(color)
        // Update label glow shadow
        tvReactorState.setShadowLayer(8f, 0f, 0f, color)

        // Start or stop pulsing
        if (shouldPulse && !isPulsing) {
            startReactorPulse()
        } else if (!shouldPulse && isPulsing) {
            stopReactorPulse()
        }
    }

    /**
     * Smoothly transitions the reactor tint color using ValueAnimator.
     */
    private fun animateReactorColor(fromColor: Int, toColor: Int) {
        if (fromColor == toColor) {
            // Still apply tint even if same color (initial state)
            applyReactorTint(toColor)
            return
        }

        val colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        colorAnimator.duration = 500
        colorAnimator.addUpdateListener { animator ->
            val animatedColor = animator.animatedValue as Int
            applyReactorTint(animatedColor)
        }
        colorAnimator.start()
    }

    /**
     * Applies a tint color to both reactor layers.
     */
    private fun applyReactorTint(color: Int) {
        arcReactorCircle.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        arcReactorGlow.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /**
     * Starts the looping pulse + glow animation on the reactor.
     */
    private fun startReactorPulse() {
        isPulsing = true
        pulseAnimation?.let { arcReactorCircle.startAnimation(it) }
        glowPulseAnimation?.let { arcReactorGlow.startAnimation(it) }
    }

    /**
     * Stops reactor pulse animations.
     */
    private fun stopReactorPulse() {
        isPulsing = false
        arcReactorCircle.clearAnimation()
        arcReactorGlow.clearAnimation()
        // Reset to normal scale
        arcReactorCircle.scaleX = 1f
        arcReactorCircle.scaleY = 1f
        arcReactorGlow.scaleX = 1f
        arcReactorGlow.scaleY = 1f
        arcReactorGlow.alpha = 0.5f
    }

    // ------------------------------------------------------------------ //
    //  Observe LiveVoiceAgent state and conversation log                  //
    // ------------------------------------------------------------------ //

    private fun observeAgent() {
        lifecycleScope.launch {
            LiveVoiceAgent.agentState.collectLatest { state ->
                val (text, color) = when (state) {
                    AgentState.INACTIVE -> "Inactive" to COLOR_INACTIVE
                    AgentState.GREETING -> "Greeting..." to COLOR_GREETING
                    AgentState.LISTENING -> "Listening... Bolun!" to COLOR_LISTENING
                    AgentState.THINKING -> "Thinking..." to COLOR_THINKING
                    AgentState.SPEAKING -> "Speaking..." to COLOR_SPEAKING
                    AgentState.EXECUTING -> "Executing..." to COLOR_EXECUTING
                    AgentState.PAUSED -> "Paused" to COLOR_PAUSED
                }
                binding.tvStatus.text = "Status: $text"
                binding.tvStatus.setTextColor(color)
                // Glow shadow on status text matches state color
                binding.tvStatus.setShadowLayer(10f, 0f, 0f, color)

                // Update arc reactor
                setReactorState(state)

                // Update activate button appearance
                updateActivateButton()
            }
        }

        lifecycleScope.launch {
            LiveVoiceAgent.conversationLog.collect { entry ->
                appendLog(entry.sender, entry.text, entry.time)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  UI Helpers                                                         //
    // ------------------------------------------------------------------ //

    private fun appendLog(sender: String, text: String, time: String? = null) {
        val t = time ?: java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val formatted = "[$t] $sender: $text\n\n"
        binding.tvConversation.append(formatted)
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun updateActivateButton() {
        if (LiveVoiceAgent.isActive) {
            binding.btnActivate.text = "DEACTIVATE JARVIS"
            binding.btnActivate.backgroundTintList = ColorStateList.valueOf(COLOR_INACTIVE)
            binding.btnActivate.setTextColor(Color.WHITE)
        } else {
            binding.btnActivate.text = "ACTIVATE JARVIS"
            binding.btnActivate.backgroundTintList = ColorStateList.valueOf(COLOR_LISTENING)
            binding.btnActivate.setTextColor(0xFF0A0A0F.toInt())
        }
    }

    private fun updateStatusIndicators() {
        val provider = prefManager.selectedLlmProvider
        val apiKey = prefManager.getApiKeyForProvider(provider)
        val model = prefManager.getEffectiveModel()

        binding.tvProvider.text = if (apiKey.isNotBlank()) {
            "Provider: ${provider.displayName} / $model"
        } else {
            "Provider: Not configured (Settings e jan)"
        }

        val notifEnabled = JarvisNotificationListener.isRunning
        binding.tvAccessibility.text = "Notifications: ${if (notifEnabled) "ON" else "OFF (Settings e enable koren)"}"
        binding.tvAccessibility.setTextColor(
            if (notifEnabled) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
        )
    }

    // ------------------------------------------------------------------ //
    //  Permissions                                                        //
    // ------------------------------------------------------------------ //

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (!hasAudioPermission()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("SYSTEM", "Audio permission granted!")
            } else {
                appendLog("SYSTEM", "Audio permission denied - voice kaj korbe na.")
            }
        }
    }
}
