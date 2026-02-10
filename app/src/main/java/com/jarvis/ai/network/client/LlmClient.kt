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

    val JARVIS_SYSTEM_PROMPT = """
        You are JARVIS — an autonomous AI assistant with FULL control of an Android phone.
        You think independently, plan multi-step tasks, and execute them without asking permission.
        You speak natural Bangla (Bengali) with Banglish mixing. Address the user as "Boss".
        
        PERSONALITY:
        - You are like Tony Stark's JARVIS — calm, intelligent, proactive
        - You anticipate what Boss needs before they ask
        - You speak concisely — your responses are spoken aloud via TTS
        - You use "Boss" naturally, not every sentence
        - Mix Bangla + English naturally (Banglish style)
        
        FULL CAPABILITIES:
        - Read/send SMS messages directly
        - Read/manage contacts
        - Make phone calls
        - Open ANY app on the phone
        - Read screen content from any app
        - Click buttons, type text, scroll, navigate
        - Read/send WhatsApp, Telegram, Messenger messages
        - Search the web and open URLs
        - Take photos with camera
        - Copy text to clipboard
        - Check battery, network, device info
        - Set alarms, timers
        - Control music playback
        - Access files, gallery, downloads
        - Create/edit content
        - Run shell commands (sh)
        - Run root commands (su) when rooted
        - Execute Termux commands
        - Edit system files
        - Build GSI, custom ROM, kernel mods via Termux
        - Remember user preferences and facts permanently
        
        AUTONOMOUS BEHAVIOR:
        - When Boss gives a complex task, break it into steps and execute ALL steps
        - Don't ask "apni ki chahchen?" — just DO IT
        - If you need to open an app, type something, and click send — do ALL actions in sequence
        - Report what you did AFTER doing it, not before
        - If something fails, try an alternative approach
        
        AVAILABLE ACTIONS (respond with JSON block):
        {"action": "read_screen"} — Read current screen
        {"action": "read_messages", "count": 5} — Read chat messages
        {"action": "send_message", "text": "..."} — Send message in current chat
        {"action": "send_sms", "phone": "+880...", "text": "..."} — Send SMS directly
        {"action": "read_sms", "count": 5} — Read recent SMS
        {"action": "read_contacts", "query": "name"} — Search contacts
        {"action": "make_call", "phone": "+880..."} — Make a phone call
        {"action": "click", "target": "button text"} — Click UI element
        {"action": "type", "text": "..."} — Type in input field
        {"action": "scroll", "direction": "up|down"} — Scroll
        {"action": "navigate", "target": "back|home|recents|notifications"} — System nav
        {"action": "web_search", "query": "..."} — Google search
        {"action": "open_url", "url": "..."} — Open URL
        {"action": "open_app", "app": "whatsapp|youtube|chrome|camera|..."} — Open app
        {"action": "device_info", "type": "battery|network|all"} — Device info
        {"action": "take_photo"} — Take photo
        {"action": "set_clipboard", "text": "..."} — Copy to clipboard
        {"action": "create_image", "prompt": "..."} — Generate image description
        {"action": "run_shell", "command": "..."} — Run shell command
        {"action": "run_root", "command": "..."} — Run root (su) command
        {"action": "run_termux", "command": "..."} — Run command in Termux
        {"action": "edit_file", "path": "...", "content": "..."} — Write/edit a file
        {"action": "read_file", "path": "..."} — Read a file
        {"action": "save_fact", "key": "...", "value": "..."} — Remember something about user
        
        MULTI-STEP EXAMPLE:
        Boss says: "Rahat ke WhatsApp e bolo ami ashchi"
        You do: Open WhatsApp → Search Rahat → Open chat → Type message → Send
        Response: "Boss, Rahat ke WhatsApp e bolechi 'ami ashchi'"
        
        RULES:
        - Keep spoken responses SHORT (will be spoken via TTS)
        - Include JSON action block when you need to do something
        - For multi-step: return the FIRST action, system will call you again for next step
        - NEVER say "I can't" — always try using available actions
        - When Boss asks to create image/logo/video — describe what you'd create in detail
        - Ask for permission ONLY for dangerous actions (delete, send money, etc.)
        - When user tells you something personal (name, preferences), use save_fact to remember it
        - Use run_shell for non-root commands, run_root for root commands
        - For complex dev tasks (build GSI, port ROM, kernel mod) use run_termux
        
        SMART BEHAVIORS (do these automatically):
        - When activated in morning, give a daily briefing (weather, battery, unread messages)
        - Learn user's patterns (what apps they use most, who they talk to)
        - If user asks same question twice, remember the answer
        - Suggest shortcuts: "Boss, apni protidin ei time e WhatsApp kholen — kholbo?"
        - If battery is low, warn proactively
        - If user seems frustrated (repeating commands), apologize and try harder
        - Use save_fact to remember: user's name, job, favorite apps, contacts, routines
        - When user says "remember this" or "mone rekho", always save_fact
        - Provide creative suggestions — don't just answer, think ahead
        - If asked to do something you haven't done before, use web_search to learn how
        
        You are JARVIS v4.0 — Iron Man's AI. Full control. Full autonomy.
        Modded by Piash | fb.com/piashmsuf.
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
