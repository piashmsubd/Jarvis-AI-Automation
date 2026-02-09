package com.jarvis.ai.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.ai.network.model.LlmProvider
import com.jarvis.ai.network.model.TtsProvider

/**
 * Encrypted preference storage for API keys and settings.
 *
 * All API keys are stored using EncryptedSharedPreferences backed by
 * Android Keystore. This means they're encrypted at rest and cannot
 * be extracted from a rooted device without the encryption key.
 */
class PreferenceManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ------------------------------------------------------------------ //
    //  API Keys                                                           //
    // ------------------------------------------------------------------ //

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENROUTER, value).apply()

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI, value).apply()

    var claudeApiKey: String
        get() = prefs.getString(KEY_CLAUDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLAUDE, value).apply()

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ, value).apply()

    var freedomGptApiKey: String
        get() = prefs.getString(KEY_FREEDOMGPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FREEDOMGPT, value).apply()

    var cartesiaApiKey: String
        get() = prefs.getString(KEY_CARTESIA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CARTESIA, value).apply()

    var speechifyApiKey: String
        get() = prefs.getString(KEY_SPEECHIFY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEECHIFY, value).apply()

    // ------------------------------------------------------------------ //
    //  Provider Selection                                                 //
    // ------------------------------------------------------------------ //

    var selectedLlmProvider: LlmProvider
        get() {
            val name = prefs.getString(KEY_SELECTED_PROVIDER, LlmProvider.OPENROUTER.name)
            return try { LlmProvider.valueOf(name ?: LlmProvider.OPENROUTER.name) }
            catch (_: Exception) { LlmProvider.OPENROUTER }
        }
        set(value) = prefs.edit().putString(KEY_SELECTED_PROVIDER, value.name).apply()

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var selectedTtsProvider: TtsProvider
        get() {
            val name = prefs.getString(KEY_TTS_PROVIDER, TtsProvider.CARTESIA.name)
            return try { TtsProvider.valueOf(name ?: TtsProvider.CARTESIA.name) }
            catch (_: Exception) { TtsProvider.CARTESIA }
        }
        set(value) = prefs.edit().putString(KEY_TTS_PROVIDER, value.name).apply()

    var customBaseUrl: String
        get() = prefs.getString(KEY_CUSTOM_BASE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_BASE_URL, value).apply()

    var cartesiaVoiceId: String
        get() = prefs.getString(KEY_CARTESIA_VOICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CARTESIA_VOICE_ID, value).apply()

    // ------------------------------------------------------------------ //
    //  Wake Word (Picovoice Porcupine)                                    //
    // ------------------------------------------------------------------ //

    var picovoiceAccessKey: String
        get() = prefs.getString(KEY_PICOVOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PICOVOICE, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    /** Use Cartesia WebSocket (true) vs HTTP bytes endpoint (false). */
    var useCartesiaWebSocket: Boolean
        get() = prefs.getBoolean(KEY_CARTESIA_WS, true)
        set(value) = prefs.edit().putBoolean(KEY_CARTESIA_WS, value).apply()

    // ------------------------------------------------------------------ //
    //  Language & Voice Settings                                           //
    // ------------------------------------------------------------------ //

    var sttLanguage: String
        get() = prefs.getString(KEY_STT_LANGUAGE, "bn-BD") ?: "bn-BD"
        set(value) = prefs.edit().putString(KEY_STT_LANGUAGE, value).apply()

    var ttsLanguage: String
        get() = prefs.getString(KEY_TTS_LANGUAGE, "bn-BD") ?: "bn-BD"
        set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()

    var customSystemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var voiceSensitivity: Int
        get() = prefs.getInt(KEY_VOICE_SENSITIVITY, 2)
        set(value) = prefs.edit().putInt(KEY_VOICE_SENSITIVITY, value).apply()
    
    /** Enable continuous listening mode (auto restart after each response) */
    var continuousListeningMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_LISTENING, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING, value).apply()
    
    /** Enable audio feedback beeps for listening state */
    var enableAudioFeedback: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_FEEDBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_FEEDBACK, value).apply()

    // ------------------------------------------------------------------ //
    //  Theme Settings                                                      //
    // ------------------------------------------------------------------ //

    /** Theme index: 0=Jarvis Cyan, 1=Iron Man Red, 2=Hulk Green, 3=Thanos Purple, 4=Custom */
    var themeIndex: Int
        get() = prefs.getInt(KEY_THEME_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_THEME_INDEX, value).apply()

    /** Custom accent color hex (e.g., "#FF5722") */
    var customAccentColor: String
        get() = prefs.getString(KEY_CUSTOM_ACCENT, "#00E5FF") ?: "#00E5FF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_ACCENT, value).apply()

    // ------------------------------------------------------------------ //
    //  Helper: Get API key for selected provider                          //
    // ------------------------------------------------------------------ //

    fun getApiKeyForProvider(provider: LlmProvider): String {
        return when (provider) {
            LlmProvider.OPENROUTER -> openRouterApiKey
            LlmProvider.OPENAI -> openAiApiKey
            LlmProvider.GEMINI -> geminiApiKey
            LlmProvider.CLAUDE -> claudeApiKey
            LlmProvider.GROQ -> groqApiKey
            LlmProvider.FREEDOMGPT -> freedomGptApiKey
            LlmProvider.CUSTOM -> openAiApiKey       // Custom uses generic key
        }
    }

    fun getEffectiveModel(): String {
        val custom = selectedModel
        return custom.ifBlank { selectedLlmProvider.defaultModel }
    }

    // ------------------------------------------------------------------ //
    //  Keys                                                               //
    // ------------------------------------------------------------------ //

    private companion object {
        const val KEY_OPENROUTER = "api_key_openrouter"
        const val KEY_OPENAI = "api_key_openai"
        const val KEY_GEMINI = "api_key_gemini"
        const val KEY_CLAUDE = "api_key_claude"
        const val KEY_GROQ = "api_key_groq"
        const val KEY_FREEDOMGPT = "api_key_freedomgpt"
        const val KEY_CARTESIA = "api_key_cartesia"
        const val KEY_SPEECHIFY = "api_key_speechify"
        const val KEY_SELECTED_PROVIDER = "selected_llm_provider"
        const val KEY_SELECTED_MODEL = "selected_model"
        const val KEY_TTS_PROVIDER = "selected_tts_provider"
        const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        const val KEY_CARTESIA_VOICE_ID = "cartesia_voice_id"
        const val KEY_PICOVOICE = "api_key_picovoice"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_CARTESIA_WS = "cartesia_use_websocket"
        const val KEY_STT_LANGUAGE = "stt_language"
        const val KEY_TTS_LANGUAGE = "tts_language"
        const val KEY_SYSTEM_PROMPT = "custom_system_prompt"
        const val KEY_VOICE_SENSITIVITY = "voice_sensitivity"
        const val KEY_CONTINUOUS_LISTENING = "continuous_listening_mode"
        const val KEY_AUDIO_FEEDBACK = "audio_feedback_enabled"
        const val KEY_THEME_INDEX = "theme_index"
        const val KEY_CUSTOM_ACCENT = "custom_accent_color"
    }
}
