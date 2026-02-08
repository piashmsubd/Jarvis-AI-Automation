package com.jarvis.ai.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.databinding.ActivityMainBinding
import com.jarvis.ai.service.FloatingOverlayService
import com.jarvis.ai.service.JarvisBrain
import com.jarvis.ai.service.JarvisNotificationListener
import com.jarvis.ai.ui.settings.SettingsActivity
import com.jarvis.ai.util.PreferenceManager
import com.jarvis.ai.voice.VoiceEngine
import com.jarvis.ai.voice.WakeWordService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity — Primary UI for Jarvis AI.
 *
 * Shows the conversation log, voice state indicator, and text input.
 * Connects VoiceEngine, JarvisBrain, WakeWordService, and the
 * Accessibility/Notification services.
 *
 * Handles these incoming intents:
 *   - FloatingOverlayService.ACTION_START_LISTENING: FAB mic tap
 *   - WakeWordService.ACTION_WAKE_WORD_DETECTED: "Hey Jarvis" detected
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_AUDIO_PERMISSION = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefManager: PreferenceManager
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var brain: JarvisBrain

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Broadcast receiver for wake word events from the service
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_WORD_DETECTED) {
                appendToConversation("SYSTEM", "Wake word detected!")
                startVoiceInput()
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)
        voiceEngine = VoiceEngine(this, prefManager)
        brain = JarvisBrain(this, prefManager, voiceEngine)

        voiceEngine.initialize()
        brain.refreshLlmClient()

        setupUI()
        observeVoiceState()
        requestPermissions()

        // Register broadcast receiver for wake word events
        val filter = IntentFilter(WakeWordService.ACTION_WAKE_WORD_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeWordReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wakeWordReceiver, filter)
        }

        // Start wake word service if configured
        startWakeWordIfEnabled()

        // Handle intent that launched this activity
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        brain.refreshLlmClient()
        voiceEngine.refreshCartesiaClient()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(wakeWordReceiver)
        voiceEngine.destroy()
        brain.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    //  Intent Routing                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Handles intents from:
     *   - FloatingOverlayService (FAB tap)
     *   - WakeWordService ("Hey Jarvis" detected)
     *   - BootReceiver (auto-start)
     */
    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            FloatingOverlayService.ACTION_START_LISTENING -> {
                startVoiceInput()
            }
            FloatingOverlayService.ACTION_STOP_LISTENING -> {
                voiceEngine.stopListening()
            }
            WakeWordService.ACTION_WAKE_WORD_DETECTED -> {
                appendToConversation("SYSTEM", "\"Hey Jarvis\" detected! Listening...")
                // Small delay to let the wake word mic release
                binding.root.postDelayed({ startVoiceInput() }, 300)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  UI Setup                                                           //
    // ------------------------------------------------------------------ //

    private fun setupUI() {
        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Send button
        binding.btnSend.setOnClickListener {
            val input = binding.etInput.text.toString().trim()
            if (input.isNotBlank()) {
                processUserInput(input)
                binding.etInput.text?.clear()
            }
        }

        // Enter key sends
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }

        // Mic button
        binding.btnMic.setOnClickListener {
            if (voiceEngine.state.value == VoiceEngine.State.LISTENING) {
                voiceEngine.stopListening()
            } else {
                startVoiceInput()
            }
        }

        // Brain response callback — update conversation log on UI thread
        brain.onResponseCallback = { response ->
            runOnUiThread {
                appendToConversation("JARVIS", response)
            }
        }
    }

    private fun observeVoiceState() {
        lifecycleScope.launch {
            voiceEngine.state.collectLatest { state ->
                val statusText = when (state) {
                    VoiceEngine.State.IDLE -> "Ready"
                    VoiceEngine.State.LISTENING -> "Listening..."
                    VoiceEngine.State.PROCESSING -> "Processing..."
                    VoiceEngine.State.SPEAKING -> "Speaking..."
                    VoiceEngine.State.ERROR -> "Error"
                }
                binding.tvStatus.text = "Status: $statusText"
                binding.tvStatus.setTextColor(
                    when (state) {
                        VoiceEngine.State.LISTENING -> 0xFF1A73E8.toInt()
                        VoiceEngine.State.SPEAKING -> 0xFF00BCD4.toInt()
                        VoiceEngine.State.ERROR -> 0xFFFF5722.toInt()
                        else -> 0xFF4CAF50.toInt()
                    }
                )
            }
        }

        lifecycleScope.launch {
            voiceEngine.lastTranscript.collectLatest { transcript ->
                if (transcript.isNotBlank()) {
                    binding.etInput.setText(transcript)
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Voice & Input Processing                                           //
    // ------------------------------------------------------------------ //

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return
        }

        voiceEngine.startListening { transcript ->
            runOnUiThread {
                if (transcript.isNotBlank()) {
                    processUserInput(transcript)
                }
            }
        }
    }

    private fun processUserInput(input: String) {
        appendToConversation("YOU", input)
        brain.processInput(input)
    }

    private fun appendToConversation(sender: String, message: String) {
        val time = timeFormat.format(Date())
        val formatted = "[$time] $sender: $message\n\n"
        binding.tvConversation.append(formatted)

        // Auto-scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    // ------------------------------------------------------------------ //
    //  Wake Word Service Control                                          //
    // ------------------------------------------------------------------ //

    private fun startWakeWordIfEnabled() {
        if (prefManager.wakeWordEnabled && prefManager.picovoiceAccessKey.isNotBlank()) {
            if (!WakeWordService.isRunning) {
                WakeWordService.start(this)
                appendToConversation("SYSTEM", "Wake word detection active. Say \"Hey Jarvis\" anytime.")
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Status                                                             //
    // ------------------------------------------------------------------ //

    private fun updateStatusIndicators() {
        val provider = prefManager.selectedLlmProvider
        val apiKey = prefManager.getApiKeyForProvider(provider)
        val model = prefManager.getEffectiveModel()

        binding.tvProvider.text = if (apiKey.isNotBlank()) {
            "Provider: ${provider.displayName} / $model"
        } else {
            "Provider: Not configured (tap Settings)"
        }

        val a11yEnabled = JarvisAccessibilityService.isRunning
        val notifEnabled = JarvisNotificationListener.isRunning
        val wakeWordActive = WakeWordService.isRunning

        binding.tvAccessibility.text = buildString {
            append("A11y: ${if (a11yEnabled) "ON" else "OFF"}")
            append(" | Notif: ${if (notifEnabled) "ON" else "OFF"}")
            append(" | Wake: ${if (wakeWordActive) "ON" else "OFF"}")
        }
        binding.tvAccessibility.setTextColor(
            when {
                a11yEnabled && notifEnabled && wakeWordActive -> 0xFF4CAF50.toInt()
                a11yEnabled || notifEnabled -> 0xFFFF9800.toInt()
                else -> 0xFFFF5722.toInt()
            }
        )
    }

    // ------------------------------------------------------------------ //
    //  Permissions                                                        //
    // ------------------------------------------------------------------ //

    private fun requestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                REQUEST_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            val audioGranted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                appendToConversation("SYSTEM", "Audio permission granted. Voice input ready.")
                // Now that we have mic permission, start wake word if enabled
                startWakeWordIfEnabled()
            } else {
                appendToConversation("SYSTEM", "Audio permission denied. Voice input will not work.")
            }
        }
    }
}
