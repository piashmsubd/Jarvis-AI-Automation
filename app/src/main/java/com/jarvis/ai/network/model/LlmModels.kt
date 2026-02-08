package com.jarvis.ai.network.model

import com.google.gson.annotations.SerializedName

// ============================================================================
//  Shared request/response models for OpenAI-compatible APIs
//  (OpenRouter, OpenAI, Groq all use this schema; Gemini & Claude adapt to it)
// ============================================================================

// ----- Request -----

data class ChatCompletionRequest(
    @SerializedName("model")
    val model: String,

    @SerializedName("messages")
    val messages: List<ChatMessage>,

    @SerializedName("temperature")
    val temperature: Double = 0.7,

    @SerializedName("max_tokens")
    val maxTokens: Int? = 2048,

    @SerializedName("stream")
    val stream: Boolean = false,

    @SerializedName("top_p")
    val topP: Double? = null,

    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double? = null,

    @SerializedName("presence_penalty")
    val presencePenalty: Double? = null,

    /** OpenRouter-specific: route to specific provider */
    @SerializedName("provider")
    val provider: ProviderPreferences? = null,

    /** Tool/function calling support */
    @SerializedName("tools")
    val tools: List<Tool>? = null,

    @SerializedName("tool_choice")
    val toolChoice: String? = null  // "auto", "none", "required"
)

data class ChatMessage(
    @SerializedName("role")
    val role: String,  // "system", "user", "assistant", "tool"

    @SerializedName("content")
    val content: String,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("tool_call_id")
    val toolCallId: String? = null,

    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

data class ProviderPreferences(
    @SerializedName("order")
    val order: List<String>? = null,  // e.g., ["openai", "anthropic"]

    @SerializedName("allow_fallbacks")
    val allowFallbacks: Boolean = true
)

// ----- Tools / Function Calling -----

data class Tool(
    @SerializedName("type")
    val type: String = "function",

    @SerializedName("function")
    val function: FunctionDef
)

data class FunctionDef(
    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("parameters")
    val parameters: Map<String, Any>
)

data class ToolCall(
    @SerializedName("id")
    val id: String,

    @SerializedName("type")
    val type: String = "function",

    @SerializedName("function")
    val function: FunctionCall
)

data class FunctionCall(
    @SerializedName("name")
    val name: String,

    @SerializedName("arguments")
    val arguments: String  // JSON string of arguments
)

// ----- Response -----

data class ChatCompletionResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("object")
    val objectType: String?,

    @SerializedName("created")
    val created: Long?,

    @SerializedName("model")
    val model: String?,

    @SerializedName("choices")
    val choices: List<Choice>,

    @SerializedName("usage")
    val usage: Usage?
)

data class Choice(
    @SerializedName("index")
    val index: Int,

    @SerializedName("message")
    val message: ChatMessage,

    @SerializedName("finish_reason")
    val finishReason: String?
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,

    @SerializedName("completion_tokens")
    val completionTokens: Int,

    @SerializedName("total_tokens")
    val totalTokens: Int
)

// ============================================================================
//  Cartesia TTS Models
// ============================================================================

data class CartesiaTtsRequest(
    @SerializedName("model_id")
    val modelId: String = "sonic-2024-10-01",

    @SerializedName("transcript")
    val transcript: String,

    @SerializedName("voice")
    val voice: CartesiaVoice,

    @SerializedName("output_format")
    val outputFormat: CartesiaOutputFormat = CartesiaOutputFormat(),

    @SerializedName("language")
    val language: String = "en"
)

data class CartesiaVoice(
    @SerializedName("mode")
    val mode: String = "id",

    @SerializedName("id")
    val id: String  // Voice ID from Cartesia's voice library
)

data class CartesiaOutputFormat(
    @SerializedName("container")
    val container: String = "wav",

    @SerializedName("encoding")
    val encoding: String = "pcm_s16le",

    @SerializedName("sample_rate")
    val sampleRate: Int = 24000
)

// ============================================================================
//  Provider Configuration
// ============================================================================

/**
 * Enum of supported LLM providers. Each maps to a different base URL and
 * potentially different request format.
 */
enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1/",
        defaultModel = "openai/gpt-4o"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o"
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/",
        defaultModel = "gemini-2.0-flash"
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1/",
        defaultModel = "claude-sonnet-4-20250514"
    ),
    GROQ(
        displayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1/",
        defaultModel = "llama-3.3-70b-versatile"
    ),
    CUSTOM(
        displayName = "Custom Provider",
        defaultBaseUrl = "",
        defaultModel = ""
    );
}

enum class TtsProvider(val displayName: String) {
    CARTESIA("Cartesia"),
    SPEECHIFY("Speechify"),
    ANDROID_TTS("Android Built-in TTS")
}
