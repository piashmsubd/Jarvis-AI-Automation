package com.jarvis.ai.network.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CartesiaWebSocketManager — Ultra-low-latency TTS via Cartesia's WebSocket API.
 *
 * Instead of the HTTP /tts/bytes endpoint (which waits for the full audio to generate),
 * this class opens a persistent WebSocket connection to:
 *
 *   wss://api.cartesia.ai/tts/websocket?api_key={KEY}&cartesia_version=2024-06-10
 *
 * Protocol:
 *   1. Client sends a JSON generation request with a unique context_id
 *   2. Server streams back JSON messages with base64-encoded PCM audio chunks
 *   3. Each chunk has { "type": "chunk", "data": "<base64 pcm>", "done": false }
 *   4. Final chunk has "done": true
 *
 * Audio chunks are decoded and fed to an AudioTrack in MODE_STREAM for real-time
 * playback — audio starts playing as the first chunk arrives (~40-90ms TTFA).
 *
 * The WebSocket connection is kept alive and reused across multiple TTS requests
 * to eliminate connection overhead. Idle timeout is handled via ping/pong.
 */
class CartesiaWebSocketManager(
    private val apiKey: String,
    private val voiceId: String = CartesiaTtsClient.DEFAULT_VOICE_ID,
    private val modelId: String = CartesiaTtsClient.DEFAULT_MODEL_ID,
    private val sampleRate: Int = 24000,
    private val defaultLanguage: String = CartesiaTtsClient.DEFAULT_LANGUAGE
) {
    companion object {
        private const val TAG = "CartesiaWS"
        private const val CARTESIA_VERSION = "2024-06-10"
        private const val WS_BASE_URL = "wss://api.cartesia.ai/tts/websocket"

        // Connection states
        enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Connection
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private val audioBufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    // Current generation tracking
    private var currentContextId: String? = null
    private var onSpeakComplete: (() -> Unit)? = null

    // OkHttp client with WebSocket-appropriate timeouts
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)      // No read timeout for WebSocket
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)          // Keep-alive pings every 30s
        .build()

    // ------------------------------------------------------------------ //
    //  Connection Management                                              //
    // ------------------------------------------------------------------ //

    /**
     * Opens the WebSocket connection. Safe to call multiple times —
     * will no-op if already connected or connecting.
     */
    fun connect() {
        if (isConnected.get() || _connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        val url = "$WS_BASE_URL?api_key=$apiKey&cartesia_version=$CARTESIA_VERSION"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
        Log.i(TAG, "WebSocket connection initiated")
    }

    /**
     * Closes the WebSocket connection gracefully.
     */
    fun disconnect() {
        stopAudio()
        webSocket?.close(1000, "Client closing")
        webSocket = null
        isConnected.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "WebSocket disconnected")
    }

    /**
     * Ensures the WebSocket is connected, reconnecting if necessary.
     */
    private suspend fun ensureConnected() {
        if (isConnected.get()) return

        connect()

        // Wait for connection with timeout
        var waited = 0
        while (!isConnected.get() && waited < 5000) {
            delay(50)
            waited += 50
        }

        if (!isConnected.get()) {
            throw RuntimeException("WebSocket connection timeout after 5s")
        }
    }

    // ------------------------------------------------------------------ //
    //  TTS: Speak with Streaming Audio                                    //
    // ------------------------------------------------------------------ //

    /**
     * Sends text to Cartesia and plays audio as chunks arrive.
     * This is the primary method — replaces CartesiaTtsClient.speak() for real-time use.
     *
     * Returns immediately; audio plays asynchronously. Use [onComplete] callback
     * or check [isSpeaking] to know when done.
     */
    suspend fun speak(
        text: String,
        language: String = defaultLanguage,
        onComplete: (() -> Unit)? = null
    ) {
        if (text.isBlank()) return

        ensureConnected()

        // Cancel any in-progress generation
        cancelCurrentGeneration()

        // Set up audio playback
        initAudioTrack()

        val contextId = UUID.randomUUID().toString().take(16)
        currentContextId = contextId
        onSpeakComplete = onComplete
        isSpeaking.set(true)

        // Build the generation request per Cartesia WebSocket protocol
        val request = JsonObject().apply {
            addProperty("model_id", modelId)
            addProperty("transcript", text)
            addProperty("language", language)
            addProperty("context_id", contextId)
            addProperty("continue", false)

            // Voice
            add("voice", JsonObject().apply {
                addProperty("mode", "id")
                addProperty("id", voiceId)
            })

            // Output format: raw PCM for lowest latency (no container overhead)
            add("output_format", JsonObject().apply {
                addProperty("container", "raw")
                addProperty("encoding", "pcm_s16le")
                addProperty("sample_rate", sampleRate)
            })

            addProperty("add_timestamps", false)
        }

        val json = gson.toJson(request)
        val sent = webSocket?.send(json) ?: false

        if (sent) {
            Log.d(TAG, "Generation request sent: context=$contextId, text='${text.take(50)}...'")
        } else {
            Log.e(TAG, "Failed to send generation request — WebSocket not ready")
            isSpeaking.set(false)
            stopAudio()
        }
    }

    /**
     * Stream text input for incremental TTS (e.g., from LLM token stream).
     * Uses Cartesia's context continuation feature for seamless prosody.
     *
     * Call with isFinal=false for intermediate chunks, isFinal=true for the last one.
     */
    suspend fun streamText(
        text: String,
        contextId: String,
        isFinal: Boolean = false,
        language: String = defaultLanguage
    ) {
        ensureConnected()

        if (!isSpeaking.get()) {
            initAudioTrack()
            isSpeaking.set(true)
            currentContextId = contextId
        }

        val request = JsonObject().apply {
            addProperty("model_id", modelId)
            addProperty("transcript", text)
            addProperty("language", language)
            addProperty("context_id", contextId)
            addProperty("continue", !isFinal)

            add("voice", JsonObject().apply {
                addProperty("mode", "id")
                addProperty("id", voiceId)
            })

            add("output_format", JsonObject().apply {
                addProperty("container", "raw")
                addProperty("encoding", "pcm_s16le")
                addProperty("sample_rate", sampleRate)
            })
        }

        webSocket?.send(gson.toJson(request))
        Log.d(TAG, "Streamed text chunk: '${text.take(30)}...' final=$isFinal")
    }

    /**
     * Cancels the current TTS generation.
     */
    fun cancelCurrentGeneration() {
        val ctxId = currentContextId ?: return

        val cancelMsg = JsonObject().apply {
            addProperty("context_id", ctxId)
            addProperty("cancel", true)
        }
        webSocket?.send(gson.toJson(cancelMsg))

        stopAudio()
        isSpeaking.set(false)
        currentContextId = null
        Log.d(TAG, "Cancelled generation: context=$ctxId")
    }

    /**
     * Stops any currently playing audio immediately.
     */
    fun stopAudio() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                }
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null
    }

    val isSpeakingNow: Boolean get() = isSpeaking.get()

    // ------------------------------------------------------------------ //
    //  WebSocket Listener — Processes incoming audio chunks               //
    // ------------------------------------------------------------------ //

    private fun createWebSocketListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected.set(true)
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "WebSocket CONNECTED")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val type = json.get("type")?.asString

                when (type) {
                    // Audio data chunk
                    "chunk" -> {
                        val base64Data = json.get("data")?.asString ?: return
                        val isDone = json.get("done")?.asBoolean ?: false
                        val contextId = json.get("context_id")?.asString

                        // Decode base64 to raw PCM bytes
                        val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)

                        // Write directly to AudioTrack for immediate playback
                        audioTrack?.let { track ->
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                track.play()
                            }
                            track.write(pcmBytes, 0, pcmBytes.size)
                        }

                        if (isDone) {
                            Log.d(TAG, "Generation complete: context=$contextId")
                            // Don't stop AudioTrack immediately — let buffered audio finish
                            scope.launch {
                                // Wait for remaining audio to play out
                                delay(500)
                                isSpeaking.set(false)
                                onSpeakComplete?.invoke()
                                onSpeakComplete = null
                            }
                        }
                    }

                    // Flush done (all chunks for context have been sent)
                    "done" -> {
                        val contextId = json.get("context_id")?.asString
                        Log.d(TAG, "Flush done: context=$contextId")
                    }

                    // Error from Cartesia
                    "error" -> {
                        val error = json.get("error")?.asString ?: "Unknown error"
                        val statusCode = json.get("status_code")?.asInt ?: 0
                        Log.e(TAG, "Cartesia error ($statusCode): $error")
                        isSpeaking.set(false)
                        stopAudio()
                    }

                    // Timestamp data (if add_timestamps was true)
                    "timestamps" -> {
                        // Could be used for subtitle sync
                    }

                    else -> {
                        Log.d(TAG, "Unknown message type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing WebSocket message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code / $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected.set(false)
            _connectionState.value = ConnectionState.DISCONNECTED
            isSpeaking.set(false)
            Log.i(TAG, "WebSocket closed: $code / $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected.set(false)
            _connectionState.value = ConnectionState.ERROR
            isSpeaking.set(false)
            Log.e(TAG, "WebSocket failure: ${t.message}", t)

            // Auto-reconnect after a delay
            scope.launch {
                delay(3000)
                Log.i(TAG, "Attempting WebSocket reconnection...")
                connect()
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  AudioTrack Setup                                                   //
    // ------------------------------------------------------------------ //

    private fun initAudioTrack() {
        stopAudio()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(audioBufferSize * 4) // Larger buffer for streaming
            .setTransferMode(AudioTrack.MODE_STREAM)   // MODE_STREAM for real-time chunks
            .build()

        Log.d(TAG, "AudioTrack initialized: rate=$sampleRate, buffer=${audioBufferSize * 4}")
    }

    // ------------------------------------------------------------------ //
    //  Cleanup                                                            //
    // ------------------------------------------------------------------ //

    fun destroy() {
        disconnect()
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        Log.i(TAG, "CartesiaWebSocketManager destroyed")
    }
}
