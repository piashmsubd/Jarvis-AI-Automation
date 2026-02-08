package com.jarvis.ai.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.network.client.LlmClient
import com.jarvis.ai.network.model.ChatMessage
import com.jarvis.ai.ui.web.WebViewActivity
import com.jarvis.ai.util.DeviceInfoProvider
import com.jarvis.ai.util.PreferenceManager
import com.jarvis.ai.voice.VoiceEngine
import kotlinx.coroutines.*

/**
 * JarvisBrain — The central coordinator that connects LLM, Voice, Accessibility,
 * and Notifications into a single intelligent pipeline.
 *
 * Flow:
 * 1. User speaks or types a command
 * 2. Brain sends it to the LLM with system prompt + screen context
 * 3. LLM returns either plain text (conversational) or a JSON action block
 * 4. Brain parses the response and executes the action via AccessibilityService
 * 5. Brain speaks the result back via VoiceEngine
 */
class JarvisBrain(
    private val context: Context,
    private val prefManager: PreferenceManager,
    private val voiceEngine: VoiceEngine
) {
    companion object {
        private const val TAG = "JarvisBrain"
        private const val MAX_HISTORY = 20  // Keep last N messages for context
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conversation history maintained across turns. */
    private val conversationHistory = mutableListOf<ChatMessage>()

    /** Callback for UI updates. */
    var onResponseCallback: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ //
    //  LLM Client (lazily created, rebuilt when settings change)           //
    // ------------------------------------------------------------------ //

    private var llmClient: LlmClient? = null

    fun refreshLlmClient() {
        val provider = prefManager.selectedLlmProvider
        val apiKey = prefManager.getApiKeyForProvider(provider)
        val model = prefManager.getEffectiveModel()

        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key configured for ${provider.displayName}")
            llmClient = null
            return
        }

        llmClient = LlmClient(
            provider = provider,
            apiKey = apiKey,
            model = model,
            customBaseUrl = if (provider == com.jarvis.ai.network.model.LlmProvider.CUSTOM) {
                prefManager.customBaseUrl
            } else null
        )

        Log.i(TAG, "LLM client initialized: ${provider.displayName} / $model")
    }

    // ------------------------------------------------------------------ //
    //  Process User Input                                                 //
    // ------------------------------------------------------------------ //

    /**
     * Main entry point. Processes a user's natural language command.
     */
    fun processInput(userText: String) {
        scope.launch {
            try {
                val client = llmClient
                if (client == null) {
                    val errorMsg = "No AI provider configured. Please set an API key in Settings."
                    voiceEngine.speak(errorMsg)
                    onResponseCallback?.invoke(errorMsg)
                    return@launch
                }

                // Add user message to history
                conversationHistory.add(ChatMessage(role = "user", content = userText))
                trimHistory()

                // Build messages with system prompt
                val messages = buildMessages(client)

                Log.d(TAG, "Sending to LLM: $userText")

                // Call LLM
                val result = client.chat(messages)

                result.fold(
                    onSuccess = { response ->
                        Log.d(TAG, "LLM response: ${response.take(200)}")

                        // Add assistant response to history
                        conversationHistory.add(ChatMessage(role = "assistant", content = response))

                        // Try to parse as an action, otherwise treat as conversational
                        val action = tryParseAction(response)
                        if (action != null) {
                            executeAction(action, response)
                        } else {
                            // Pure conversational response — speak it
                            voiceEngine.speak(response)
                            onResponseCallback?.invoke(response)
                        }
                    },
                    onFailure = { error ->
                        val errorMsg = "I encountered an error: ${error.message}"
                        Log.e(TAG, errorMsg, error)
                        voiceEngine.speak(errorMsg)
                        onResponseCallback?.invoke(errorMsg)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "processInput failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Action Parsing & Execution                                         //
    // ------------------------------------------------------------------ //

    private fun tryParseAction(response: String): JsonObject? {
        return try {
            // Look for JSON block in the response (could be embedded in text)
            val jsonPattern = """\{[^{}]*"action"\s*:\s*"[^"]+(?:"[^{}]*)*\}""".toRegex()
            val match = jsonPattern.find(response)
            if (match != null) {
                gson.fromJson(match.value, JsonObject::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeAction(action: JsonObject, fullResponse: String) {
        val actionType = action.get("action")?.asString ?: return

        Log.d(TAG, "Executing action: $actionType")

        when (actionType) {
            "read_screen" -> {
                val a11y = JarvisAccessibilityService.instance
                if (a11y != null) {
                    val screenText = a11y.readScreenTextFlat()
                    val summary = "Here's what's on screen:\n$screenText"
                    voiceEngine.speak(if (screenText.isBlank()) "The screen appears empty." else summary)
                    onResponseCallback?.invoke(summary)
                } else {
                    voiceEngine.speak("Accessibility service is not enabled. Please enable it in settings.")
                }
            }

            "read_messages" -> {
                val a11y = JarvisAccessibilityService.instance
                val count = action.get("count")?.asInt ?: 5
                if (a11y != null) {
                    val messages = a11y.readLastMessages(count)
                    val formatted = messages.joinToString("\n") { "${it.sender}: ${it.text}" }
                    val response = if (formatted.isBlank()) {
                        "I couldn't find any messages on screen."
                    } else {
                        "Here are the recent messages:\n$formatted"
                    }
                    voiceEngine.speak(response)
                    onResponseCallback?.invoke(response)
                } else {
                    voiceEngine.speak("Accessibility service is not enabled.")
                }
            }

            "send_message" -> {
                val a11y = JarvisAccessibilityService.instance
                val text = action.get("text")?.asString ?: ""
                if (a11y != null && text.isNotBlank()) {
                    val success = a11y.sendMessage(text)
                    val response = if (success) "Message sent: $text" else "Failed to send message."
                    voiceEngine.speak(response)
                    onResponseCallback?.invoke(response)
                }
            }

            "click" -> {
                val a11y = JarvisAccessibilityService.instance
                val target = action.get("target")?.asString ?: ""
                if (a11y != null && target.isNotBlank()) {
                    val success = a11y.clickNodeByText(target)
                    val response = if (success) "Clicked '$target'." else "Could not find '$target' on screen."
                    voiceEngine.speak(response)
                    onResponseCallback?.invoke(response)
                }
            }

            "type" -> {
                val a11y = JarvisAccessibilityService.instance
                val text = action.get("text")?.asString ?: ""
                if (a11y != null && text.isNotBlank()) {
                    a11y.typeText(text)
                    voiceEngine.speak("Typed: $text")
                }
            }

            "scroll" -> {
                val a11y = JarvisAccessibilityService.instance
                val direction = action.get("direction")?.asString ?: "down"
                if (a11y != null) {
                    val dir = if (direction == "up") {
                        JarvisAccessibilityService.ScrollDirection.UP
                    } else {
                        JarvisAccessibilityService.ScrollDirection.DOWN
                    }
                    a11y.scroll(dir)
                    voiceEngine.speak("Scrolled $direction.")
                }
            }

            "navigate" -> {
                val a11y = JarvisAccessibilityService.instance
                val target = action.get("target")?.asString ?: ""
                if (a11y != null) {
                    when (target) {
                        "back" -> a11y.pressBack()
                        "home" -> a11y.pressHome()
                        "recents" -> a11y.openRecents()
                        "notifications" -> a11y.openNotifications()
                    }
                    voiceEngine.speak("Navigated $target.")
                }
            }

            "device_info" -> {
                val type = action.get("type")?.asString ?: "all"
                val info = when (type) {
                    "battery" -> {
                        val b = DeviceInfoProvider.getBatteryInfo(context)
                        "Battery is at ${b.percentage}%. ${if (b.isCharging) "Currently charging." else "Not charging."} Temperature: ${b.temperatureCelsius}°C."
                    }
                    "network" -> {
                        val n = DeviceInfoProvider.getNetworkInfo(context)
                        "Connected via ${n.type}. Download: ${n.downstreamMbps} Mbps, Upload: ${n.upstreamMbps} Mbps."
                    }
                    else -> DeviceInfoProvider.getDeviceSummary(context)
                }
                voiceEngine.speak(info)
                onResponseCallback?.invoke(info)
            }

            "speak" -> {
                val text = action.get("text")?.asString ?: fullResponse
                voiceEngine.speak(text)
                onResponseCallback?.invoke(text)
            }

            "web_search" -> {
                val query = action.get("query")?.asString ?: ""
                if (query.isNotBlank()) {
                    voiceEngine.speak("Searching the web for: $query")
                    onResponseCallback?.invoke("Searching: $query")
                    WebViewActivity.launchSearch(context, query)
                } else {
                    voiceEngine.speak("I need a search query to look that up.")
                }
            }

            "open_url" -> {
                val url = action.get("url")?.asString ?: ""
                if (url.isNotBlank()) {
                    voiceEngine.speak("Opening $url")
                    onResponseCallback?.invoke("Opening: $url")
                    WebViewActivity.launchUrl(context, url)
                } else {
                    voiceEngine.speak("I need a URL to open.")
                }
            }

            else -> {
                // Unknown action — just speak the full response
                voiceEngine.speak(fullResponse)
                onResponseCallback?.invoke(fullResponse)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Message Building                                                   //
    // ------------------------------------------------------------------ //

    private fun buildMessages(client: LlmClient): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // System prompt
        messages.add(ChatMessage(role = "system", content = client.JARVIS_SYSTEM_PROMPT))

        // Inject current screen context if accessibility is running
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            val screenContext = buildString {
                append("[CURRENT SCREEN CONTEXT]\n")
                append("Foreground app: ${a11y.currentPackage}\n")
                val chatName = a11y.getCurrentChatName()
                if (chatName != null) append("Chat with: $chatName\n")
                append("Screen text:\n")
                append(a11y.readScreenTextFlat().take(2000)) // Cap screen text
            }
            messages.add(ChatMessage(role = "system", content = screenContext))
        }

        // Inject web browsing context if available
        val lastWebTitle = WebViewActivity.lastPageTitle
        val lastWebText = WebViewActivity.lastExtractedText
        if (lastWebTitle.isNotBlank() || lastWebText.isNotBlank()) {
            val webContext = buildString {
                append("[LAST WEB PAGE VISITED]\n")
                append("Title: $lastWebTitle\n")
                append("URL: ${WebViewActivity.lastPageUrl}\n")
                if (lastWebText.isNotBlank()) {
                    append("Content:\n${lastWebText.take(1500)}\n")
                }
            }
            messages.add(ChatMessage(role = "system", content = webContext))
        }

        // Inject recent notifications context
        val recentNotifs = JarvisNotificationListener.getRecentNotifications(5)
        if (recentNotifs.isNotEmpty()) {
            val notifContext = buildString {
                append("[RECENT NOTIFICATIONS]\n")
                recentNotifs.forEach { append("${it.toContextString()}\n") }
            }
            messages.add(ChatMessage(role = "system", content = notifContext))
        }

        // Add conversation history
        messages.addAll(conversationHistory)

        return messages
    }

    private fun trimHistory() {
        while (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeFirst()
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    fun destroy() {
        scope.cancel()
    }
}
