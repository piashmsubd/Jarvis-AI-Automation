package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.jarvis.ai.network.client.CartesiaTtsClient
import com.jarvis.ai.network.client.CartesiaWebSocketManager
import com.jarvis.ai.network.model.TtsProvider
import com.jarvis.ai.util.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * VoiceEngine — Orchestrates STT (Speech-to-Text) and TTS (Text-to-Speech).
 *
 * STT: Uses Android's built-in SpeechRecognizer (or could be swapped for Whisper API).
 * TTS: Routes to one of three backends based on settings:
 *   1. Cartesia WebSocket (ultra-low latency, streaming audio) — DEFAULT
 *   2. Cartesia HTTP /tts/bytes (higher latency, simpler)
 *   3. Android built-in TTS (offline fallback)
 *
 * Exposes state as Flows so the UI can observe listening/speaking states.
 */
class VoiceEngine(
    private val context: Context,
    private val prefManager: PreferenceManager
) {
    companion object {
        private const val TAG = "VoiceEngine"
    }

    // ------------------------------------------------------------------ //
    //  State                                                              //
    // ------------------------------------------------------------------ //

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // STT
    private var speechRecognizer: SpeechRecognizer? = null
    private var onResultCallback: ((String) -> Unit)? = null

    // TTS — Android built-in (fallback)
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    // TTS — Cartesia HTTP (legacy)
    private var cartesiaClient: CartesiaTtsClient? = null

    // TTS — Cartesia WebSocket (ultra-low latency, preferred)
    private var cartesiaWsManager: CartesiaWebSocketManager? = null

    // ------------------------------------------------------------------ //
    //  Initialization                                                     //
    // ------------------------------------------------------------------ //

    fun initialize() {
        // Initialize Android SpeechRecognizer
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized")
        } else {
            Log.e(TAG, "Speech recognition not available on this device")
        }

        // Initialize Android TTS as fallback
        androidTts = TextToSpeech(context) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                androidTts?.language = Locale.US
                Log.i(TAG, "Android TTS initialized")
            }
        }

        // Initialize Cartesia clients if API key is configured
        refreshCartesiaClient()
    }

    fun refreshCartesiaClient() {
        val apiKey = prefManager.cartesiaApiKey
        val voiceId = prefManager.cartesiaVoiceId.ifBlank { CartesiaTtsClient.DEFAULT_VOICE_ID }

        if (apiKey.isNotBlank()) {
            // HTTP client (fallback)
            cartesiaClient = CartesiaTtsClient(apiKey = apiKey, voiceId = voiceId)

            // WebSocket client (preferred for real-time)
            if (prefManager.useCartesiaWebSocket) {
                // Disconnect old one if voice/key changed
                cartesiaWsManager?.disconnect()
                cartesiaWsManager = CartesiaWebSocketManager(
                    apiKey = apiKey,
                    voiceId = voiceId
                ).also {
                    // Pre-connect for zero-latency first speak
                    it.connect()
                }
                Log.i(TAG, "Cartesia WebSocket manager initialized (preconnecting)")
            }
        } else {
            cartesiaClient = null
            cartesiaWsManager?.disconnect()
            cartesiaWsManager = null
        }
    }

    // ------------------------------------------------------------------ //
    //  STT: Start/Stop Listening                                          //
    // ------------------------------------------------------------------ //

    /**
     * Starts listening for voice input.
     * [onResult] is called with the transcribed text when recognition completes.
     */
    fun startListening(onResult: (String) -> Unit) {
        if (_state.value == State.LISTENING) {
            Log.w(TAG, "Already listening")
            return
        }

        onResultCallback = onResult
        _state.value = State.LISTENING
        _errorMessage.value = null

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep listening until user stops (longer timeout)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _state.value = State.ERROR
            _errorMessage.value = "Failed to start voice recognition: ${e.message}"
        }
    }

    /**
     * Stops the current listening session.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        _state.value = State.IDLE
        Log.d(TAG, "Stopped listening")
    }

    // ------------------------------------------------------------------ //
    //  TTS: Speak Text                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Speaks the given text using the configured TTS provider.
     *
     * Priority for Cartesia:
     *   1. WebSocket manager (if enabled and connected) — ultra-low latency
     *   2. HTTP client — reliable fallback
     *   3. Android TTS — offline last resort
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) return

        _state.value = State.SPEAKING

        scope.launch {
            try {
                when (prefManager.selectedTtsProvider) {
                    TtsProvider.CARTESIA -> speakWithCartesia(text, onComplete)
                    TtsProvider.SPEECHIFY -> speakWithSpeechify(text)
                    TtsProvider.ANDROID_TTS -> speakWithAndroidTts(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS failed, falling back to Android TTS", e)
                speakWithAndroidTts(text)
            } finally {
                if (prefManager.selectedTtsProvider != TtsProvider.CARTESIA || cartesiaWsManager == null) {
                    _state.value = State.IDLE
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * Stops any ongoing speech output.
     */
    fun stopSpeaking() {
        cartesiaWsManager?.cancelCurrentGeneration()
        cartesiaClient?.stop()
        androidTts?.stop()
        _state.value = State.IDLE
    }

    private suspend fun speakWithCartesia(text: String, onComplete: (() -> Unit)?) {
        // Try WebSocket first (ultra-low latency)
        val wsManager = cartesiaWsManager
        if (wsManager != null && prefManager.useCartesiaWebSocket) {
            try {
                wsManager.speak(text) {
                    _state.value = State.IDLE
                    onComplete?.invoke()
                }
                Log.d(TAG, "Speaking via Cartesia WebSocket")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cartesia WebSocket failed, falling back to HTTP", e)
            }
        }

        // Fall back to HTTP /tts/bytes
        val client = cartesiaClient
        if (client != null) {
            val result = client.speak(text)
            result.onFailure { e ->
                Log.e(TAG, "Cartesia HTTP TTS also failed, using Android TTS", e)
                speakWithAndroidTts(text)
            }
            return
        }

        Log.w(TAG, "No Cartesia client configured, falling back to Android TTS")
        speakWithAndroidTts(text)
    }

    private suspend fun speakWithSpeechify(text: String) {
        // Speechify integration placeholder — similar to Cartesia
        Log.w(TAG, "Speechify not yet fully implemented, using Android TTS")
        speakWithAndroidTts(text)
    }

    private fun speakWithAndroidTts(text: String) {
        if (!androidTtsReady) {
            Log.e(TAG, "Android TTS not ready")
            return
        }

        // Split long text into chunks (Android TTS has a ~4000 char limit)
        val chunks = text.chunked(3900)
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            androidTts?.speak(chunk, queueMode, null, "jarvis_tts_$index")
        }
    }

    // ------------------------------------------------------------------ //
    //  Recognition Listener                                               //
    // ------------------------------------------------------------------ //

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could be used to show audio level visualization
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            _state.value = State.PROCESSING
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing audio permission"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Unknown error: $error"
            }
            Log.w(TAG, "Recognition error: $message")
            _errorMessage.value = message

            // For timeout/no-match, this is normal — not a real error
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                _state.value = State.IDLE
            } else {
                _state.value = State.ERROR
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull() ?: ""

            Log.d(TAG, "Final transcript: $transcript")
            _lastTranscript.value = transcript
            _state.value = State.IDLE

            if (transcript.isNotBlank()) {
                onResultCallback?.invoke(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                _lastTranscript.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------ //
    //  Cleanup                                                            //
    // ------------------------------------------------------------------ //

    fun destroy() {
        speechRecognizer?.destroy()
        androidTts?.shutdown()
        cartesiaClient?.stop()
        cartesiaWsManager?.destroy()
        scope.cancel()
        Log.i(TAG, "VoiceEngine destroyed")
    }
}
