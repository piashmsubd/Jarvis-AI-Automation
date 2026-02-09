package com.jarvis.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.MediaStore
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
 * CONTINUOUS LOOP: ACTIVATE -> GREET -> LISTEN -> THINK -> SPEAK -> LISTEN -> ...
 *
 * KEY FIX: SpeechRecognizer is recreated before each listen cycle to avoid
 * the Android bug where it stops responding after one use. All operations
 * have timeouts and try-catch to ensure the loop NEVER breaks.
 *
 * Modded by Piash
 */
class LiveVoiceAgent : Service() {

    companion object {
        private const val TAG = "LiveVoiceAgent"
        private const val NOTIFICATION_ID = 2001
        private const val WAKELOCK_TAG = "JarvisAI:VoiceAgent"

        // Timeouts
        private const val STT_TIMEOUT_MS = 15_000L      // Max 15s for one listen
        private const val TTS_TIMEOUT_MS = 30_000L       // Max 30s for TTS to finish
        private const val ACTION_TIMEOUT_MS = 10_000L    // Max 10s for an action

        enum class AgentState {
            INACTIVE, GREETING, LISTENING, THINKING, SPEAKING, EXECUTING, PAUSED
        }

        @Volatile
        var instance: LiveVoiceAgent? = null
            private set

        val isActive: Boolean get() = instance != null

        private val _agentState = MutableStateFlow(AgentState.INACTIVE)
        val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

        val conversationLog = MutableSharedFlow<ConversationEntry>(
            replay = 50, extraBufferCapacity = 20
        )

        val textInput = MutableSharedFlow<String>(extraBufferCapacity = 5)

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

    // TTS backends
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var cartesiaWsManager: CartesiaWebSocketManager? = null
    private var cartesiaClient: CartesiaTtsClient? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // Conversation history
    private val conversationHistory = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var lastAnnouncedNotifTimestamp = 0L

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
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("Jarvis active — listening..."))
        acquireWakeLock()
        requestBatteryOptimizationExemption()
        initializeComponents()
        startConversationLoop()
        listenForTextInput()
        return START_STICKY  // Auto-restart if killed by system
    }

    /** Request ignore battery optimizations so Android doesn't kill us */
    @Suppress("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization request failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        keepListening = false
        _agentState.value = AgentState.INACTIVE

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
            val customUrl = when (provider) {
                LlmProvider.CUSTOM -> prefManager.customBaseUrl
                LlmProvider.FREEDOMGPT -> prefManager.customBaseUrl.ifBlank { provider.defaultBaseUrl }
                else -> null
            }
            llmClient = LlmClient(
                provider = provider,
                apiKey = apiKey,
                model = model,
                customBaseUrl = customUrl
            )
        }

        // Initialize Cartesia TTS (primary)
        initializeCartesiaTts()

        // Android TTS (offline fallback) — uses language from settings
        androidTts = TextToSpeech(this) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                setTtsLanguage()
                Log.i(TAG, "Android TTS ready (fallback)")
            }
        }
    }

    private fun initializeCartesiaTts() {
        val cartesiaApiKey = prefManager.cartesiaApiKey
        val voiceId = prefManager.cartesiaVoiceId.ifBlank { CartesiaTtsClient.DEFAULT_VOICE_ID }

        if (cartesiaApiKey.isNotBlank()) {
            cartesiaClient = CartesiaTtsClient(apiKey = cartesiaApiKey, voiceId = voiceId)
            Log.i(TAG, "Cartesia HTTP TTS initialized")

            if (prefManager.useCartesiaWebSocket) {
                cartesiaWsManager = CartesiaWebSocketManager(
                    apiKey = cartesiaApiKey, voiceId = voiceId
                ).also { it.connect() }
                Log.i(TAG, "Cartesia WebSocket TTS initialized")
            }
        } else {
            Log.w(TAG, "No Cartesia API key — using Android TTS only")
        }
    }

    // ------------------------------------------------------------------ //
    //  TEXT INPUT LISTENER                                                 //
    // ------------------------------------------------------------------ //

    private fun listenForTextInput() {
        scope.launch {
            textInput.collect { typedText ->
                if (typedText.isNotBlank() && keepListening) {
                    try {
                        emitLog("YOU (typed)", typedText)

                        if (SHUTDOWN_KEYWORDS.any { typedText.lowercase().contains(it) }) {
                            emitLog("JARVIS", "ঠিক আছে Boss, বন্ধ হচ্ছি।")
                            safeSpeak("ঠিক আছে Boss, বন্ধ হচ্ছি।")
                            stopSelf()
                            return@collect
                        }

                        _agentState.value = AgentState.THINKING
                        updateNotification("Thinking...")
                        val response = askLlm(typedText)
                        emitLog("JARVIS", response)

                        _agentState.value = AgentState.SPEAKING
                        updateNotification("Speaking...")
                        safeSpeak(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "Text input processing error", e)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  THE MAIN CONVERSATION LOOP — NEVER BREAKS                          //
    // ------------------------------------------------------------------ //

    private fun startConversationLoop() {
        keepListening = true

        scope.launch {
            // Step 1: Greet
            _agentState.value = AgentState.GREETING
            val greeting = generateGreeting()
            emitLog("JARVIS", greeting)
            safeSpeak(greeting)

            // Step 2: Announce pending notifications
            try { announceNewNotifications() } catch (e: Exception) {
                Log.e(TAG, "Notification announce error", e)
            }

            // Step 3: CONTINUOUS LOOP — wrapped in try-catch, NEVER exits
            while (keepListening) {
                try {
                    _agentState.value = AgentState.LISTENING
                    updateNotification("Listening... Bolun Boss!")

                    // Create fresh SpeechRecognizer each time (fixes Android re-use bug)
                    val userSpeech = safeListenForSpeech()

                    if (userSpeech.isBlank()) {
                        // No speech — loop immediately (no delay!)
                        continue
                    }

                    emitLog("YOU", userSpeech)

                    // Shutdown check
                    if (SHUTDOWN_KEYWORDS.any { userSpeech.lowercase().contains(it) }) {
                        val goodbye = "ঠিক আছে Boss, বন্ধ হয়ে যাচ্ছি। আবার ডাকবেন!"
                        emitLog("JARVIS", goodbye)
                        safeSpeak(goodbye)
                        stopSelf()
                        return@launch
                    }

                    // Instant greeting response (no LLM needed - zero latency)
                    val greetingKeywords = listOf(
                        "hello jarvis", "hi jarvis", "hey jarvis", "jarvis",
                        "হ্যালো জার্ভিস", "হেই জার্ভিস", "জার্ভিস",
                        "hello", "hi", "hey"
                    )
                    val isGreeting = greetingKeywords.any {
                        userSpeech.lowercase().trim() == it ||
                        userSpeech.lowercase().trim().startsWith("$it ")
                    }
                    if (isGreeting && userSpeech.length < 30) {
                        val greetingResponse = "হ্যাঁ Boss, বলুন"
                        emitLog("JARVIS", greetingResponse)
                        _agentState.value = AgentState.SPEAKING
                        safeSpeak(greetingResponse)
                        continue
                    }

                    // INSTANT ACKNOWLEDGMENT — so user knows Jarvis heard them
                    // This speaks "hmm" while LLM processes in background
                    _agentState.value = AgentState.THINKING
                    updateNotification("Thinking...")

                    // Start LLM call in background immediately
                    val llmDeferred = ioScope.async {
                        try {
                            askLlm(userSpeech)
                        } catch (e: Exception) {
                            Log.e(TAG, "LLM call failed", e)
                            "Boss, problem hocchhe. Abar bolun."
                        }
                    }

                    // Speak quick acknowledgment WHILE LLM is processing
                    // Only for longer queries (not simple ones)
                    if (userSpeech.length > 15) {
                        val ack = listOf("hmm", "accha", "bujhchi", "thik ache").random()
                        // Don't wait for ack to finish — fire and forget
                        androidTts?.speak(ack, TextToSpeech.QUEUE_FLUSH, null, "ack_${System.currentTimeMillis()}")
                    }

                    // Wait for LLM response
                    val response = llmDeferred.await()
                    emitLog("JARVIS", response)

                    // Speak the full response
                    _agentState.value = AgentState.SPEAKING
                    updateNotification("Speaking...")
                    safeSpeak(response)

                } catch (e: CancellationException) {
                    throw e // Don't catch coroutine cancellation
                } catch (e: Exception) {
                    // CRITICAL: Catch everything and keep the loop alive
                    Log.e(TAG, "Loop iteration error — recovering", e)
                    emitLog("SYSTEM", "Error recovered — listening again...")
                    delay(1000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  GREETING                                                           //
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
            " Battery ${battery.percentage}% — charge dien."
        } else ""

        return "$timeGreeting Jarvis ready. Bolun ki korbo?$batteryWarning"
    }

    // ------------------------------------------------------------------ //
    //  NOTIFICATION ANNOUNCER                                             //
    // ------------------------------------------------------------------ //

    private suspend fun announceNewNotifications() {
        val recentNotifs = JarvisNotificationListener.getRecentNotifications(3)
        val newNotifs = recentNotifs.filter { it.timestamp > lastAnnouncedNotifTimestamp }

        if (newNotifs.isEmpty()) return

        for (notif in newNotifs) {
            val announcement = "Boss, ${notif.appName} e ${notif.sender} bolche: ${notif.text}"
            emitLog("JARVIS", announcement)
            safeSpeak(announcement)
            lastAnnouncedNotifTimestamp = notif.timestamp
        }
    }

    // ------------------------------------------------------------------ //
    //  LLM                                                                //
    // ------------------------------------------------------------------ //

    private suspend fun askLlm(userText: String): String {
        val client = llmClient ?: return "Boss, AI setup koreni. Settings e API key dien."

        conversationHistory.add(ChatMessage(role = "user", content = userText))
        if (conversationHistory.size > 20) conversationHistory.removeFirst()

        val messages = buildMessages(client)

        return withContext(Dispatchers.IO) {
            try {
                // Cap LLM wait to 15 seconds max
                val result = withTimeout(15_000L) { client.chat(messages) }
                result.fold(
                    onSuccess = { response ->
                        conversationHistory.add(ChatMessage(role = "assistant", content = response))

                        // Execute any action in the response (non-blocking)
                        val action = tryParseAction(response)
                        if (action != null) {
                            // Launch action on Main thread, but don't wait for it to block LLM return
                            withContext(Dispatchers.Main) {
                                try {
                                    withTimeout(ACTION_TIMEOUT_MS) {
                                        executeAction(action, response)
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    Log.w(TAG, "Action timed out: ${action.get("action")?.asString}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Action failed", e)
                                }
                            }
                        }

                        // Strip JSON action block for speaking
                        response.replace(Regex("""\{[^{}]*"action"[^{}]*\}"""), "").trim()
                            .ifBlank { "করে দিচ্ছি Boss!" }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "LLM error", error)
                        "Boss, ektu problem — ${error.message?.take(40)}"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM exception", e)
                "Boss, network e problem hocchhe."
            }
        }
    }

    private fun buildMessages(client: LlmClient): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // Use custom system prompt if set, otherwise default
        val systemPrompt = prefManager.customSystemPrompt.ifBlank { client.JARVIS_SYSTEM_PROMPT }
        messages.add(ChatMessage(role = "system", content = systemPrompt))

        // Screen context
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            try {
                val screenContext = buildString {
                    append("[CURRENT SCREEN]\n")
                    append("App: ${a11y.currentPackage}\n")
                    val chatName = a11y.getCurrentChatName()
                    if (chatName != null) append("Chat: $chatName\n")
                    val screenText = a11y.readScreenTextFlat()
                    if (screenText.isNotBlank()) append("Screen:\n${screenText.take(1500)}")
                }
                messages.add(ChatMessage(role = "system", content = screenContext))
            } catch (e: Exception) {
                Log.w(TAG, "Screen context error", e)
            }
        }

        // Web context
        try {
            val lastWebTitle = WebViewActivity.lastPageTitle
            val lastWebText = WebViewActivity.lastExtractedText
            if (lastWebTitle.isNotBlank() || lastWebText.isNotBlank()) {
                val webCtx = "[WEB PAGE]\nTitle: $lastWebTitle\nURL: ${WebViewActivity.lastPageUrl}\n${lastWebText.take(1000)}"
                messages.add(ChatMessage(role = "system", content = webCtx))
            }
        } catch (_: Exception) {}

        // Notifications
        try {
            val recentNotifs = JarvisNotificationListener.getRecentNotifications(5)
            if (recentNotifs.isNotEmpty()) {
                val ctx = recentNotifs.joinToString("\n") { it.toContextString() }
                messages.add(ChatMessage(role = "system", content = "[NOTIFICATIONS]\n$ctx"))
            }
        } catch (_: Exception) {}

        // Device info
        try {
            messages.add(ChatMessage(role = "system", content = "[DEVICE]\n${DeviceInfoProvider.getDeviceSummary(this)}"))
        } catch (_: Exception) {}

        messages.addAll(conversationHistory)
        return messages
    }

    // ------------------------------------------------------------------ //
    //  ACTION PARSING & EXECUTION                                         //
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

        try {
            when (type) {
                "read_screen" -> {
                    val a11y = JarvisAccessibilityService.instance
                    if (a11y != null) {
                        val txt = a11y.readScreenTextFlat()
                        emitLog("JARVIS", if (txt.isBlank()) "Screen empty." else "Screen:\n${txt.take(500)}")
                    } else {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "read_messages" -> {
                    val a11y = JarvisAccessibilityService.instance
                    val count = action.get("count")?.asInt ?: 5
                    if (a11y != null) {
                        val msgs = a11y.readLastMessages(count)
                        val formatted = msgs.joinToString("\n") { "${it.sender}: ${it.text}" }
                        emitLog("JARVIS", if (formatted.isBlank()) "No messages." else "Messages:\n$formatted")
                    } else {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "send_message" -> {
                    val a11y = JarvisAccessibilityService.instance
                    val text = action.get("text")?.asString ?: ""
                    if (a11y != null && text.isNotBlank()) {
                        // Run sendMessage on IO thread (it has Thread.sleep inside)
                        val success = withContext(Dispatchers.IO) {
                            a11y.sendMessage(text)
                        }
                        emitLog("JARVIS", if (success) "Message sent: $text" else "Send failed Boss.")
                    } else if (a11y == null) {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "click" -> {
                    val a11y = JarvisAccessibilityService.instance
                    val target = action.get("target")?.asString ?: ""
                    if (a11y != null && target.isNotBlank()) {
                        val success = a11y.clickNodeByText(target)
                        emitLog("JARVIS", if (success) "Clicked '$target'" else "'$target' not found")
                    } else if (a11y == null) {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "type" -> {
                    val a11y = JarvisAccessibilityService.instance
                    val text = action.get("text")?.asString ?: ""
                    if (a11y != null && text.isNotBlank()) {
                        a11y.typeText(text)
                        emitLog("JARVIS", "Typed: $text")
                    } else if (a11y == null) {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "scroll" -> {
                    val a11y = JarvisAccessibilityService.instance
                    val direction = action.get("direction")?.asString ?: "down"
                    if (a11y != null) {
                        val dir = if (direction == "up") JarvisAccessibilityService.ScrollDirection.UP
                        else JarvisAccessibilityService.ScrollDirection.DOWN
                        a11y.scroll(dir)
                        emitLog("JARVIS", "Scrolled $direction")
                    } else {
                        emitLog("SYSTEM", "Accessibility OFF.")
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
                        emitLog("JARVIS", "Navigated $target")
                    } else {
                        emitLog("SYSTEM", "Accessibility OFF.")
                    }
                }

                "web_search" -> {
                    val query = action.get("query")?.asString ?: ""
                    if (query.isNotBlank()) {
                        WebViewActivity.launchSearch(this@LiveVoiceAgent, query)
                        emitLog("JARVIS", "Searching: $query")
                    }
                }

                "open_url" -> {
                    val url = action.get("url")?.asString ?: ""
                    if (url.isNotBlank()) {
                        WebViewActivity.launchUrl(this@LiveVoiceAgent, url)
                        emitLog("JARVIS", "Opening: $url")
                    }
                }

                "device_info" -> {
                    val infoType = action.get("type")?.asString ?: "all"
                    val info = when (infoType) {
                        "battery" -> {
                            val b = DeviceInfoProvider.getBatteryInfo(this@LiveVoiceAgent)
                            "Battery ${b.percentage}%. ${if (b.isCharging) "Charging." else "Not charging."}"
                        }
                        "network" -> {
                            val n = DeviceInfoProvider.getNetworkInfo(this@LiveVoiceAgent)
                            "${n.type}. Down: ${n.downstreamMbps} Mbps, Up: ${n.upstreamMbps} Mbps."
                        }
                        else -> DeviceInfoProvider.getDeviceSummary(this@LiveVoiceAgent)
                    }
                    emitLog("JARVIS", info)
                }

                "speak" -> { /* Main loop will speak the response text */ }

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
                                "camera" -> pm.getLaunchIntentForPackage("com.android.camera")
                                    ?: pm.getLaunchIntentForPackage("com.sec.android.app.camera")
                                "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                                "gallery", "photos" -> pm.getLaunchIntentForPackage("com.google.android.apps.photos")
                                    ?: pm.getLaunchIntentForPackage("com.sec.android.gallery3d")
                                "maps" -> pm.getLaunchIntentForPackage("com.google.android.apps.maps")
                                "music", "spotify" -> pm.getLaunchIntentForPackage("com.spotify.music")
                                "phone", "dialer" -> pm.getLaunchIntentForPackage("com.android.dialer")
                                    ?: pm.getLaunchIntentForPackage("com.samsung.android.dialer")
                                "contacts" -> pm.getLaunchIntentForPackage("com.android.contacts")
                                    ?: pm.getLaunchIntentForPackage("com.samsung.android.contacts")
                                "clock", "alarm" -> pm.getLaunchIntentForPackage("com.android.deskclock")
                                    ?: pm.getLaunchIntentForPackage("com.sec.android.app.clockpackage")
                                "calculator" -> pm.getLaunchIntentForPackage("com.android.calculator2")
                                    ?: pm.getLaunchIntentForPackage("com.sec.android.app.popupcalculator")
                                "files", "file manager" -> pm.getLaunchIntentForPackage("com.android.documentsui")
                                    ?: pm.getLaunchIntentForPackage("com.sec.android.app.myfiles")
                                "play store" -> pm.getLaunchIntentForPackage("com.android.vending")
                                "twitter", "x" -> pm.getLaunchIntentForPackage("com.twitter.android")
                                "tiktok" -> pm.getLaunchIntentForPackage("com.zhiliaoapp.musically")
                                "snapchat" -> pm.getLaunchIntentForPackage("com.snapchat.android")
                                else -> pm.getLaunchIntentForPackage(appName)
                            }
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                                emitLog("JARVIS", "$appName opened.")
                            } else {
                                emitLog("JARVIS", "$appName not found.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Open app error: $appName", e)
                            emitLog("JARVIS", "$appName open failed.")
                        }
                    }
                }

                "take_photo" -> {
                    // Open camera app for photo
                    try {
                        val pm = packageManager
                        val cameraIntent = pm.getLaunchIntentForPackage("com.android.camera")
                            ?: pm.getLaunchIntentForPackage("com.sec.android.app.camera")
                            ?: pm.getLaunchIntentForPackage("com.google.android.GoogleCamera")
                            ?: Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(cameraIntent)
                        emitLog("JARVIS", "Camera open korechi Boss. Photo tulen!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera open failed", e)
                        emitLog("JARVIS", "Camera open korte parlam na.")
                    }
                }

                "set_clipboard" -> {
                    val text = action.get("text")?.asString ?: ""
                    if (text.isNotBlank()) {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Jarvis", text)
                            clipboard.setPrimaryClip(clip)
                            emitLog("JARVIS", "Clipboard e copy korechi: ${text.take(50)}")
                        } catch (e: Exception) {
                            emitLog("JARVIS", "Clipboard e copy korte parlam na.")
                        }
                    }
                }

                "send_sms" -> {
                    val phone = action.get("phone")?.asString ?: ""
                    val text = action.get("text")?.asString ?: ""
                    if (phone.isNotBlank() && text.isNotBlank()) {
                        try {
                            val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                                getSystemService(android.telephony.SmsManager::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                android.telephony.SmsManager.getDefault()
                            }
                            val parts = smsManager.divideMessage(text)
                            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                            emitLog("JARVIS", "SMS sent to $phone: $text")
                        } catch (e: Exception) {
                            Log.e(TAG, "SMS send failed", e)
                            emitLog("JARVIS", "SMS send korte parlam na: ${e.message?.take(30)}")
                        }
                    }
                }

                "read_sms" -> {
                    val count = action.get("count")?.asInt ?: 5
                    try {
                        val cursor = contentResolver.query(
                            android.provider.Telephony.Sms.CONTENT_URI,
                            arrayOf("address", "body", "date", "type"),
                            null, null, "date DESC"
                        )
                        val messages = mutableListOf<String>()
                        cursor?.use {
                            var i = 0
                            while (it.moveToNext() && i < count) {
                                val addr = it.getString(0) ?: "Unknown"
                                val body = it.getString(1) ?: ""
                                val type = it.getInt(3)
                                val dir = if (type == 1) "Received" else "Sent"
                                messages.add("[$dir] $addr: $body")
                                i++
                            }
                        }
                        emitLog("JARVIS", if (messages.isEmpty()) "No SMS found." else "SMS:\n${messages.joinToString("\n")}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Read SMS failed", e)
                        emitLog("JARVIS", "SMS read korte parlam na. Permission den Settings e.")
                    }
                }

                "read_contacts" -> {
                    val query = action.get("query")?.asString ?: ""
                    try {
                        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                        val selection = if (query.isNotBlank()) {
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                        } else null
                        val selArgs = if (query.isNotBlank()) arrayOf("%$query%") else null
                        val cursor = contentResolver.query(
                            uri,
                            arrayOf(
                                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                            ),
                            selection, selArgs,
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                        )
                        val contacts = mutableListOf<String>()
                        cursor?.use {
                            var i = 0
                            while (it.moveToNext() && i < 10) {
                                val name = it.getString(0) ?: ""
                                val number = it.getString(1) ?: ""
                                contacts.add("$name: $number")
                                i++
                            }
                        }
                        emitLog("JARVIS", if (contacts.isEmpty()) "No contacts found." else "Contacts:\n${contacts.joinToString("\n")}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Read contacts failed", e)
                        emitLog("JARVIS", "Contacts read korte parlam na.")
                    }
                }

                "make_call" -> {
                    val phone = action.get("phone")?.asString ?: ""
                    if (phone.isNotBlank()) {
                        try {
                            val callIntent = Intent(Intent.ACTION_CALL).apply {
                                data = android.net.Uri.parse("tel:$phone")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(callIntent)
                            emitLog("JARVIS", "Calling $phone...")
                        } catch (e: Exception) {
                            Log.e(TAG, "Call failed", e)
                            emitLog("JARVIS", "Call korte parlam na. Permission den.")
                        }
                    }
                }

                "create_image" -> {
                    val prompt = action.get("prompt")?.asString ?: ""
                    emitLog("JARVIS", "Image concept: $prompt\nBoss, ekhon image generation feature ashche. Apni eita describe korechi.")
                }

                else -> Log.d(TAG, "Unknown action: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Action execution error: $type", e)
            emitLog("SYSTEM", "Action '$type' failed.")
        }
    }

    // ------------------------------------------------------------------ //
    //  STT — CONTINUOUS LISTEN (FIXED: no cooldown, instant restart)       //
    // ------------------------------------------------------------------ //

    /** Track consecutive errors to avoid tight spin loops */
    private var consecutiveSttErrors = 0

    /**
     * MAIN FIX for "voice ekbar nile ar ney na":
     *
     * Problem: Android SpeechRecognizer has an internal cooldown after
     * ERROR_NO_MATCH(7) and ERROR_SPEECH_TIMEOUT(6). After one use it
     * waits 30s-2min before accepting startListening() again.
     *
     * Solution:
     * 1. Create FRESH SpeechRecognizer EVERY time (destroy old one first)
     * 2. Request AUDIO FOCUS before starting (ensures mic is free)
     * 3. Add small delay (700ms) between destroy and create to let Android
     *    release internal resources
     * 4. Use language from settings (not hardcoded)
     * 5. Configure silence timeouts based on voice sensitivity setting
     */
    private suspend fun safeListenForSpeech(): String {
        return try {
            withTimeout(STT_TIMEOUT_MS) {
                // Small delay between listen cycles to let Android release mic
                // This is the KEY fix — without it, SpeechRecognizer refuses to start
                val delayMs = when {
                    consecutiveSttErrors > 3 -> 1000L   // Many errors: longer cooldown
                    consecutiveSttErrors > 0 -> 500L   // Some errors: medium cooldown
                    else -> 200L                         // No errors: quick restart
                }
                delay(delayMs)

                val result = listenForSpeechOnce()
                if (result.isNotBlank()) {
                    consecutiveSttErrors = 0  // Reset on success
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "STT timeout — no speech")
            consecutiveSttErrors++
            ""
        } catch (e: Exception) {
            Log.e(TAG, "STT error — recovering", e)
            consecutiveSttErrors++
            delay(1000)
            ""
        }
    }

    /**
     * One-shot speech recognition with a FRESH SpeechRecognizer.
     * Requests audio focus, uses language from settings, proper sensitivity.
     */
    private suspend fun listenForSpeechOnce(): String = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(this@LiveVoiceAgent)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return@withContext ""
        }

        // Request audio focus to ensure mic is available
        requestAudioFocus()

        suspendCancellableCoroutine { cont ->
            var recognizer: SpeechRecognizer? = null
            var hasResumed = false  // Prevent double resume

            fun safeResume(text: String) {
                if (!hasResumed && cont.isActive) {
                    hasResumed = true
                    try { recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                    abandonAudioFocus()
                    cont.resume(text, null)
                }
            }

            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this@LiveVoiceAgent)

                // Get language from settings
                val sttLang = prefManager.sttLanguage

                // Get silence timeouts from voice sensitivity
                val (possibleSilence, completeSilence) = when (prefManager.voiceSensitivity) {
                    0 -> 6000L to 8000L    // Low: long silence allowed
                    1 -> 4000L to 6000L    // Normal
                    2 -> 3000L to 4000L    // High: shorter silence = faster response
                    3 -> 2000L to 3000L    // Max Focus: very quick
                    else -> 3000L to 5000L
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sttLang)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, sttLang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // Get partial results for faster feedback
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleSilence)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilence)
                    // Prefer offline recognition if available (faster)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }

                recognizer!!.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        Log.d(TAG, "STT result: '$text'")
                        safeResume(text)
                    }

                    override fun onError(error: Int) {
                        val errorName = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
                            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                            SpeechRecognizer.ERROR_SERVER -> "SERVER"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                            else -> "UNKNOWN($error)"
                        }
                        Log.d(TAG, "STT error: $errorName")
                        consecutiveSttErrors++
                        safeResume("")
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "STT ready — mic active, listening...")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "STT: speech started")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "STT: speech ended, processing...")
                    }
                    override fun onPartialResults(partial: Bundle?) {
                        // Log partial results for debugging
                        val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            Log.d(TAG, "STT partial: '$text'")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer!!.startListening(intent)
                Log.d(TAG, "STT startListening called (lang=$sttLang)")

            } catch (e: Exception) {
                Log.e(TAG, "STT create/start failed", e)
                safeResume("")
            }

            cont.invokeOnCancellation {
                try {
                    recognizer?.stopListening()
                    recognizer?.destroy()
                } catch (_: Exception) {}
                abandonAudioFocus()
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Audio Focus — ensures mic is available for STT                      //
    // ------------------------------------------------------------------ //

    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener {}
                    .build()
                am.requestAudioFocus(req)
                audioFocusRequest = req
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus({}, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus {}
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ //
    //  TTS — SAFE SPEAK (with timeout, never hangs)                       //
    // ------------------------------------------------------------------ //

    /**
     * Speaks text with a hard timeout. If TTS hangs or fails,
     * the loop continues after timeout. NEVER blocks forever.
     */
    private suspend fun safeSpeak(text: String) {
        if (text.isBlank()) return
        _agentState.value = AgentState.SPEAKING

        try {
            withTimeout(TTS_TIMEOUT_MS) {
                speakAndWait(text)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "TTS timeout after ${TTS_TIMEOUT_MS}ms — moving on")
            // Stop any stuck audio
            cartesiaWsManager?.cancelCurrentGeneration()
            cartesiaClient?.stop()
            androidTts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "TTS error — recovering", e)
        }
    }

    private suspend fun speakAndWait(text: String) {
        val ttsProvider = prefManager.selectedTtsProvider

        when (ttsProvider) {
            TtsProvider.CARTESIA -> speakWithCartesia(text)
            TtsProvider.SPEECHIFY -> speakWithAndroidTtsFallback(text)
            TtsProvider.ANDROID_TTS -> speakWithAndroidTtsFallback(text)
        }
    }

    private suspend fun speakWithCartesia(text: String) {
        // Try WebSocket
        val wsManager = cartesiaWsManager
        if (wsManager != null && prefManager.useCartesiaWebSocket) {
            try {
                return suspendCancellableCoroutine { cont ->
                    ioScope.launch {
                        try {
                            wsManager.speak(text) {
                                if (cont.isActive) cont.resume(Unit, null)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Cartesia WS speak error", e)
                            if (cont.isActive) cont.resume(Unit, null)
                        }
                    }
                    cont.invokeOnCancellation {
                        wsManager.cancelCurrentGeneration()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cartesia WS failed", e)
            }
        }

        // Try HTTP
        val httpClient = cartesiaClient
        if (httpClient != null) {
            try {
                val result = withContext(Dispatchers.IO) { httpClient.speak(text) }
                if (result.isSuccess) return
                Log.w(TAG, "Cartesia HTTP failed")
            } catch (e: Exception) {
                Log.w(TAG, "Cartesia HTTP error", e)
            }
        }

        // Android TTS fallback
        speakWithAndroidTtsFallback(text)
    }

    private suspend fun speakWithAndroidTtsFallback(text: String) {
        if (!androidTtsReady) {
            Log.e(TAG, "Android TTS not ready")
            return
        }

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "j_${System.currentTimeMillis()}"

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

            val chunks = text.chunked(3900)
            chunks.forEachIndexed { index, chunk ->
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (index == chunks.lastIndex) utteranceId else "${utteranceId}_$index"
                androidTts?.speak(chunk, mode, null, id)
            }

            cont.invokeOnCancellation { androidTts?.stop() }
        }
    }

    // ------------------------------------------------------------------ //
    //  TTS Language Setup                                                 //
    // ------------------------------------------------------------------ //

    private fun setTtsLanguage() {
        val langCode = prefManager.ttsLanguage
        val parts = langCode.split("-")
        val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(langCode)
        val result = androidTts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
            Log.w(TAG, "TTS language $langCode not supported, using default")
            androidTts?.language = Locale.getDefault()
        } else {
            Log.i(TAG, "TTS language set to $langCode")
        }
    }

    // ------------------------------------------------------------------ //
    //  HELPERS                                                            //
    // ------------------------------------------------------------------ //

    private suspend fun emitLog(sender: String, text: String) {
        try {
            val time = timeFormat.format(Date())
            conversationLog.emit(ConversationEntry(sender, text, time))
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(4 * 60 * 60 * 1000L)
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

    data class ConversationEntry(
        val sender: String,
        val text: String,
        val time: String
    )
}
