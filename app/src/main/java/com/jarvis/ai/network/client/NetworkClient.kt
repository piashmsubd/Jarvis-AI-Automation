package com.jarvis.ai.network.client

import com.google.gson.GsonBuilder
import com.jarvis.ai.network.api.*
import com.jarvis.ai.network.model.LlmProvider
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Centralized network client factory.
 *
 * Creates and caches Retrofit instances per base URL. All API services
 * share the same OkHttpClient configuration (timeouts, logging, interceptors).
 *
 * Usage:
 *   val llmApi = NetworkClient.getLlmService(LlmProvider.OPENROUTER)
 *   val cartesiaApi = NetworkClient.getCartesiaService()
 */
object NetworkClient {

    // ------------------------------------------------------------------ //
    //  OkHttp Configuration                                               //
    // ------------------------------------------------------------------ //

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)     // LLM responses can be slow
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            // Add common headers
            val request = chain.request().newBuilder()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "JarvisAI-Android/1.0")
                .build()
            chain.proceed(request)
        }
        .retryOnConnectionFailure(true)
        .build()

    /** Separate client for streaming with extended timeouts. */
    private val streamingOkHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val gson = GsonBuilder()
        .setLenient()
        .create()

    // ------------------------------------------------------------------ //
    //  Retrofit Instance Cache                                            //
    // ------------------------------------------------------------------ //

    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()

    private fun getRetrofit(baseUrl: String, streaming: Boolean = false): Retrofit {
        val key = "$baseUrl|streaming=$streaming"
        return retrofitCache.getOrPut(key) {
            Retrofit.Builder()
                .baseUrl(baseUrl.ensureTrailingSlash())
                .client(if (streaming) streamingOkHttpClient else okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        }
    }

    // ------------------------------------------------------------------ //
    //  Service Factories                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Get an LLM API service for OpenAI-compatible endpoints.
     * Works for: OpenRouter, OpenAI, Groq, and custom endpoints.
     */
    fun getLlmService(provider: LlmProvider, customBaseUrl: String? = null): LlmApiService {
        val baseUrl = when (provider) {
            LlmProvider.CUSTOM -> customBaseUrl ?: throw IllegalArgumentException("Custom provider requires a base URL")
            LlmProvider.FREEDOMGPT -> customBaseUrl?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
            else -> provider.defaultBaseUrl
        }
        return getRetrofit(baseUrl).create(LlmApiService::class.java)
    }

    /**
     * Get a streaming-capable LLM API service.
     */
    fun getLlmStreamingService(provider: LlmProvider, customBaseUrl: String? = null): LlmApiService {
        val baseUrl = when (provider) {
            LlmProvider.CUSTOM -> customBaseUrl ?: throw IllegalArgumentException("Custom provider requires a base URL")
            LlmProvider.FREEDOMGPT -> customBaseUrl?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
            else -> provider.defaultBaseUrl
        }
        return getRetrofit(baseUrl, streaming = true).create(LlmApiService::class.java)
    }

    /**
     * Get the Claude-native API service (for Anthropic's Messages API).
     */
    fun getClaudeService(): ClaudeApiService {
        return getRetrofit(LlmProvider.CLAUDE.defaultBaseUrl)
            .create(ClaudeApiService::class.java)
    }

    /**
     * Get the Gemini-native API service.
     */
    fun getGeminiService(): GeminiApiService {
        return getRetrofit(LlmProvider.GEMINI.defaultBaseUrl)
            .create(GeminiApiService::class.java)
    }

    /**
     * Get the Cartesia TTS API service.
     */
    fun getCartesiaService(): CartesiaApiService {
        return getRetrofit(CARTESIA_BASE_URL)
            .create(CartesiaApiService::class.java)
    }

    /**
     * Get the Speechify TTS API service.
     */
    fun getSpeechifyService(): SpeechifyApiService {
        return getRetrofit(SPEECHIFY_BASE_URL)
            .create(SpeechifyApiService::class.java)
    }

    /**
     * Clear the Retrofit cache (useful when user changes base URLs in settings).
     */
    fun clearCache() {
        retrofitCache.clear()
    }

    // ------------------------------------------------------------------ //
    //  Constants                                                          //
    // ------------------------------------------------------------------ //

    private const val CARTESIA_BASE_URL = "https://api.cartesia.ai/"
    private const val SPEECHIFY_BASE_URL = "https://api.sws.speechify.com/"

    private fun String.ensureTrailingSlash(): String {
        return if (endsWith("/")) this else "$this/"
    }
}
