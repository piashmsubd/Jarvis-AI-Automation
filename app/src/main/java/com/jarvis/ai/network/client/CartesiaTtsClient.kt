package com.jarvis.ai.network.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.jarvis.ai.network.model.CartesiaOutputFormat
import com.jarvis.ai.network.model.CartesiaTtsRequest
import com.jarvis.ai.network.model.CartesiaVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * CartesiaTtsClient — Handles Text-to-Speech via Cartesia's API.
 *
 * Cartesia's /tts/bytes endpoint returns raw audio bytes. We download them
 * into a WAV file, then play via AudioTrack for low-latency playback.
 *
 * The voice ID can be changed in settings. Default is a natural male voice.
 * Browse voices at: https://play.cartesia.ai
 *
 * API Reference: https://docs.cartesia.ai/api-reference/tts/bytes
 */
class CartesiaTtsClient(
    private val apiKey: String,
    private val voiceId: String = DEFAULT_VOICE_ID,
    private val modelId: String = DEFAULT_MODEL_ID,
    private val sampleRate: Int = 24000
) {
    companion object {
        private const val TAG = "CartesiaTTS"

        // Default voice: "Confident British Male" — Jarvis-like
        const val DEFAULT_VOICE_ID = "a0e99841-438c-4a64-b679-ae501e7d6091"
        const val DEFAULT_MODEL_ID = "sonic-2024-10-01"

        // Some curated voice IDs for quick selection
        val PRESET_VOICES = mapOf(
            "British Male (Jarvis)" to "a0e99841-438c-4a64-b679-ae501e7d6091",
            "Professional Female" to "694f9389-aac1-45b6-b726-9d9369183238",
            "Warm Narrator" to "6ccbfb76-1fc6-48f7-b71d-91ac6298247b",
            "Friendly Assistant" to "f786b574-daa5-4673-aa0c-cbe3e8534c02"
        )
    }

    private val service = NetworkClient.getCartesiaService()
    private var audioTrack: AudioTrack? = null

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Synthesize text to speech and return raw audio bytes.
     * Does NOT play the audio — caller decides what to do with it.
     */
    suspend fun synthesize(
        text: String,
        language: String = "en"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = CartesiaTtsRequest(
                modelId = modelId,
                transcript = text,
                voice = CartesiaVoice(mode = "id", id = voiceId),
                outputFormat = CartesiaOutputFormat(
                    container = "wav",
                    encoding = "pcm_s16le",
                    sampleRate = sampleRate
                ),
                language = language
            )

            Log.d(TAG, "Synthesizing: '${text.take(50)}...' with voice=$voiceId")

            val response = service.textToSpeechBytes(
                request = request,
                apiKey = apiKey
            )

            if (response.isSuccessful && response.body() != null) {
                val bytes = response.body()!!.bytes()
                Log.d(TAG, "Received ${bytes.size} audio bytes")
                Result.success(bytes)
            } else {
                val error = response.errorBody()?.string() ?: "Unknown error"
                Log.e(TAG, "TTS failed: ${response.code()} - $error")
                Result.failure(RuntimeException("Cartesia error ${response.code()}: $error"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS exception", e)
            Result.failure(e)
        }
    }

    /**
     * Synthesize and immediately play the audio.
     * Blocks until playback is complete.
     */
    suspend fun speak(
        text: String,
        language: String = "en"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val audioResult = synthesize(text, language)

        audioResult.fold(
            onSuccess = { audioBytes ->
                try {
                    playWavBytes(audioBytes)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Playback failed", e)
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Synthesize and save to a WAV file.
     */
    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        language: String = "en"
    ): Result<File> = withContext(Dispatchers.IO) {
        val audioResult = synthesize(text, language)

        audioResult.fold(
            onSuccess = { audioBytes ->
                try {
                    FileOutputStream(outputFile).use { it.write(audioBytes) }
                    Log.d(TAG, "Saved audio to ${outputFile.absolutePath}")
                    Result.success(outputFile)
                } catch (e: Exception) {
                    Log.e(TAG, "File write failed", e)
                    Result.failure(e)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Stop any currently playing audio.
     */
    fun stop() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio", e)
        }
    }

    /**
     * List available voices from Cartesia's library.
     */
    suspend fun listVoices(): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val response = service.listVoices(apiKey = apiKey)
            if (response.isSuccessful && response.body() != null) {
                val voices = response.body()!!.voices.map { it.name to it.id }
                Result.success(voices)
            } else {
                Result.failure(RuntimeException("Failed to list voices: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ //
    //  Audio Playback                                                     //
    // ------------------------------------------------------------------ //

    /**
     * Plays raw WAV bytes via AudioTrack for minimal-latency playback.
     * WAV header is 44 bytes; PCM data follows.
     */
    private fun playWavBytes(wavBytes: ByteArray) {
        // Skip WAV header (44 bytes for standard WAV)
        val headerSize = 44
        if (wavBytes.size <= headerSize) {
            Log.w(TAG, "Audio data too small: ${wavBytes.size} bytes")
            return
        }

        val pcmData = wavBytes.copyOfRange(headerSize, wavBytes.size)

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.apply {
            write(pcmData, 0, pcmData.size)
            play()

            // Wait for playback to complete
            val durationMs = (pcmData.size.toLong() * 1000) / (sampleRate * 2) // 16-bit = 2 bytes per sample
            Thread.sleep(durationMs + 100) // small buffer for safety

            stop()
            release()
        }

        audioTrack = null
    }
}
