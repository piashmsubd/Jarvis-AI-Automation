package com.jarvis.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.network.client.CartesiaTtsClient
import com.jarvis.ai.network.client.CartesiaWebSocketManager
import com.jarvis.ai.network.client.LlmClient
import com.jarvis.ai.network.model.ChatMessage
import com.jarvis.ai.network.model.LlmProvider
import com.jarvis.ai.network.model.TtsProvider
import com.jarvis.ai.ui.main.MainActivity
import com.jarvis.ai.ui.web.WebViewActivity
import com.jarvis.ai.util.DeviceInfoProvider
import com.jarvis.ai.util.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * LiveVoiceAgent — The always-on, autonomous Bangla voice assistant.
 *
 * This is a FOREGROUND SERVICE that runs a continuous voice loop:
 *
 *   ACTIVATE -> GREET -> LISTEN -> THINK -> SPEAK -> LISTEN -> ...
 *                                                    ^_____|
 *
 * The loop runs forever until the user says "Jarvis bondho hoye jao" or
 * taps the DEACTIVATE button. No button presses needed between turns.
 *
 * FULL AUTOMATION:
 *   - Cartesia WebSocket/HTTP TTS (ultra-low latency, Bengali voice)
 *   - Android TTS offline fallback
 *   - Accessibility Service integration for UI automation
 *   - Notification listener for proactive message reading
 *   - Web browser integration for search and URL opening
 *   - Image generation via LLM (DALL-E / Stability AI compatible)
 *   - Full device control (read screen, click, type, scroll, navigate, send messages)
 *
 * Modded by Piash
 */
class LiveVoiceAgent : Service() {

    companion object {
        private const val TAG = "LiveVoiceAgent"
        private const val NOTIFICATION_ID = 2001
        private const val WAKELOCK_TAG = "JarvisAI:VoiceAgent"

        // Agent states
        enum class AgentState {
            INACTIVE,       // Not running
            GREETING,       // Speaking the initial greeting
            LISTENING,      // Waiting for user voice input
            THINKING,       // Sending to LLM, waiting for response
            SPEAKING,       // TTS playing the response
            EXECUTING,      // Performing an action (click, type, scroll, etc.)
            PAUSED          // Temporarily paused (e.g., during a phone call)
        }

        @Volatile
        var instance: LiveVoiceAgent? = null
            private set

        val isActive: Boolean get() = instance != null

        // Observable state
        private val _agentState = MutableStateFlow(AgentState.INACTIVE)
        val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

        // Conversation log for UI
        val conversationLog = MutableSharedFlow<ConversationEntry>(
            replay = 50,
            extraBufferCapacity = 20
        )

        // Input from text field in MainActivity
        val textInput = MutableSharedFlow<String>(extraBufferCapacity = 5)

        // Shutdown keywords
        private val SHUTDOWN_KEYWORDS = listOf(
            "jarvis bondho", "jarvis stop", "jarvis off",
            "বন্ধ হও", "বন্ধ হয়ে যাও", "jarvis bndho",
            "stop jarvis", "shut down", "বন্ধ"
        )

        fun start(context: Context) {
            val intent = Intent(context, LiveVoiceAgent::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveVoiceAgent::class.java))
        }
    }

    // ------------------------------------------------------------------ //
    //  Core components                                                    //
    // ------------------------------------------------------------------ //

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private lateinit var prefManager: PreferenceManager
    private var llmClient: LlmClient? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // TTS backends
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var cartesiaWsManager: CartesiaWebSocketManager? = null
    private var cartesiaClient: CartesiaTtsClient? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // Conversation history
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Notification tracking (to announce new ones)
    private var lastAnnouncedNotifTimestamp = 0L

    // Flag to keep the listen loop going
    @Volatile
    private var keepListening = false

    // ------------------------------------------------------------------ //
    //  Service Lifecycle                                                   //
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefManager = PreferenceManager(this)
        Log.i(TAG, "LiveVoiceAgent created — modby piash")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle STOP action from notification
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("Jarvis active — listening..."))
        acquireWakeLock()
        initializeComponents()
        startConversationLoop()
        listenForTextInput()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        keepListening = false
        _agentState.value = AgentState.INACTIVE

        speechRecognizer?.destroy()
        androidTts?.shutdown()
        cartesiaWsManager?.destroy()
        cartesiaClient?.stop()
        releaseWakeLock()
        scope.cancel()
        ioScope.cancel()

        instance = null
        Log.i(TAG, "LiveVoiceAgent destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    //  Initialization                                                     //
    // ------------------------------------------------------------------ //

    private fun initializeComponents() {
        // LLM client
        val provider = prefManager.selectedLlmProvider
        val apiKey = prefManager.getApiKeyForProvider(provider)
        val model = prefManager.getEffectiveModel()

        if (apiKey.isNotBlank()) {
            llmClient = LlmClient(
                provider = provider,
                apiKey = apiKey,
                model = model,
                customBaseUrl = if (provider == LlmProvider.CUSTOM) prefManager.customBaseUrl else null
            )
        }

        // Speech recognizer
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        // Initialize Cartesia TTS (primary)
        initializeCartesiaTts()

        // Android TTS (offline fallback)
        androidTts = TextToSpeech(this) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                val bengali = Locale("bn", "BD")
                val result = androidTts?.setLanguage(bengali)
                if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    androidTts?.language = Locale.getDefault()
                }
                Log.i(TAG, "Android TTS ready (fallback), language: ${androidTts?.voice?.locale}")
            }
        }
    }

    /**
     * Initialize Cartesia TTS clients — WebSocket (preferred) and HTTP (fallback).
     */
    private fun initializeCartesiaTts() {
        val cartesiaApiKey = prefManager.cartesiaApiKey
        val voiceId = prefManager.cartesiaVoiceId.ifBlank { CartesiaTtsClient.DEFAULT_VOICE_ID }

        if (cartesiaApiKey.isNotBlank()) {
            // HTTP client (fallback)
            cartesiaClient = CartesiaTtsClient(apiKey = cartesiaApiKey, voiceId = voiceId)
            Log.i(TAG, "Cartesia HTTP TTS client initialized")

            // WebSocket client (preferred for ultra-low latency)
            if (prefManager.useCartesiaWebSocket) {
                cartesiaWsManager = CartesiaWebSocketManager(
                    apiKey = cartesiaApiKey,
                    voiceId = voiceId
                ).also { it.connect() }
                Log.i(TAG, "Cartesia WebSocket TTS initialized and connecting")
            }
        } else {
            Log.w(TAG, "Cartesia API key not set — will use Android TTS fallback")
        }
    }

    // ------------------------------------------------------------------ //
    //  TEXT INPUT LISTENER — receive typed text from MainActivity          //
    // ------------------------------------------------------------------ //

    private fun listenForTextInput() {
        scope.launch {
            textInput.collect { typedText ->
                if (typedText.isNotBlank() && keepListening) {
                    emitLog("YOU (typed)", typedText)

                    // Check for shutdown command
                    if (SHUTDOWN_KEYWORDS.any { typedText.lowercase().contains(it) }) {
                        val goodbye = "ঠিক আছে Boss, আমি বন্ধ হয়ে যাচ্ছি।"
                        emitLog("JARVIS", goodbye)
                        speakAndWait(goodbye)
                        stopSelf()
                        return@collect
                    }

                    _agentState.value = AgentState.THINKING
                    updateNotification("Thinking...")
                    val response = askLlm(typedText)
                    emitLog("JARVIS", response)

                    _agentState.value = AgentState.SPEAKING
                    updateNotification("Speaking...")
                    speakAndWait(response)
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  THE MAIN CONVERSATION LOOP                                         //
    // ------------------------------------------------------------------ //

    private fun startConversationLoop() {
        keepListening = true

        scope.launch {
            // Step 1: Greet the user
            _agentState.value = AgentState.GREETING
            val greeting = generateGreeting()
            emitLog("JARVIS", greeting)
            speakAndWait(greeting)

            // Step 2: Announce any pending notifications
            announceNewNotifications()

            // Step 3: Enter the continuous listen -> think -> speak loop
            while (keepListening) {
                _agentState.value = AgentState.LISTENING
                updateNotification("Listening... Bolun Boss!")

                val userSpeech = listenForSpeech()

                if (userSpeech.isBlank()) {
                    // No speech detected — check for new notifications, then listen again
                    announceNewNotifications()
                    delay(500)
                    continue
                }

                emitLog("YOU", userSpeech)

                // Check for shutdown command
                if (SHUTDOWN_KEYWORDS.any { userSpeech.lowercase().contains(it) }) {
                    val goodbye = "ঠিক আছে Boss, আমি বন্ধ হয়ে যাচ্ছি। আবার দরকার হলে ডাকবেন!"
                    emitLog("JARVIS", goodbye)
                    speakAndWait(goodbye)
                    stopSelf()
                    return@launch
                }

                // Step 4: Think — send to LLM
                _agentState.value = AgentState.THINKING
                updateNotification("Thinking...")

                val response = askLlm(userSpeech)
                emitLog("JARVIS", response)

                // Step 5: Speak the response
                _agentState.value = AgentState.SPEAKING
                updateNotification("Speaking...")
                speakAndWait(response)

                // Small pause before listening again
                delay(300)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  GREETING — Jarvis speaks first                                     //
    // ------------------------------------------------------------------ //

    private fun generateGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 6 -> "এত রাতে জেগে আছেন Boss?"
            hour < 12 -> "সুপ্রভাত Boss!"
            hour < 17 -> "Boss, দুপুরের পর কেমন যাচ্ছে?"
            hour < 21 -> "শুভ সন্ধ্যা Boss!"
            else -> "Boss, এখনো জেগে আছেন?"
        }

        val battery = DeviceInfoProvider.getBatteryInfo(this)
        val batteryWarning = if (battery.percentage in 1..20 && !battery.isCharging) {
            " আচ্ছা Boss, battery ${battery.percentage}% আছে — charge দিয়ে দিন।"
        } else ""

        return "$timeGreeting আমি Jarvis, আপনার AI assistant। বলুন কি করতে পারি?$batteryWarning"
    }

    // ------------------------------------------------------------------ //
    //  NOTIFICATION ANNOUNCER — Proactive alerts                          //
    // ------------------------------------------------------------------ //

    private suspend fun announceNewNotifications() {
        val recentNotifs = JarvisNotificationListener.getRecentNotifications(3)
        val newNotifs = recentNotifs.filter { it.timestamp > lastAnnouncedNotifTimestamp }

        if (newNotifs.isEmpty()) return

        for (notif in newNotifs) {
            val announcement = "Boss, ${notif.appName} থেকে message — ${notif.sender} বলেছে: ${notif.text}"
            emitLog("JARVIS", announcement)
            speakAndWait(announcement)
            lastAnnouncedNotifTimestamp = notif.timestamp
        }
    }

    // ------------------------------------------------------------------ //
    //  LLM — Ask the brain                                                //
    // ------------------------------------------------------------------ //

    private suspend fun askLlm(userText: String): String {
        val client = llmClient
        if (client == null) {
            return "Boss, AI provider set up করা হয়নি। Settings এ গিয়ে API key দিন।"
        }

        conversationHistory.add(ChatMessage(role = "user", content = userText))
        if (conversationHistory.size > 20) conversationHistory.removeFirst()

        val messages = buildMessages(client)

        return withContext(Dispatchers.IO) {
            try {
                val result = client.chat(messages)
                result.fold(
                    onSuccess = { response ->
                        conversationHistory.add(ChatMessage(role = "assistant", content = response))

                        // Check for action blocks and execute them
                        val action = tryParseAction(response)
                        if (action != null) {
                            withContext(Dispatchers.Main) {
                                executeAction(action, response)
                            }
                        }

                        // Return the text portion (strip JSON action block for speaking)
                        response.replace(Regex("""\{[^{}]*"action"[^{}]*\}"""), "").trim()
                            .ifBlank { "করে দিচ্ছি Boss!" }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "LLM error", error)
                        "Boss, একটু সমস্যা হচ্ছে — ${error.message?.take(50)}"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM exception", e)
                "Boss, network এ সমস্যা হচ্ছে।"
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  MESSAGE BUILDING — Rich context for the LLM                        //
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
                append(a11y.readScreenTextFlat().take(2000))
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

        // Inject notification context
        val recentNotifs = JarvisNotificationListener.getRecentNotifications(5)
        if (recentNotifs.isNotEmpty()) {
            val ctx = recentNotifs.joinToString("\n") { it.toContextString() }
            messages.add(ChatMessage(role = "system", content = "[RECENT NOTIFICATIONS]\n$ctx"))
        }

        // Inject device info
        val deviceInfo = DeviceInfoProvider.getDeviceSummary(this)
        messages.add(ChatMessage(role = "system", content = "[DEVICE INFO]\n$deviceInfo"))

        // Add conversation history
        messages.addAll(conversationHistory)

        return messages
    }

    // ------------------------------------------------------------------ //
    //  ACTION PARSING & EXECUTION — Full automation                      //
    // ------------------------------------------------------------------ //

    private fun tryParseAction(response: String): JsonObject? {
        return try {
            val pattern = """\{[^{}]*"action"\s*:\s*"[^"]+(?:"[^{}]*)*\}""".toRegex()
            val match = pattern.find(response) ?: return null
            gson.fromJson(match.value, JsonObject::class.java)
        } catch (_: Exception) { null }
    }

    private suspend fun executeAction(action: JsonObject, fullResponse: String = "") {
        val type = action.get("action")?.asString ?: return
        _agentState.value = AgentState.EXECUTING

        Log.d(TAG, "Executing action: $type")

        when (type) {
            // ── Screen Reading ──
            "read_screen" -> {
                val a11y = JarvisAccessibilityService.instance
                if (a11y != null) {
                    val screenText = a11y.readScreenTextFlat()
                    if (screenText.isBlank()) {
                        emitLog("JARVIS", "Screen e kichhu dekhte parchhi na Boss.")
                    } else {
                        emitLog("JARVIS", "Screen content:\n${screenText.take(500)}")
                    }
                } else {
                    emitLog("SYSTEM", "Accessibility Service OFF — Settings e enable koren.")
                }
            }

            // ── Read Messages from Chat Apps ──
            "read_messages" -> {
                val a11y = JarvisAccessibilityService.instance
                val count = action.get("count")?.asInt ?: 5
                if (a11y != null) {
                    val msgs = a11y.readLastMessages(count)
                    val formatted = msgs.joinToString("\n") { "${it.sender}: ${it.text}" }
                    if (formatted.isBlank()) {
                        emitLog("JARVIS", "Kono message pailam na screen e.")
                    } else {
                        emitLog("JARVIS", "Messages:\n$formatted")
                    }
                } else {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── Send Message in WhatsApp/Telegram ──
            "send_message" -> {
                val a11y = JarvisAccessibilityService.instance
                val text = action.get("text")?.asString ?: ""
                if (a11y != null && text.isNotBlank()) {
                    val success = a11y.sendMessage(text)
                    emitLog("JARVIS", if (success) "Message pathano hoyechhe: $text" else "Message pathate parlam na Boss.")
                } else if (a11y == null) {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── Click UI Element ──
            "click" -> {
                val a11y = JarvisAccessibilityService.instance
                val target = action.get("target")?.asString ?: ""
                if (a11y != null && target.isNotBlank()) {
                    val success = a11y.clickNodeByText(target)
                    emitLog("JARVIS", if (success) "'$target' e click korechi." else "'$target' khuje pai ni Boss.")
                } else if (a11y == null) {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── Type Text ──
            "type" -> {
                val a11y = JarvisAccessibilityService.instance
                val text = action.get("text")?.asString ?: ""
                if (a11y != null && text.isNotBlank()) {
                    a11y.typeText(text)
                    emitLog("JARVIS", "Type korechi: $text")
                } else if (a11y == null) {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── Scroll ──
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
                    emitLog("JARVIS", "Scroll $direction korechi.")
                } else {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── System Navigation ──
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
                    emitLog("JARVIS", "Navigated $target.")
                } else {
                    emitLog("SYSTEM", "Accessibility Service OFF.")
                }
            }

            // ── Web Search ──
            "web_search" -> {
                val query = action.get("query")?.asString ?: ""
                if (query.isNotBlank()) {
                    WebViewActivity.launchSearch(this, query)
                    emitLog("JARVIS", "Searching: $query")
                }
            }

            // ── Open URL ──
            "open_url" -> {
                val url = action.get("url")?.asString ?: ""
                if (url.isNotBlank()) {
                    WebViewActivity.launchUrl(this, url)
                    emitLog("JARVIS", "Opening: $url")
                }
            }

            // ── Device Info ──
            "device_info" -> {
                val infoType = action.get("type")?.asString ?: "all"
                val info = when (infoType) {
                    "battery" -> {
                        val b = DeviceInfoProvider.getBatteryInfo(this)
                        "Battery ${b.percentage}%. ${if (b.isCharging) "Charging hocchhe." else "Charge e nei."} Temperature: ${b.temperatureCelsius}°C."
                    }
                    "network" -> {
                        val n = DeviceInfoProvider.getNetworkInfo(this)
                        "Connected via ${n.type}. Download: ${n.downstreamMbps} Mbps, Upload: ${n.upstreamMbps} Mbps."
                    }
                    else -> DeviceInfoProvider.getDeviceSummary(this)
                }
                emitLog("JARVIS", info)
            }

            // ── Speak (explicit) ──
            "speak" -> {
                val text = action.get("text")?.asString ?: fullResponse
                // Will be spoken by the main loop
            }

            // ── Open App (via accessibility) ──
            "open_app" -> {
                val appName = action.get("app")?.asString ?: ""
                if (appName.isNotBlank()) {
                    try {
                        val pm = packageManager
                        val launchIntent = when (appName.lowercase()) {
                            "whatsapp" -> pm.getLaunchIntentForPackage("com.whatsapp")
                            "telegram" -> pm.getLaunchIntentForPackage("org.telegram.messenger")
                            "messenger" -> pm.getLaunchIntentForPackage("com.facebook.orca")
                            "facebook" -> pm.getLaunchIntentForPackage("com.facebook.katana")
                            "instagram" -> pm.getLaunchIntentForPackage("com.instagram.android")
                            "youtube" -> pm.getLaunchIntentForPackage("com.google.android.youtube")
                            "chrome" -> pm.getLaunchIntentForPackage("com.android.chrome")
                            "camera" -> pm.getLaunchIntentForPackage("com.android.camera") ?: pm.getLaunchIntentForPackage("com.sec.android.app.camera")
                            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                            "gallery", "photos" -> pm.getLaunchIntentForPackage("com.google.android.apps.photos") ?: pm.getLaunchIntentForPackage("com.sec.android.gallery3d")
                            "maps" -> pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                            "music", "spotify" -> pm.getLaunchIntentForPackage("com.spotify.music")
                            else -> pm.getLaunchIntentForPackage(appName) // Try as package name
                        }
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            emitLog("JARVIS", "$appName open korechi Boss.")
                        } else {
                            emitLog("JARVIS", "$appName khuje pai ni Boss. Install ache ki?")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open app: $appName", e)
                        emitLog("JARVIS", "$appName open korte parlam na Boss.")
                    }
                }
            }

            else -> {
                Log.d(TAG, "Unknown action: $type")
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  SPEECH-TO-TEXT — Listen for voice (suspending)                      //
    // ------------------------------------------------------------------ //

    private suspend fun listenForSpeech(): String = suspendCancellableCoroutine { cont ->
        val recognizer = speechRecognizer
        if (recognizer == null) {
            cont.resume("", null)
            return@suspendCancellableCoroutine
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")  // Bengali
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                Log.d(TAG, "Heard: $text")
                if (cont.isActive) cont.resume(text, null)
            }

            override fun onError(error: Int) {
                // Timeout / no match = normal, just return empty
                Log.d(TAG, "STT error: $error")
                if (cont.isActive) cont.resume("", null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "STT start failed", e)
            if (cont.isActive) cont.resume("", null)
        }

        cont.invokeOnCancellation {
            try { recognizer.stopListening() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ //
    //  TEXT-TO-SPEECH — Cartesia WebSocket > HTTP > Android TTS            //
    // ------------------------------------------------------------------ //

    /**
     * Speaks text using the best available TTS backend.
     * Priority: Cartesia WebSocket -> Cartesia HTTP -> Android TTS
     *
     * This is a suspending function that waits until speech is complete.
     */
    private suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return

        _agentState.value = AgentState.SPEAKING

        val ttsProvider = prefManager.selectedTtsProvider

        when (ttsProvider) {
            TtsProvider.CARTESIA -> speakWithCartesia(text)
            TtsProvider.SPEECHIFY -> speakWithAndroidTtsFallback(text) // Speechify not implemented
            TtsProvider.ANDROID_TTS -> speakWithAndroidTtsFallback(text)
        }
    }

    /**
     * Cartesia TTS with fallback chain:
     * 1. WebSocket (ultra-low latency, streaming audio)
     * 2. HTTP /tts/bytes (higher latency, simpler)
     * 3. Android TTS (offline last resort)
     */
    private suspend fun speakWithCartesia(text: String) {
        // Try WebSocket first
        val wsManager = cartesiaWsManager
        if (wsManager != null && prefManager.useCartesiaWebSocket) {
            try {
                return suspendCancellableCoroutine { cont ->
                    ioScope.launch {
                        try {
                            wsManager.speak(text) {
                                if (cont.isActive) cont.resume(Unit, null)
                            }
                            Log.d(TAG, "Speaking via Cartesia WebSocket")
                        } catch (e: Exception) {
                            Log.w(TAG, "Cartesia WebSocket speak failed", e)
                            if (cont.isActive) cont.resume(Unit, null)
                        }
                    }
                    cont.invokeOnCancellation {
                        wsManager.cancelCurrentGeneration()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cartesia WebSocket failed, trying HTTP fallback", e)
            }
        }

        // Fall back to Cartesia HTTP
        val httpClient = cartesiaClient
        if (httpClient != null) {
            try {
                val result = httpClient.speak(text)
                if (result.isSuccess) {
                    Log.d(TAG, "Spoke via Cartesia HTTP")
                    return
                }
                Log.w(TAG, "Cartesia HTTP failed: ${result.exceptionOrNull()?.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Cartesia HTTP exception", e)
            }
        }

        // Final fallback: Android TTS
        Log.w(TAG, "All Cartesia TTS failed, using Android TTS")
        speakWithAndroidTtsFallback(text)
    }

    /**
     * Android built-in TTS — offline fallback (always available).
     */
    private suspend fun speakWithAndroidTtsFallback(text: String) {
        if (!androidTtsReady) {
            Log.e(TAG, "Android TTS not ready either — cannot speak")
            return
        }

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "jarvis_${System.currentTimeMillis()}"

            androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (cont.isActive) cont.resume(Unit, null)
                }
                @Deprecated("Deprecated")
                override fun onError(id: String?) {
                    if (cont.isActive) cont.resume(Unit, null)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(Unit, null)
                }
            })

            // Split long text
            val chunks = text.chunked(3900)
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (index == chunks.lastIndex) utteranceId else "${utteranceId}_$index"
                androidTts?.speak(chunk, mode, null, id)
            }

            cont.invokeOnCancellation {
                androidTts?.stop()
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  HELPERS                                                            //
    // ------------------------------------------------------------------ //

    private suspend fun emitLog(sender: String, text: String) {
        val time = timeFormat.format(Date())
        conversationLog.emit(ConversationEntry(sender, text, time))
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(4 * 60 * 60 * 1000L)  // 4 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveVoiceAgent::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_VOICE_SERVICE)
            .setContentTitle("Jarvis AI Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, createNotification(text))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ //
    //  Data class                                                         //
    // ------------------------------------------------------------------ //

    data class ConversationEntry(
        val sender: String,
        val text: String,
        val time: String
    )
}
