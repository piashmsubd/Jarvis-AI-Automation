package com.jarvis.ai.network.api

import com.jarvis.ai.network.model.ChatCompletionRequest
import com.jarvis.ai.network.model.ChatCompletionResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for OpenAI-compatible chat completion APIs.
 * Works with: OpenRouter, OpenAI, Groq, and any OpenAI-compatible endpoint.
 *
 * Gemini and Claude use adapters that transform their native format
 * to/from this common interface.
 */
interface LlmApiService {

    /**
     * Standard chat completion (non-streaming).
     * POST /chat/completions
     */
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatCompletionRequest,
        @Header("Authorization") authorization: String,
        @Header("HTTP-Referer") referer: String = "https://jarvis-ai.app",
        @Header("X-Title") title: String = "Jarvis AI Automation"
    ): Response<ChatCompletionResponse>

    /**
     * Streaming chat completion.
     * Returns raw ResponseBody so we can read SSE lines.
     */
    @Streaming
    @POST("chat/completions")
    suspend fun chatCompletionStream(
        @Body request: ChatCompletionRequest,
        @Header("Authorization") authorization: String,
        @Header("HTTP-Referer") referer: String = "https://jarvis-ai.app",
        @Header("X-Title") title: String = "Jarvis AI Automation"
    ): Response<ResponseBody>
}

/**
 * Retrofit interface for Anthropic Claude's native Messages API.
 * Claude uses a different schema than OpenAI.
 */
interface ClaudeApiService {

    @POST("messages")
    suspend fun createMessage(
        @Body request: ClaudeMessageRequest,
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Header("content-type") contentType: String = "application/json"
    ): Response<ClaudeMessageResponse>
}

// Claude-native request/response models
data class ClaudeMessageRequest(
    val model: String,
    val max_tokens: Int = 2048,
    val system: String? = null,
    val messages: List<ClaudeMessage>
)

data class ClaudeMessage(
    val role: String,  // "user" or "assistant"
    val content: String
)

data class ClaudeMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ClaudeContentBlock>,
    val model: String,
    val usage: ClaudeUsage?
)

data class ClaudeContentBlock(
    val type: String,  // "text"
    val text: String
)

data class ClaudeUsage(
    val input_tokens: Int,
    val output_tokens: Int
)

/**
 * Retrofit interface for Google Gemini's native API.
 */
interface GeminiApiService {

    @POST("models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
}

// Gemini-native request/response models
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

data class GeminiContent(
    val role: String?,  // "user" or "model"
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String
)

data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    val usageMetadata: GeminiUsageMetadata?
)

data class GeminiCandidate(
    val content: GeminiContent,
    val finishReason: String?
)

data class GeminiUsageMetadata(
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?
)
