package com.jarvis.ai.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.ai.databinding.ActivityMainBinding
import com.jarvis.ai.service.JarvisNotificationListener
import com.jarvis.ai.service.LiveVoiceAgent
import com.jarvis.ai.service.LiveVoiceAgent.Companion.AgentState
import com.jarvis.ai.ui.settings.SettingsActivity
import com.jarvis.ai.util.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity — Single-screen UI for Jarvis AI.
 *
 * One big ACTIVATE button starts the LiveVoiceAgent foreground service.
 * The agent runs a continuous voice loop in the background.
 * This screen just shows the conversation log and status.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)

        setupUI()
        observeAgent()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        updateActivateButton()
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
                if (!hasAudioPermission()) {
                    requestPermissions()
                    return@setOnClickListener
                }
                if (prefManager.getApiKeyForProvider(prefManager.selectedLlmProvider).isBlank()) {
                    appendLog("SYSTEM", "API key set koren Settings e giye!")
                    return@setOnClickListener
                }
                LiveVoiceAgent.start(this)
                appendLog("SYSTEM", "Jarvis activating...")
            }
            updateActivateButton()
        }

        // Text input (alternative to voice)
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                binding.etInput.text?.clear()
                appendLog("YOU (typed)", text)
            }
        }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }
    }

    // ------------------------------------------------------------------ //
    //  Observe LiveVoiceAgent state and conversation log                  //
    // ------------------------------------------------------------------ //

    private fun observeAgent() {
        lifecycleScope.launch {
            LiveVoiceAgent.agentState.collectLatest { state ->
                val (text, color) = when (state) {
                    AgentState.INACTIVE -> "Inactive" to 0xFFFF5722.toInt()
                    AgentState.GREETING -> "Greeting..." to 0xFF00BCD4.toInt()
                    AgentState.LISTENING -> "Listening... Bolun!" to 0xFF1A73E8.toInt()
                    AgentState.THINKING -> "Thinking..." to 0xFFFF9800.toInt()
                    AgentState.SPEAKING -> "Speaking..." to 0xFF00BCD4.toInt()
                    AgentState.PAUSED -> "Paused" to 0xFF888888.toInt()
                }
                binding.tvStatus.text = "Status: $text"
                binding.tvStatus.setTextColor(color)
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
            binding.btnActivate.setBackgroundColor(0xFFFF5722.toInt())
        } else {
            binding.btnActivate.text = "ACTIVATE JARVIS"
            binding.btnActivate.setBackgroundColor(0xFF1A73E8.toInt())
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
