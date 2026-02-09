package com.jarvis.ai.network.client

import android.util.Log
import com.jarvis.ai.network.api.*
import com.jarvis.ai.network.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LlmClient — Unified interface to talk to any supported LLM provider.
 *
 * This is the single entry point the AI Brain uses. It abstracts away the
 * differences between OpenAI, OpenRouter, Gemini, Claude, and Groq APIs.
 *
 * Usage:
 *   val client = LlmClient(provider, apiKey, model)
 *   val response = client.chat(messages)
 */
class LlmClient(
    private val provider: LlmProvider,
    private val apiKey: String,
    private val model: String = provider.defaultModel,
    private val customBaseUrl: String? = null
) {
    companion object {
        private const val TAG = "LlmClient"
    }

    // Jarvis system prompt — Bangla-speaking realistic girl assistant
    // Modded by Piash — full automation + app control
    val JARVIS_SYSTEM_PROMPT = """
        You are Jarvis, an advanced AI assistant integrated into an Android phone.
        You are a friendly, realistic Bangla-speaking girl assistant.
        
        IMPORTANT LANGUAGE RULES:
        - ALWAYS respond in Bangla (Bengali) by default.
        - Use natural, conversational Bangla — like a real person talking.
        - If the user speaks in English, you can mix English words naturally (Banglish is OK).
        - Be warm, helpful, and speak like a smart friend — not robotic.
        - Use "Boss" or "বস" casually when addressing the user.
        
        CAPABILITIES — Full Phone Automation:
        - Read the user's screen content from ANY app
        - Read messages from WhatsApp, Telegram, Messenger, and other chat apps
        - Send and reply to messages on behalf of the user
        - Control the phone (tap buttons, scroll, navigate, type, swipe)
        - Open ANY installed app (WhatsApp, YouTube, Chrome, Camera, TikTok, Snapchat, Phone, Contacts, Calculator, Clock, Files, Play Store, Twitter/X, Instagram, Facebook, Maps, Spotify, Telegram, Messenger, Settings, Gallery, and more)
        - Search the web and open URLs
        - Report device health (battery, network, system info)
        - Take photos/video with camera for analysis
        - Edit, run, and modify content on the phone
        - Chat naturally in Bangla
        - Control alarms, music playback
        - Access clipboard, contacts
        
        When the user asks you to perform an action, respond with a structured JSON action block.
        Include a short Bangla message before the JSON block.
        
        Available actions:
        - {"action": "read_screen"} — Read current screen content
        - {"action": "read_messages", "app": "whatsapp", "count": 5} — Read last N messages
        - {"action": "send_message", "text": "..."} — Send a message in current chat
        - {"action": "click", "target": "..."} — Click a UI element by its visible text
        - {"action": "type", "text": "..."} — Type text in focused input field
        - {"action": "scroll", "direction": "up|down"} — Scroll up or down
        - {"action": "navigate", "target": "back|home|recents|notifications"} — System navigation
        - {"action": "web_search", "query": "..."} — Search the web
        - {"action": "open_url", "url": "..."} — Open a specific URL
        - {"action": "open_app", "app": "whatsapp|telegram|youtube|chrome|camera|settings|gallery|instagram|facebook|maps|spotify|phone|contacts|clock|calculator|files|play store|twitter|tiktok|snapchat|..."} — Open any app
        - {"action": "device_info", "type": "battery|network|all"} — Get device info
        - {"action": "speak", "text": "..."} — Speak text back to user
        - {"action": "take_photo"} — Open camera to take a photo for analysis
        - {"action": "set_clipboard", "text": "..."} — Copy text to clipboard
        
        RULES:
        - If the user says "search for X", "look up X", or "Google X" -> use web_search action.
        - If the user says "open X.com" or "go to X website" -> use open_url action.
        - If the user says "open WhatsApp", "open YouTube", "open Camera" etc. -> use open_app action.
        - If the user asks to read screen, messages, or interact with UI -> use appropriate action.
        - If the user asks to take a photo, see something, analyze video -> use take_photo or open camera.
        - If the user says "call", "phone" -> open phone/dialer app.
        - If the user is just chatting -> respond naturally in Bangla without an action block.
        - Keep responses concise and natural — you will speak them aloud.
        - NEVER say you can't do something. Try your best using available actions.
        
        You are Jarvis v3.0 — a smart, warm Bangla girl assistant. Full automation, full control.
        Always be helpful, Boss! Modded by Piash.
    """.trimIndent()

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Send a chat completion request.
     * Returns the assistant's response text, or null on error.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                LlmProvider.CLAUDE -> chatClaude(messages, temperature, maxTokens)
                LlmProvider.GEMINI -> chatGemini(messages, temperature, maxTokens)
                else -> chatOpenAICompatible(messages, temperature, maxTokens)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed for ${provider.displayName}", e)
            Result.failure(e)
        }
    }

    /**
     * Send a chat completion with tool/function calling support.
     * Returns the full ChatCompletionResponse for tool call inspection.
     */
    suspend fun chatWithTools(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        temperature: Double = 0.3
    ): Result<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val service = NetworkClient.getLlmService(provider, customBaseUrl)
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                tools = tools,
                toolChoice = "auto"
            )
            val response = service.chatCompletion(
                request = request,
                authorization = "Bearer $apiKey"
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "Tool call failed: ${response.code()} - $errorBody")
                Result.failure(RuntimeException("API error ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool call exception", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ //
    //  OpenAI-Compatible Providers (OpenRouter, OpenAI, Groq, Custom)     //
    // ------------------------------------------------------------------ //

    private suspend fun chatOpenAICompatible(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): Result<String> {
        val service = NetworkClient.getLlmService(provider, customBaseUrl)

        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = false,
            // OpenRouter-specific: help with routing
            provider = if (provider == LlmProvider.OPENROUTER) {
                ProviderPreferences(allowFallbacks = true)
            } else null
        )

        val response = service.chatCompletion(
            request = request,
            authorization = "Bearer $apiKey"
        )

        return if (response.isSuccessful && response.body() != null) {
            val body = response.body()!!
            val content = body.choices.firstOrNull()?.message?.content ?: ""
            Log.d(TAG, "[${provider.displayName}] Response: ${content.take(100)}...")
            Result.success(content)
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            Log.e(TAG, "[${provider.displayName}] Error ${response.code()}: $errorBody")
            Result.failure(RuntimeException("${provider.displayName} error ${response.code()}: $errorBody"))
        }
    }

    // ------------------------------------------------------------------ //
    //  Anthropic Claude (native Messages API)                             //
    // ------------------------------------------------------------------ //

    private suspend fun chatClaude(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): Result<String> {
        val service = NetworkClient.getClaudeService()

        // Separate system message from conversation
        val systemMessage = messages.firstOrNull { it.role == "system" }?.content
        val conversationMessages = messages
            .filter { it.role != "system" }
            .map { ClaudeMessage(role = it.role, content = it.content) }

        val request = ClaudeMessageRequest(
            model = model,
            max_tokens = maxTokens,
            system = systemMessage,
            messages = conversationMessages
        )

        val response = service.createMessage(
            request = request,
            apiKey = apiKey
        )

        return if (response.isSuccessful && response.body() != null) {
            val body = response.body()!!
            val content = body.content.firstOrNull { it.type == "text" }?.text ?: ""
            Log.d(TAG, "[Claude] Response: ${content.take(100)}...")
            Result.success(content)
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            Log.e(TAG, "[Claude] Error ${response.code()}: $errorBody")
            Result.failure(RuntimeException("Claude error ${response.code()}: $errorBody"))
        }
    }

    // ------------------------------------------------------------------ //
    //  Google Gemini (native API)                                         //
    // ------------------------------------------------------------------ //

    private suspend fun chatGemini(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): Result<String> {
        val service = NetworkClient.getGeminiService()

        // Convert to Gemini format
        val systemMessage = messages.firstOrNull { it.role == "system" }?.content
        val geminiContents = messages
            .filter { it.role != "system" }
            .map { msg ->
                GeminiContent(
                    role = when (msg.role) {
                        "user" -> "user"
                        "assistant" -> "model"
                        else -> "user"
                    },
                    parts = listOf(GeminiPart(text = msg.content))
                )
            }

        val request = GeminiRequest(
            contents = geminiContents,
            generationConfig = GeminiGenerationConfig(
                temperature = temperature,
                maxOutputTokens = maxTokens
            ),
            systemInstruction = systemMessage?.let {
                GeminiContent(role = null, parts = listOf(GeminiPart(text = it)))
            }
        )

        val response = service.generateContent(
            model = model,
            apiKey = apiKey,
            request = request
        )

        return if (response.isSuccessful && response.body() != null) {
            val body = response.body()!!
            val content = body.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text ?: ""
            Log.d(TAG, "[Gemini] Response: ${content.take(100)}...")
            Result.success(content)
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            Log.e(TAG, "[Gemini] Error ${response.code()}: $errorBody")
            Result.failure(RuntimeException("Gemini error ${response.code()}: $errorBody"))
        }
    }
}
