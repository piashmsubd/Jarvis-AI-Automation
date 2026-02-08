package com.jarvis.ai.network.api

import com.jarvis.ai.network.model.CartesiaTtsRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for Cartesia TTS API.
 *
 * Cartesia provides two main TTS endpoints:
 * 1. /tts/bytes  — Returns raw audio bytes (WAV/MP3/PCM). Best for batch/offline.
 * 2. /tts/sse    — Server-Sent Events streaming. Best for real-time playback.
 *
 * We use /tts/bytes for simplicity and guaranteed format consistency on Android.
 * The response is raw audio that can be fed directly to Android's AudioTrack or MediaPlayer.
 *
 * API Docs: https://docs.cartesia.ai/api-reference/tts/bytes
 */
interface CartesiaApiService {

    /**
     * Generate speech from text.
     * Returns raw audio bytes in the format specified in the request body.
     *
     * Headers:
     * - Cartesia-Version: API version (required)
     * - X-API-Key: Your Cartesia API key (required)
     */
    @POST("tts/bytes")
    suspend fun textToSpeechBytes(
        @Body request: CartesiaTtsRequest,
        @Header("X-API-Key") apiKey: String,
        @Header("Cartesia-Version") version: String = "2024-06-10",
        @Header("Content-Type") contentType: String = "application/json"
    ): Response<ResponseBody>

    /**
     * Streaming TTS via Server-Sent Events.
     * Returns an SSE stream with raw PCM audio chunks.
     * Lower latency for real-time voice output.
     */
    @Streaming
    @POST("tts/sse")
    suspend fun textToSpeechStream(
        @Body request: CartesiaTtsRequest,
        @Header("X-API-Key") apiKey: String,
        @Header("Cartesia-Version") version: String = "2024-06-10",
        @Header("Content-Type") contentType: String = "application/json"
    ): Response<ResponseBody>

    /**
     * List available voices from the Cartesia voice library.
     */
    @GET("voices")
    suspend fun listVoices(
        @Header("X-API-Key") apiKey: String,
        @Header("Cartesia-Version") version: String = "2024-06-10"
    ): Response<CartesiaVoiceListResponse>
}

// Voice listing models
data class CartesiaVoiceListResponse(
    val voices: List<CartesiaVoiceInfo>
)

data class CartesiaVoiceInfo(
    val id: String,
    val name: String,
    val description: String?,
    val language: String?,
    val is_public: Boolean
)

/**
 * Retrofit interface for Speechify TTS API (alternative TTS provider).
 */
interface SpeechifyApiService {

    @POST("v1/audio/speech")
    suspend fun textToSpeech(
        @Body request: SpeechifyTtsRequest,
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json"
    ): Response<ResponseBody>
}

data class SpeechifyTtsRequest(
    val input: String,
    val voice_id: String = "george",
    val audio_format: String = "mp3"
)
