package com.jarvis.ai.ui.settings

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.databinding.ActivitySettingsBinding
import com.jarvis.ai.network.model.LlmProvider
import com.jarvis.ai.network.model.TtsProvider
import com.jarvis.ai.service.JarvisNotificationListener
import com.jarvis.ai.util.PreferenceManager
import com.jarvis.ai.voice.WakeWordService

/**
 * Settings UI for configuring API keys, providers, permissions,
 * wake word detection, and TTS mode (WebSocket vs HTTP).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefManager: PreferenceManager

    private val llmProviders = LlmProvider.entries.toTypedArray()
    private val ttsProviders = TtsProvider.entries.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)

        setupProviderSpinner()
        setupTtsSpinner()
        loadSavedSettings()
        setupPermissionButtons()
        setupSaveButton()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    // ------------------------------------------------------------------ //
    //  Setup                                                              //
    // ------------------------------------------------------------------ //

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            llmProviders.map { it.displayName }
        )
        binding.spinnerProvider.adapter = adapter

        binding.spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = llmProviders[pos]
                binding.layoutCustomUrl.visibility = if (selected == LlmProvider.CUSTOM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupTtsSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttsProviders.map { it.displayName }
        )
        binding.spinnerTts.adapter = adapter
    }

    private fun loadSavedSettings() {
        // Provider
        val providerIndex = llmProviders.indexOf(prefManager.selectedLlmProvider)
        if (providerIndex >= 0) binding.spinnerProvider.setSelection(providerIndex)

        // TTS
        val ttsIndex = ttsProviders.indexOf(prefManager.selectedTtsProvider)
        if (ttsIndex >= 0) binding.spinnerTts.setSelection(ttsIndex)

        // Model
        binding.etModel.setText(prefManager.selectedModel)
        binding.etCustomUrl.setText(prefManager.customBaseUrl)

        // API Keys
        binding.etOpenRouterKey.setText(prefManager.openRouterApiKey)
        binding.etOpenAiKey.setText(prefManager.openAiApiKey)
        binding.etGeminiKey.setText(prefManager.geminiApiKey)
        binding.etClaudeKey.setText(prefManager.claudeApiKey)
        binding.etGroqKey.setText(prefManager.groqApiKey)

        // TTS Keys
        binding.etCartesiaKey.setText(prefManager.cartesiaApiKey)
        binding.etCartesiaVoiceId.setText(prefManager.cartesiaVoiceId)
        binding.switchCartesiaWs.isChecked = prefManager.useCartesiaWebSocket
        binding.etSpeechifyKey.setText(prefManager.speechifyApiKey)

        // Wake Word
        binding.switchWakeWord.isChecked = prefManager.wakeWordEnabled
        binding.etPicovoiceKey.setText(prefManager.picovoiceAccessKey)
    }

    // ------------------------------------------------------------------ //
    //  Permission Buttons — with Restricted Settings fix                  //
    // ------------------------------------------------------------------ //

    private fun setupPermissionButtons() {

        // ---------- ACCESSIBILITY ----------
        binding.btnEnableAccessibility.setOnClickListener {
            if (JarvisAccessibilityService.isRunning) {
                Toast.makeText(this, "Already enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // On Android 13+, sideloaded APKs get blocked ("Restricted setting").
            // User must first go to App Info > 3-dot menu > Allow restricted settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showRestrictedSettingsGuide("Accessibility Service") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // ---------- NOTIFICATION LISTENER ----------
        binding.btnEnableNotificationListener.setOnClickListener {
            if (JarvisNotificationListener.isRunning) {
                Toast.makeText(this, "Already enabled!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showRestrictedSettingsGuide("Notification Access") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        // ---------- OVERLAY ----------
        binding.btnEnableOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        // ---------- ALLOW RESTRICTED SETTINGS (direct shortcut) ----------
        binding.btnAllowRestricted.setOnClickListener {
            openAppInfoPage()
        }
    }

    /**
     * Shows an AlertDialog explaining HOW to fix the "Restricted Setting"
     * block on Android 13+, then opens the permission page.
     */
    private fun showRestrictedSettingsGuide(permissionName: String, onProceed: () -> Unit) {
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("Restricted Setting - $permissionName")
            .setMessage(
                "Android 13+ blocks this permission for sideloaded apps.\n\n" +
                "FIRST do this:\n" +
                "1. Tap 'Open App Info' below\n" +
                "2. Tap the 3-dot menu (top-right corner)\n" +
                "3. Tap 'Allow restricted settings'\n" +
                "4. Enter your PIN/fingerprint\n" +
                "5. Come back and tap this button again\n\n" +
                "If you already did this, tap 'Go to Settings' to enable the permission."
            )
            .setPositiveButton("Go to Settings") { _, _ -> onProceed() }
            .setNeutralButton("Open App Info") { _, _ -> openAppInfoPage() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Opens the App Info page for Jarvis AI Automation.
     * From there, user taps 3-dot menu > "Allow restricted settings".
     */
    private fun openAppInfoPage() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    // ------------------------------------------------------------------ //
    //  Save                                                               //
    // ------------------------------------------------------------------ //

    private fun saveSettings() {
        // Provider
        prefManager.selectedLlmProvider = llmProviders[binding.spinnerProvider.selectedItemPosition]
        prefManager.selectedTtsProvider = ttsProviders[binding.spinnerTts.selectedItemPosition]

        // Model
        prefManager.selectedModel = binding.etModel.text.toString().trim()
        prefManager.customBaseUrl = binding.etCustomUrl.text.toString().trim()

        // API Keys
        prefManager.openRouterApiKey = binding.etOpenRouterKey.text.toString().trim()
        prefManager.openAiApiKey = binding.etOpenAiKey.text.toString().trim()
        prefManager.geminiApiKey = binding.etGeminiKey.text.toString().trim()
        prefManager.claudeApiKey = binding.etClaudeKey.text.toString().trim()
        prefManager.groqApiKey = binding.etGroqKey.text.toString().trim()

        // TTS Keys
        prefManager.cartesiaApiKey = binding.etCartesiaKey.text.toString().trim()
        prefManager.cartesiaVoiceId = binding.etCartesiaVoiceId.text.toString().trim()
        prefManager.useCartesiaWebSocket = binding.switchCartesiaWs.isChecked
        prefManager.speechifyApiKey = binding.etSpeechifyKey.text.toString().trim()

        // Wake Word
        val wakeWordWasEnabled = prefManager.wakeWordEnabled
        prefManager.wakeWordEnabled = binding.switchWakeWord.isChecked
        prefManager.picovoiceAccessKey = binding.etPicovoiceKey.text.toString().trim()

        // Start/stop wake word service based on toggle
        if (binding.switchWakeWord.isChecked && prefManager.picovoiceAccessKey.isNotBlank()) {
            if (!WakeWordService.isRunning) {
                WakeWordService.start(this)
                Toast.makeText(this, "Wake word detection started", Toast.LENGTH_SHORT).show()
            }
        } else if (wakeWordWasEnabled && !binding.switchWakeWord.isChecked) {
            WakeWordService.stop(this)
            Toast.makeText(this, "Wake word detection stopped", Toast.LENGTH_SHORT).show()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ------------------------------------------------------------------ //
    //  Permission Status                                                  //
    // ------------------------------------------------------------------ //

    private fun updatePermissionStatuses() {
        val a11yEnabled = JarvisAccessibilityService.isRunning
        binding.btnEnableAccessibility.text = if (a11yEnabled) {
            "Accessibility: ENABLED"
        } else {
            "Enable Accessibility Service"
        }

        val notifEnabled = JarvisNotificationListener.isRunning
        binding.btnEnableNotificationListener.text = if (notifEnabled) {
            "Notification Access: ENABLED"
        } else {
            "Enable Notification Access"
        }

        val overlayEnabled = Settings.canDrawOverlays(this)
        binding.btnEnableOverlay.text = if (overlayEnabled) {
            "Overlay Permission: ENABLED"
        } else {
            "Enable Overlay Permission"
        }

        // Show/hide the restricted settings button based on Android version
        binding.btnAllowRestricted.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
    }
}
