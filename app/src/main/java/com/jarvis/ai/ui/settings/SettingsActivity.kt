package com.jarvis.ai.ui.settings

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

    companion object {
        private const val TAG = "SettingsActivity"

        /**
         * The AppOps constant for ACCESS_RESTRICTED_SETTINGS.
         * Not in the public SDK until later, but the string is stable since Android 13.
         */
        private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefManager: PreferenceManager

    private val llmProviders = LlmProvider.entries.toTypedArray()
    private val ttsProviders = TtsProvider.entries.toTypedArray()

    /**
     * Tracks which permission the user was trying to enable before being
     * redirected to App Info to allow restricted settings.
     * When they come back (onResume), we auto-retry opening that permission page.
     */
    private var pendingPermissionAction: PendingPermission? = null

    /** Flag: user went to App Info to allow restricted settings. */
    private var awaitingRestrictedSettingsReturn = false

    private enum class PendingPermission {
        ACCESSIBILITY,
        NOTIFICATION_LISTENER
    }

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

        // --- Auto-retry: user just came back from App Info ---
        if (awaitingRestrictedSettingsReturn && pendingPermissionAction != null) {
            awaitingRestrictedSettingsReturn = false

            val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                areRestrictedSettingsAllowed()
            } else {
                true
            }

            if (allowed) {
                // Restricted settings are now allowed — go straight to the permission page
                when (pendingPermissionAction) {
                    PendingPermission.ACCESSIBILITY -> {
                        Toast.makeText(
                            this,
                            "Restricted settings allowed! Opening Accessibility...",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PendingPermission.NOTIFICATION_LISTENER -> {
                        Toast.makeText(
                            this,
                            "Restricted settings allowed! Opening Notification Access...",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    null -> { /* nothing */ }
                }
                pendingPermissionAction = null
            } else {
                // Still not allowed — let the user know
                Toast.makeText(
                    this,
                    "Restricted settings still NOT allowed.\n" +
                        "\"Allow restricted settings\" অপশনটি এখনও চালু হয়নি।",
                    Toast.LENGTH_LONG
                ).show()
                pendingPermissionAction = null
            }
        }
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
                // Show custom URL field for CUSTOM and FREEDOMGPT providers
                binding.layoutCustomUrl.visibility =
                    if (selected == LlmProvider.CUSTOM || selected == LlmProvider.FREEDOMGPT) View.VISIBLE else View.GONE

                // Auto-fill the URL when FreedomGPT is selected
                if (selected == LlmProvider.FREEDOMGPT) {
                    binding.etCustomUrl.setText(LlmProvider.FREEDOMGPT.defaultBaseUrl)
                }
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
            // Check the AppOps flag first — only show the guide if NOT yet allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areRestrictedSettingsAllowed()) {
                showRestrictedSettingsGuide(
                    permissionName = "Accessibility Service",
                    pendingPermission = PendingPermission.ACCESSIBILITY
                ) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areRestrictedSettingsAllowed()) {
                showRestrictedSettingsGuide(
                    permissionName = "Notification Access",
                    pendingPermission = PendingPermission.NOTIFICATION_LISTENER
                ) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && areRestrictedSettingsAllowed()) {
                Toast.makeText(
                    this,
                    "Restricted settings already allowed!\n\u0987\u09A4\u09BF\u09AE\u09A7\u09CD\u09AF\u09C7 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09AF\u09BC\u09C7\u099B\u09C7!",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            openAppInfoPage(pendingPermission = null)
        }
    }

    // ------------------------------------------------------------------ //
    //  Restricted Settings detection (Android 13+ / API 33+)              //
    // ------------------------------------------------------------------ //

    /**
     * Checks whether this app has been granted the
     * ACCESS_RESTRICTED_SETTINGS appop by the user.
     *
     * On Android 13 (TIRAMISU) and above, sideloaded APKs are blocked
     * from enabling Accessibility Service and Notification Listener
     * until the user explicitly allows "restricted settings" from App Info.
     *
     * Returns `true` if:
     *   - The device is below Android 13 (restriction doesn't exist), OR
     *   - The appop is MODE_ALLOWED.
     *
     * Returns `false` if the appop is not allowed or we can't determine.
     */
    private fun areRestrictedSettingsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true // restriction doesn't exist below Android 13
        }
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                OP_ACCESS_RESTRICTED_SETTINGS,
                android.os.Process.myUid(),
                packageName
            )
            Log.d(TAG, "ACCESS_RESTRICTED_SETTINGS mode=$mode (0=ALLOWED, 1=IGNORED, 2=ERRORED, 3=DEFAULT)")
            // MODE_ALLOWED = 0;  anything else means not yet allowed
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Could not check ACCESS_RESTRICTED_SETTINGS appop", e)
            // If we can't check, assume not allowed so the guide is shown
            false
        }
    }

    // ------------------------------------------------------------------ //
    //  Restricted Settings guide dialog                                    //
    // ------------------------------------------------------------------ //

    /**
     * Shows a detailed AlertDialog explaining HOW to fix the
     * "Restricted Setting" block on Android 13+.
     *
     * Includes step-by-step instructions in both English and Bangla.
     * If the user taps "Open App Info", we track [pendingPermission]
     * so that [onResume] can auto-navigate to the right settings page
     * after the user comes back.
     */
    private fun showRestrictedSettingsGuide(
        permissionName: String,
        pendingPermission: PendingPermission,
        onProceed: () -> Unit
    ) {
        val restrictedAllowed = areRestrictedSettingsAllowed()

        // If already allowed, just go straight to the settings page
        if (restrictedAllowed) {
            onProceed()
            return
        }

        val message = buildString {
            appendLine("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550")
            appendLine("  Android 13+ Restricted Settings")
            appendLine("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550")
            appendLine()
            appendLine("Android 13+ blocks \"$permissionName\" for")
            appendLine("apps installed outside the Play Store.")
            appendLine()
            appendLine("You MUST first allow restricted settings.")
            appendLine()
            appendLine("\u2500\u2500\u2500\u2500\u2500 Steps (English) \u2500\u2500\u2500\u2500\u2500")
            appendLine("1. Tap \"Open App Info\" below")
            appendLine("2. On the App Info page, tap the")
            appendLine("   \u22EE (3-dot menu) at the TOP RIGHT")
            appendLine("3. Tap \"Allow restricted settings\"")
            appendLine("4. Enter your PIN / Pattern / Fingerprint")
            appendLine("5. Press BACK \u2014 the app will automatically")
            appendLine("   open the $permissionName page for you!")
            appendLine()
            appendLine("\u2500\u2500\u2500\u2500\u2500 \u09A7\u09BE\u09AA\u09B8\u09AE\u09C2\u09B9 (\u09AC\u09BE\u0982\u09B2\u09BE) \u2500\u2500\u2500\u2500\u2500")
            appendLine("\u09E7. \u09A8\u09BF\u099A\u09C7 \"Open App Info\" \u09AC\u09BE\u099F\u09A8\u09C7 \u099F\u09CD\u09AF\u09BE\u09AA \u0995\u09B0\u09C1\u09A8")
            appendLine("\u09E8. App Info \u09AA\u09C7\u099C\u09C7 \u09A1\u09BE\u09A8 \u09A6\u09BF\u0995\u09C7 \u0989\u09AA\u09B0\u09C7\u09B0 \u0995\u09CB\u09A3\u09BE\u09AF\u09BC")
            appendLine("   \u22EE (\u09A4\u09BF\u09A8-\u09A1\u099F \u09AE\u09C7\u09A8\u09C1) \u099F\u09CD\u09AF\u09BE\u09AA \u0995\u09B0\u09C1\u09A8")
            appendLine("\u09E9. \"Allow restricted settings\" \u099F\u09CD\u09AF\u09BE\u09AA \u0995\u09B0\u09C1\u09A8")
            appendLine("   (\u09B8\u09C0\u09AE\u09BE\u09AC\u09A6\u09CD\u09A7 \u09B8\u09C7\u099F\u09BF\u0982\u09B8 \u0985\u09A8\u09C1\u09AE\u09A4\u09BF \u09A6\u09BF\u09A8)")
            appendLine("\u09EA. \u0986\u09AA\u09A8\u09BE\u09B0 PIN / \u09AA\u09CD\u09AF\u09BE\u099F\u09BE\u09B0\u09CD\u09A8 / \u09AB\u09BF\u0999\u09CD\u0997\u09BE\u09B0\u09AA\u09CD\u09B0\u09BF\u09A8\u09CD\u099F \u09A6\u09BF\u09A8")
            appendLine("\u09EB. BACK \u099A\u09BE\u09AA\u09C1\u09A8 \u2014 \u0985\u09CD\u09AF\u09BE\u09AA \u09B8\u09CD\u09AC\u09AF\u09BC\u0982\u0995\u09CD\u09B0\u09BF\u09AF\u09BC\u09AD\u09BE\u09AC\u09C7")
            appendLine("   $permissionName \u09B8\u09C7\u099F\u09BF\u0982\u09B8 \u0996\u09C1\u09B2\u09AC\u09C7!")
            appendLine()
            appendLine("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            appendLine("\u09AF\u09A6\u09BF \u0987\u09A4\u09BF\u09AE\u09A7\u09CD\u09AF\u09C7 \u0995\u09B0\u09C7 \u09A5\u09BE\u0995\u09C7\u09A8, \"Go to Settings\"")
            appendLine("\u099F\u09CD\u09AF\u09BE\u09AA \u0995\u09B0\u09C1\u09A8\u0964")
            appendLine("If you already did this, tap \"Go to Settings\".")
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
            .setTitle("\u26A0\uFE0F Restricted Setting \u2014 $permissionName")
            .setMessage(message)
            .setPositiveButton("Go to Settings") { _, _ ->
                // User claims they already allowed — try directly
                onProceed()
            }
            .setNeutralButton("Open App Info") { _, _ ->
                openAppInfoPage(pendingPermission)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    /**
     * Opens the system App Info page for this package.
     *
     * If [pendingPermission] is non-null, we remember it so that
     * [onResume] can automatically navigate to the correct settings
     * page once the user returns after allowing restricted settings.
     */
    private fun openAppInfoPage(pendingPermission: PendingPermission?) {
        this.pendingPermissionAction = pendingPermission
        this.awaitingRestrictedSettingsReturn = pendingPermission != null

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            if (pendingPermission != null) {
                Toast.makeText(
                    this,
                    "\u22EE \u09AE\u09C7\u09A8\u09C1 \u09A5\u09C7\u0995\u09C7 \"Allow restricted settings\" \u099A\u09BE\u09B2\u09C1 \u0995\u09B0\u09C1\u09A8\n" +
                        "Tap \u22EE menu > \"Allow restricted settings\"",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open App Info", e)
            Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show()
            this.pendingPermissionAction = null
            this.awaitingRestrictedSettingsReturn = false
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

        // Show/hide the restricted settings button based on Android version.
        // Also update its label to reflect current status.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.btnAllowRestricted.visibility = View.VISIBLE
            val restricted = areRestrictedSettingsAllowed()
            binding.btnAllowRestricted.text = if (restricted) {
                "Restricted Settings: ALLOWED \u2705"
            } else {
                "Allow Restricted Settings (\u26A0\uFE0F Required)"
            }
        } else {
            binding.btnAllowRestricted.visibility = View.GONE
        }
    }
}
