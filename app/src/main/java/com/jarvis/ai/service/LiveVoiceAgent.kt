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
import com.jarvis.ai.network.client.LlmClient
import com.jarvis.ai.network.model.ChatMessage
import com.jarvis.ai.network.model.LlmProvider
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
 *   ACTIVATE → GREET → LISTEN → THINK → SPEAK → LISTEN → ...
 *                                                    ↑_____|
 *
 * The loop runs forever until the user says "Jarvis bondho hoye jao" or
 * taps the DEACTIVATE button. No button presses needed between turns.
 *
 * Proactive behaviors:
 *   - Greets the user when activated ("Boss, kemon achhen?")
 *   - Reads new notifications aloud ("Boss, WhatsApp e message eshechhe...")
 *   - Reports device issues ("Boss, battery 15% — charge dien")
 *
 * Works WITHOUT Accessibility Service — uses NotificationListener for
 * message detection and Android TTS as voice fallback.
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
        Log.i(TAG, "LiveVoiceAgent created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Jarvis active — listening..."))
        acquireWakeLock()
        initializeComponents()
        startConversationLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        keepListening = false
        _agentState.value = AgentState.INACTIVE

        speechRecognizer?.destroy()
        tts?.shutdown()
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

        // Android TTS (works offline, reliable for Bangla)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                // Try to set Bengali, fall back to default
                val bengali = Locale("bn", "BD")
                val result = tts?.setLanguage(bengali)
                if (result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                Log.i(TAG, "TTS ready, language: ${tts?.voice?.locale}")
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

            // Step 3: Enter the continuous listen → think → speak loop
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

        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(role = "system", content = client.JARVIS_SYSTEM_PROMPT))

        // Inject notification context
        val recentNotifs = JarvisNotificationListener.getRecentNotifications(5)
        if (recentNotifs.isNotEmpty()) {
            val ctx = recentNotifs.joinToString("\n") { it.toContextString() }
            messages.add(ChatMessage(role = "system", content = "[RECENT NOTIFICATIONS]\n$ctx"))
        }

        // Inject device info
        val deviceInfo = DeviceInfoProvider.getDeviceSummary(this)
        messages.add(ChatMessage(role = "system", content = "[DEVICE INFO]\n$deviceInfo"))

        messages.addAll(conversationHistory)

        return withContext(Dispatchers.IO) {
            try {
                val result = client.chat(messages)
                result.fold(
                    onSuccess = { response ->
                        conversationHistory.add(ChatMessage(role = "assistant", content = response))

                        // Check for action blocks and execute them
                        val action = tryParseAction(response)
                        if (action != null) {
                            executeAction(action)
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
    //  ACTION EXECUTION (no accessibility needed)                         //
    // ------------------------------------------------------------------ //

    private fun tryParseAction(response: String): JsonObject? {
        return try {
            val pattern = """\{[^{}]*"action"\s*:\s*"[^"]+(?:"[^{}]*)*\}""".toRegex()
            val match = pattern.find(response) ?: return null
            gson.fromJson(match.value, JsonObject::class.java)
        } catch (_: Exception) { null }
    }

    private fun executeAction(action: JsonObject) {
        val type = action.get("action")?.asString ?: return
        when (type) {
            "web_search" -> {
                val query = action.get("query")?.asString ?: return
                WebViewActivity.launchSearch(this, query)
            }
            "open_url" -> {
                val url = action.get("url")?.asString ?: return
                WebViewActivity.launchUrl(this, url)
            }
            "device_info" -> { /* Already injected in context */ }
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
    //  TEXT-TO-SPEECH — Speak and wait until done (suspending)             //
    // ------------------------------------------------------------------ //

    private suspend fun speakAndWait(text: String) {
        if (text.isBlank() || !ttsReady) return

        _agentState.value = AgentState.SPEAKING

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "jarvis_${System.currentTimeMillis()}"

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                tts?.speak(chunk, mode, null, id)
            }

            cont.invokeOnCancellation {
                tts?.stop()
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
