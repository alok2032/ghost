package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ModelCategory(val label: String) {
    GENERAL("General & Multimodal"),
    REASONING("Deep Reasoning"),
    CODING("Coding & STEM"),
    RESEARCH("Web & Research"),
    IMAGE("Image Gen & Edit"),
    AUDIO("Audio & Speech"),
    LOCAL("Local / Self-Hosted")
}

enum class ProviderTierStatus(val label: String) {
    FREE_FIREBASE_AI_LOGIC("Free (Firebase AI Logic)"),
    FREE_TIER_API("Free API Tier"),
    OPEN_WEIGHTS_FREE_TIER("Free / Open Pool"),
    PAID_API("Paid API (User Key)"),
    SELF_HOSTED("Self-Hosted / Local")
}

enum class ModelHealthStatus(val label: String) {
    OPERATIONAL("Operational"),
    DEGRADED("Degraded"),
    OFFLINE("Error / Offline"),
    UNCONFIGURED("Key Needed"),
    UNKNOWN("Not Checked")
}

data class RegistryModelEntry(
    val id: String,
    val provider: AiProviderType,
    val modelId: String,
    val displayName: String,
    val category: ModelCategory,
    val endpoint: String,
    val authRequirement: String,
    val tierStatus: ProviderTierStatus,
    val rateLimitNote: String,
    val contextWindow: String,
    val supportsStreaming: Boolean,
    val supportsVision: Boolean,
    val supportsToolCalling: Boolean,
    val supportsImageGen: Boolean = false,
    val supportsAudio: Boolean = false,
    val modelVersion: String,
    val healthStatus: ModelHealthStatus = ModelHealthStatus.UNKNOWN,
    val latencyMs: Long? = null,
    val lastCheckedMs: Long? = null,
    val lastError: String? = null,
    val verificationStatus: ModelVerificationStatus = ModelVerificationStatus.CONFIGURED,
    val verificationMethod: String = "static_baseline",
    val pricingVerified: Boolean = false,
    val failureReason: String? = null
)

/**
 * Continuously maintainable AI Provider Registry & Live Health Monitor.
 * Never claims a model is operational without checking configuration and live reachability.
 */
object AiModelRegistry {

    private val _entries = MutableStateFlow(buildInitialCatalog(null))
    val entries: StateFlow<List<RegistryModelEntry>> = _entries.asStateFlow()

    fun refreshConfigurationStates(context: Context?) {
        val currentMap = _entries.value.associateBy { it.id }
        val rebuilt = buildInitialCatalog(context).map { fresh ->
            val prev = currentMap[fresh.id]
            val catalogEntry = ModelCatalog.findEntry(fresh.provider, fresh.modelId)
            val catalogVerification = catalogEntry?.verificationStatus ?: fresh.verificationStatus
            val catalogMethod = catalogEntry?.verificationMethod ?: fresh.verificationMethod
            val catalogPricingVerified = catalogEntry?.pricingVerified ?: fresh.pricingVerified
            val catalogReason = catalogEntry?.failureReason
            if (prev != null && prev.lastCheckedMs != null && fresh.healthStatus != ModelHealthStatus.UNCONFIGURED) {
                fresh.copy(
                    healthStatus = prev.healthStatus,
                    latencyMs = prev.latencyMs,
                    lastCheckedMs = prev.lastCheckedMs,
                    lastError = prev.lastError ?: catalogReason,
                    verificationStatus = catalogVerification,
                    verificationMethod = catalogMethod,
                    pricingVerified = catalogPricingVerified,
                    failureReason = catalogReason
                )
            } else {
                fresh.copy(
                    verificationStatus = catalogVerification,
                    verificationMethod = catalogMethod,
                    pricingVerified = catalogPricingVerified,
                    failureReason = catalogReason
                )
            }
        }
        _entries.value = rebuilt
    }

    fun buildInitialCatalog(context: Context?): List<RegistryModelEntry> {
        val firebaseReady = context != null && FirebaseAiEngine.isAvailable(context)
        val ext = if (context != null) ExternalAiProviders(context) else null

        fun statusFor(provider: AiProviderType, firebaseManaged: Boolean = false): ModelHealthStatus {
            if (firebaseManaged) {
                return if (firebaseReady) ModelHealthStatus.UNKNOWN else ModelHealthStatus.OFFLINE
            }
            if (provider == AiProviderType.POLLINATIONS_FREE) {
                val enabled = context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                return if (enabled) ModelHealthStatus.UNKNOWN else ModelHealthStatus.UNCONFIGURED
            }
            val configured = ext?.isProviderConfigured(provider) ?: false
            return if (configured) ModelHealthStatus.UNKNOWN else ModelHealthStatus.UNCONFIGURED
        }

        val comfyConfigured = context != null && AyraPreferences.getComfyUiUrl(context).isNotBlank()

        return listOf(
            RegistryModelEntry(
                id = "fb_gemini_3_8_flash",
                provider = AiProviderType.GEMINI,
                modelId = FirebaseAiEngine.MODEL_FLASH,
                displayName = "Gemini 3.8 Flash (Firebase AI)",
                category = ModelCategory.GENERAL,
                endpoint = "Firebase AI Logic (App Check)",
                authRequirement = "Firebase App Check",
                tierStatus = ProviderTierStatus.FREE_FIREBASE_AI_LOGIC,
                rateLimitNote = "Firebase Spark/Blaze quota",
                contextWindow = "1M tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                supportsAudio = true,
                modelVersion = "3.8-flash",
                healthStatus = statusFor(AiProviderType.GEMINI, firebaseManaged = true)
            ),
            RegistryModelEntry(
                id = "fb_gemini_3_5_flash_lite",
                provider = AiProviderType.GEMINI,
                modelId = FirebaseAiEngine.MODEL_FAST_LITE,
                displayName = "Gemini 3.5 Flash-Lite",
                category = ModelCategory.GENERAL,
                endpoint = "Firebase AI Logic (App Check)",
                authRequirement = "Firebase App Check",
                tierStatus = ProviderTierStatus.FREE_FIREBASE_AI_LOGIC,
                rateLimitNote = "High-volume Spark/Blaze quota",
                contextWindow = "1M tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "3.5-flash-lite",
                healthStatus = statusFor(AiProviderType.GEMINI, firebaseManaged = true)
            ),
            RegistryModelEntry(
                id = "fb_gemini_4_argon",
                provider = AiProviderType.GEMINI,
                modelId = FirebaseAiEngine.MODEL_PRO_REASONING,
                displayName = "Gemini 4 Argon (Deep Reasoning)",
                category = ModelCategory.REASONING,
                endpoint = "Firebase AI Logic (App Check)",
                authRequirement = "Firebase App Check + Blaze",
                tierStatus = ProviderTierStatus.PAID_API,
                rateLimitNote = "Requires Blaze billing on Firebase project",
                contextWindow = "2M tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "4-argon",
                healthStatus = statusFor(AiProviderType.GEMINI, firebaseManaged = true)
            ),
            RegistryModelEntry(
                id = "fb_nano_banana_2_1",
                provider = AiProviderType.GEMINI,
                modelId = FirebaseAiEngine.MODEL_IMAGE_NANO_BANANA,
                displayName = "Gemini Nano Banana 2.1",
                category = ModelCategory.IMAGE,
                endpoint = "Firebase AI Logic (App Check)",
                authRequirement = "Firebase App Check + Blaze",
                tierStatus = ProviderTierStatus.PAID_API,
                rateLimitNote = "Native image gen & editing (Blaze)",
                contextWindow = "128k tokens",
                supportsStreaming = false,
                supportsVision = true,
                supportsToolCalling = false,
                supportsImageGen = true,
                modelVersion = "nano-banana-2.1",
                healthStatus = statusFor(AiProviderType.GEMINI, firebaseManaged = true)
            ),
            RegistryModelEntry(
                id = "groq_qwen_3_8_27b",
                provider = AiProviderType.GROQ,
                modelId = ExternalAiProviders.GROQ_QWEN_MODEL,
                displayName = "Groq Qwen 3.8 27B",
                category = ModelCategory.REASONING,
                endpoint = "https://api.groq.com/openai/v1/chat/completions",
                authRequirement = "Bearer API Key (Free Console)",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "30 RPM / 14.4K RPD free tier",
                contextWindow = "131k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "qwen3.8-27b",
                healthStatus = statusFor(AiProviderType.GROQ)
            ),
            RegistryModelEntry(
                id = "cerebras_gpt_oss_120b",
                provider = AiProviderType.CEREBRAS,
                modelId = ExternalAiProviders.CEREBRAS_LLAMA_MODEL,
                displayName = "Cerebras GPT-OSS 120B",
                category = ModelCategory.GENERAL,
                endpoint = "https://api.cerebras.ai/v1/chat/completions",
                authRequirement = "Bearer API Key (Free Tier)",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "High-throughput free developer tier",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "gpt-oss-120b",
                healthStatus = statusFor(AiProviderType.CEREBRAS)
            ),
            RegistryModelEntry(
                id = "sambanova_deepseek_v3",
                provider = AiProviderType.SAMBANOVA,
                modelId = ExternalAiProviders.SAMBANOVA_DEEPSEEK_MODEL,
                displayName = "SambaNova DeepSeek V3.1 Terminus",
                category = ModelCategory.REASONING,
                endpoint = "https://api.sambanova.ai/v1/chat/completions",
                authRequirement = "Bearer API Key (Free Cloud Tier)",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "SambaNova Cloud free developer quota",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "deepseek-v3.1",
                healthStatus = statusFor(AiProviderType.SAMBANOVA)
            ),
            RegistryModelEntry(
                id = "sambanova_whisper_v3",
                provider = AiProviderType.SAMBANOVA,
                modelId = ExternalAiProviders.SAMBANOVA_WHISPER_MODEL,
                displayName = "SambaNova / Groq Whisper Large v3",
                category = ModelCategory.AUDIO,
                endpoint = "https://api.sambanova.ai/v1/audio/transcriptions",
                authRequirement = "SambaNova or Groq API Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "Audio transcription (Speech-to-Text)",
                contextWindow = "25MB audio",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                supportsAudio = true,
                modelVersion = "whisper-v3",
                healthStatus = if (ext?.isProviderConfigured(AiProviderType.SAMBANOVA) == true || ext?.isProviderConfigured(AiProviderType.GROQ) == true) ModelHealthStatus.UNKNOWN else ModelHealthStatus.UNCONFIGURED
            ),
            RegistryModelEntry(
                id = "huggingface_qwen_72b",
                provider = AiProviderType.HUGGINGFACE,
                modelId = ExternalAiProviders.HF_QWEN_MODEL,
                displayName = "Hugging Face Qwen 2.5 72B",
                category = ModelCategory.CODING,
                endpoint = "https://router.huggingface.co/v1/chat/completions",
                authRequirement = "HF Token (Free Tier)",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "Monthly free inference credits",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "2.5-72b",
                healthStatus = statusFor(AiProviderType.HUGGINGFACE)
            ),
            RegistryModelEntry(
                id = "github_models_retired",
                provider = AiProviderType.GITHUB_MODELS,
                modelId = ExternalAiProviders.GITHUB_RETIRED_MODEL,
                displayName = "GitHub Models (Azure Inference Retired)",
                category = ModelCategory.CODING,
                endpoint = "https://models.inference.ai.azure.com/chat/completions",
                authRequirement = "Retired 2026-07-30",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "Azure inference endpoint shut down 2026-07-30",
                contextWindow = "128k tokens",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                modelVersion = "retired",
                healthStatus = ModelHealthStatus.OFFLINE,
                verificationStatus = ModelVerificationStatus.RETIRED,
                failureReason = "models.inference.ai.azure.com shut down 2026-07-30"
            ),
            RegistryModelEntry(
                id = "nim_llama_3_3_70b",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = ExternalAiProviders.NVIDIA_LLAMA_MODEL,
                displayName = "NVIDIA NIM Llama 3.3 70B Instruct",
                category = ModelCategory.GENERAL,
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                authRequirement = "NVIDIA Build Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "NVIDIA developer verified baseline",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "3.3-70b",
                healthStatus = statusFor(AiProviderType.NVIDIA_NIM)
            ),
            RegistryModelEntry(
                id = "nim_minimax_m3_1",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = ExternalAiProviders.NVIDIA_MINIMAX_MODEL,
                displayName = "NVIDIA NIM MiniMax M3.1 Flash",
                category = ModelCategory.GENERAL,
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                authRequirement = "NVIDIA Build Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "NVIDIA developer trial credits",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "m3.1-flash",
                healthStatus = statusFor(AiProviderType.NVIDIA_NIM)
            ),
            RegistryModelEntry(
                id = "nim_glm_5_3",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = ExternalAiProviders.NVIDIA_GLM_MODEL,
                displayName = "NVIDIA NIM Z-AI GLM 5.3 Flash",
                category = ModelCategory.CODING,
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                authRequirement = "NVIDIA Build Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "NVIDIA developer trial credits",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "glm-5.3",
                healthStatus = statusFor(AiProviderType.NVIDIA_NIM)
            ),
            RegistryModelEntry(
                id = "nim_muse_30b",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = ExternalAiProviders.NVIDIA_MUSE_MODEL,
                displayName = "NVIDIA NIM Meta Muse 30B",
                category = ModelCategory.GENERAL,
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                authRequirement = "NVIDIA Build Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "NVIDIA developer trial credits",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "muse-30b",
                healthStatus = statusFor(AiProviderType.NVIDIA_NIM)
            ),
            RegistryModelEntry(
                id = "nim_gemma_4_31b",
                provider = AiProviderType.NVIDIA_NIM,
                modelId = ExternalAiProviders.NVIDIA_GEMMA_MODEL,
                displayName = "NVIDIA NIM Gemma 4 31B IT",
                category = ModelCategory.REASONING,
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                authRequirement = "NVIDIA Build Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "NVIDIA developer trial credits",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "gemma-4-31b",
                healthStatus = statusFor(AiProviderType.NVIDIA_NIM)
            ),
            RegistryModelEntry(
                id = "openrouter_free",
                provider = AiProviderType.OPENROUTER,
                modelId = ExternalAiProviders.OPENROUTER_DEFAULT_MODEL,
                displayName = "OpenRouter Universal Gateway",
                category = ModelCategory.RESEARCH,
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                authRequirement = "OpenRouter API Key",
                tierStatus = ProviderTierStatus.FREE_TIER_API,
                rateLimitNote = "Free :free models + BYOK",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "free",
                healthStatus = statusFor(AiProviderType.OPENROUTER)
            ),
            RegistryModelEntry(
                id = "openai_gpt_6_2_sol",
                provider = AiProviderType.OPENAI,
                modelId = ExternalAiProviders.OPENAI_DEFAULT_MODEL,
                displayName = "OpenAI GPT-6.2 Sol",
                category = ModelCategory.GENERAL,
                endpoint = "https://api.openai.com/v1/chat/completions",
                authRequirement = "OpenAI API Key",
                tierStatus = ProviderTierStatus.PAID_API,
                rateLimitNote = "Pay-as-you-go OpenAI tier",
                contextWindow = "256k tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "gpt-6.2-sol",
                healthStatus = statusFor(AiProviderType.OPENAI)
            ),
            RegistryModelEntry(
                id = "anthropic_claude_5_5",
                provider = AiProviderType.ANTHROPIC,
                modelId = ExternalAiProviders.ANTHROPIC_SONNET_MODEL,
                displayName = "Anthropic Claude Sonnet 5.5",
                category = ModelCategory.CODING,
                endpoint = "https://api.anthropic.com/v1/messages",
                authRequirement = "Anthropic API Key",
                tierStatus = ProviderTierStatus.PAID_API,
                rateLimitNote = "Pay-as-you-go Anthropic tier",
                contextWindow = "200k tokens",
                supportsStreaming = true,
                supportsVision = true,
                supportsToolCalling = true,
                modelVersion = "sonnet-5.5",
                healthStatus = statusFor(AiProviderType.ANTHROPIC)
            ),
            RegistryModelEntry(
                id = "deepseek_v4_pro",
                provider = AiProviderType.DEEPSEEK,
                modelId = ExternalAiProviders.DEEPSEEK_REASONER_MODEL,
                displayName = "DeepSeek V4 Pro",
                category = ModelCategory.REASONING,
                endpoint = "https://api.deepseek.com/chat/completions",
                authRequirement = "DeepSeek API Key",
                tierStatus = ProviderTierStatus.PAID_API,
                rateLimitNote = "Pay-as-you-go DeepSeek tier",
                contextWindow = "128k tokens",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "v4-pro",
                healthStatus = statusFor(AiProviderType.DEEPSEEK)
            ),
            RegistryModelEntry(
                id = "pollinations_free_pool",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "openai",
                displayName = "Global Free AI Pool (Fast Chat & Flux)",
                category = ModelCategory.GENERAL,
                endpoint = "https://text.pollinations.ai/ & image.pollinations.ai",
                authRequirement = "None (Auto-Connected Keyless)",
                tierStatus = ProviderTierStatus.OPEN_WEIGHTS_FREE_TIER,
                rateLimitNote = "Always-on keyless specialist & Flux visual tier",
                contextWindow = "32k tokens",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                supportsImageGen = true,
                modelVersion = "flux+text",
                healthStatus = statusFor(AiProviderType.POLLINATIONS_FREE)
            ),
            RegistryModelEntry(
                id = "pollinations_free_openai_large",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "openai-large",
                displayName = "Global Free Flagship (Large)",
                category = ModelCategory.RESEARCH,
                endpoint = "https://text.pollinations.ai/",
                authRequirement = "None (Auto-Connected Keyless)",
                tierStatus = ProviderTierStatus.OPEN_WEIGHTS_FREE_TIER,
                rateLimitNote = "High-reasoning keyless ensemble synthesizer",
                contextWindow = "64k tokens",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                modelVersion = "openai-large",
                healthStatus = statusFor(AiProviderType.POLLINATIONS_FREE)
            ),
            RegistryModelEntry(
                id = "pollinations_free_qwen_coder",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "qwen-coder",
                displayName = "Global Free Qwen Coder",
                category = ModelCategory.CODING,
                endpoint = "https://text.pollinations.ai/",
                authRequirement = "None (Auto-Connected Keyless)",
                tierStatus = ProviderTierStatus.OPEN_WEIGHTS_FREE_TIER,
                rateLimitNote = "Keyless coding & architecture specialist",
                contextWindow = "32k tokens",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                modelVersion = "qwen-coder",
                healthStatus = statusFor(AiProviderType.POLLINATIONS_FREE)
            ),
            RegistryModelEntry(
                id = "pollinations_free_deepseek",
                provider = AiProviderType.POLLINATIONS_FREE,
                modelId = "deepseek",
                displayName = "Global Free DeepSeek R1/V3",
                category = ModelCategory.REASONING,
                endpoint = "https://text.pollinations.ai/",
                authRequirement = "None (Auto-Connected Keyless)",
                tierStatus = ProviderTierStatus.OPEN_WEIGHTS_FREE_TIER,
                rateLimitNote = "Keyless STEM, math & CoT reasoning specialist",
                contextWindow = "64k tokens",
                supportsStreaming = false,
                supportsVision = false,
                supportsToolCalling = false,
                modelVersion = "deepseek",
                healthStatus = statusFor(AiProviderType.POLLINATIONS_FREE)
            ),
            RegistryModelEntry(
                id = "local_ollama_lan",
                provider = AiProviderType.LOCAL_OLLAMA,
                modelId = "llama3.3",
                displayName = "Local Ollama / LM Studio",
                category = ModelCategory.LOCAL,
                endpoint = context?.let { AyraPreferences.getCustomEndpointUrl(it).ifBlank { AyraPreferences.getOllamaHost(it) } } ?: "http://10.0.2.2:11434/v1/",
                authRequirement = "None / Optional LAN Token",
                tierStatus = ProviderTierStatus.SELF_HOSTED,
                rateLimitNote = "Unlimited on local hardware",
                contextWindow = "Hardware dependent",
                supportsStreaming = true,
                supportsVision = false,
                supportsToolCalling = true,
                modelVersion = "local-gguf",
                healthStatus = statusFor(AiProviderType.LOCAL_OLLAMA)
            ),
            RegistryModelEntry(
                id = "self_hosted_comfyui",
                provider = AiProviderType.LOCAL_OLLAMA,
                modelId = "comfyui-flux-wan",
                displayName = "ComfyUI Self-Hosted Workflow Server",
                category = ModelCategory.IMAGE,
                endpoint = context?.let { AyraPreferences.getComfyUiUrl(it).ifBlank { "http://10.0.2.2:8188" } } ?: "http://10.0.2.2:8188",
                authRequirement = "Server URL in Key Vault",
                tierStatus = ProviderTierStatus.SELF_HOSTED,
                rateLimitNote = "Real Flux / SDXL / Wan2.1 when server running",
                contextWindow = "Workflow JSON",
                supportsStreaming = false,
                supportsVision = true,
                supportsToolCalling = false,
                supportsImageGen = true,
                modelVersion = "comfy-rest-v1",
                healthStatus = if (comfyConfigured) ModelHealthStatus.UNKNOWN else ModelHealthStatus.UNCONFIGURED
            )
        )
    }

    /**
     * Performs a real network/SDK health verification on a specific [RegistryModelEntry].
     */
    suspend fun pingEntry(
        context: Context?,
        entry: RegistryModelEntry,
        httpClient: OkHttpClient? = null
    ): RegistryModelEntry = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val client = httpClient ?: com.ayra.ai.app.security.NetworkSecurityHelper.newClient(
            connectTimeoutSec = 4L,
            readTimeoutSec = 6L,
            callTimeoutSec = 15L
        )

        // 1. Firebase AI Logic entries
        if (entry.id.startsWith("fb_")) {
            if (context == null || !FirebaseAiEngine.isAvailable(context)) {
                return@withContext entry.copy(
                    healthStatus = ModelHealthStatus.OFFLINE,
                    lastCheckedMs = now,
                    lastError = "FirebaseApp not initialized"
                )
            }
            if (entry.id == "fb_gemini_3_8_flash" || entry.id == "fb_gemini_3_5_flash_lite") {
                val start = System.currentTimeMillis()
                return@withContext try {
                    val out = FirebaseAiEngine(context).generateText(
                        prompt = "Reply only OK",
                        modelName = entry.modelId,
                        enableSearchGrounding = false
                    )
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    if (out.isNotBlank()) {
                        entry.copy(
                            healthStatus = ModelHealthStatus.OPERATIONAL,
                            latencyMs = latency,
                            lastCheckedMs = now,
                            lastError = null
                        )
                    } else {
                        entry.copy(
                            healthStatus = ModelHealthStatus.DEGRADED,
                            latencyMs = latency,
                            lastCheckedMs = now,
                            lastError = "Empty response (check App Check / quota)"
                        )
                    }
                } catch (e: Exception) {
                    entry.copy(
                        healthStatus = ModelHealthStatus.OFFLINE,
                        lastCheckedMs = now,
                        lastError = e.message ?: "Firebase AI error"
                    )
                }
            } else {
                // Avoid costly Blaze-only image/argon test pings unless user triggers generation; never claim OPERATIONAL without probing
                return@withContext entry.copy(
                    healthStatus = ModelHealthStatus.UNKNOWN,
                    latencyMs = null,
                    lastCheckedMs = now,
                    lastError = "Configured (Blaze required on call — unprobed)"
                )
            }
        }

        // 2. ComfyUI endpoint probe
        if (entry.id == "self_hosted_comfyui") {
            val url = context?.let { AyraPreferences.getComfyUiUrl(it) } ?: ""
            if (url.isBlank()) {
                return@withContext entry.copy(
                    healthStatus = ModelHealthStatus.UNCONFIGURED,
                    lastCheckedMs = now,
                    lastError = "Set ComfyUI URL in Tools & Keys"
                )
            }
            val urlCheck = com.ayra.ai.app.security.NetworkSecurityHelper.validateLocalAiEndpointUrl(context, url)
            if (!urlCheck.allowed) {
                return@withContext entry.copy(
                    healthStatus = ModelHealthStatus.OFFLINE,
                    lastCheckedMs = now,
                    lastError = urlCheck.reason ?: "Blocked ComfyUI endpoint URL"
                )
            }
            val comfyClient = httpClient ?: com.ayra.ai.app.security.NetworkSecurityHelper.newClient(
                connectTimeoutSec = 4L,
                readTimeoutSec = 6L,
                callTimeoutSec = 15L,
                allowLanCleartext = true
            )
            val start = System.currentTimeMillis()
            return@withContext try {
                val req = Request.Builder().url("${url.trimEnd('/')}/system_stats").get().build()
                comfyClient.newCall(req).execute().use { resp ->
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    if (resp.isSuccessful) {
                        entry.copy(healthStatus = ModelHealthStatus.OPERATIONAL, latencyMs = latency, lastCheckedMs = now, lastError = null)
                    } else {
                        entry.copy(healthStatus = ModelHealthStatus.OFFLINE, latencyMs = latency, lastCheckedMs = now, lastError = "HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                entry.copy(healthStatus = ModelHealthStatus.OFFLINE, lastCheckedMs = now, lastError = e.message)
            }
        }

        // 3. External REST providers
        val ext = ExternalAiProviders(context)
        if (!ext.isProviderConfigured(entry.provider)) {
            return@withContext entry.copy(
                healthStatus = ModelHealthStatus.UNCONFIGURED,
                lastCheckedMs = now,
                lastError = "API key not configured"
            )
        }

        return@withContext try {
            val latency = ext.testProviderConnection(entry.provider, customModel = entry.modelId)
            ModelCatalog.recordModelSuccess(entry.provider, entry.modelId)
            entry.copy(
                healthStatus = ModelHealthStatus.OPERATIONAL,
                latencyMs = latency,
                lastCheckedMs = now,
                lastError = null,
                failureReason = null
            )
        } catch (e: Exception) {
            val status = if (e is ProviderError.RateLimited) ModelHealthStatus.DEGRADED else ModelHealthStatus.OFFLINE
            val httpStatus = when (e) {
                is ProviderError.RateLimited -> 429
                is ProviderError.ModelNotFound -> e.httpStatus
                else -> 503
            }
            if (httpStatus == 410) {
                ModelCatalog.markModelRetired(entry.provider, entry.modelId, e.message ?: "Model retired")
            } else {
                ModelCatalog.markModelUnavailable(
                    entry.provider,
                    entry.modelId,
                    httpStatus,
                    e.message ?: "Connection failed"
                )
            }
            entry.copy(
                healthStatus = status,
                lastCheckedMs = now,
                lastError = e.message ?: "Connection failed",
                verificationStatus = if (e is ProviderError.RateLimited) ModelVerificationStatus.RATE_LIMITED else ModelVerificationStatus.UNAVAILABLE,
                failureReason = e.message
            )
        }
    }

    suspend fun runHealthSweep(context: Context?): List<RegistryModelEntry> = coroutineScope {
        refreshConfigurationStates(context)
        val current = _entries.value
        val updated = current.map { entry ->
            async(Dispatchers.IO) {
                if (entry.healthStatus == ModelHealthStatus.UNCONFIGURED) {
                    entry.copy(lastCheckedMs = System.currentTimeMillis())
                } else {
                    pingEntry(context, entry)
                }
            }
        }.awaitAll()
        _entries.value = updated
        updated
    }

    /**
     * Queries live `/models` endpoints on configured providers via [ExternalAiProviders.discoverDynamicModels]
     * and merges newly discovered models into [_entries] without duplicating existing entries.
     */
    suspend fun discoverAndMergeLiveModels(context: Context): Int = withContext(Dispatchers.IO) {
        refreshConfigurationStates(context)
        val ext = ExternalAiProviders(context)
        val activeProviders = ext.getActiveConfiguredProviders()
            .filter { it != AiProviderType.POLLINATIONS_FREE && it != AiProviderType.OMNI_ROUTE }

        var addedCount = 0
        val currentList = _entries.value.toMutableList()
        val existingModelPairs = currentList.map { "${it.provider.id}:${it.modelId}" }.toMutableSet()

        coroutineScope {
            val results = activeProviders.map { provider ->
                async(Dispatchers.IO) {
                    provider to runCatching { ext.discoverDynamicModels(provider) }.getOrDefault(emptyList())
                }
            }.awaitAll()

            val now = System.currentTimeMillis()
            for ((provider, models) in results) {
                models.take(6).forEach { info ->
                    val pairKey = "${provider.id}:${info.id}"
                    if (existingModelPairs.add(pairKey)) {
                        val safeId = "live_${provider.id}_${info.id.replace(Regex("[^a-zA-Z0-9_]"), "_").take(36)}"
                        currentList.add(
                            RegistryModelEntry(
                                id = safeId,
                                provider = provider,
                                modelId = info.id,
                                displayName = "${info.displayName} (Discovered)",
                                category = if (info.isReasoning) ModelCategory.REASONING else ModelCategory.GENERAL,
                                endpoint = provider.defaultBaseUrl,
                                authRequirement = "Configured Key",
                                tierStatus = when (provider) {
                                    AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                                    AiProviderType.GITHUB_MODELS, AiProviderType.HUGGINGFACE, AiProviderType.NVIDIA_NIM -> ProviderTierStatus.FREE_TIER_API
                                    AiProviderType.GEMINI -> ProviderTierStatus.FREE_FIREBASE_AI_LOGIC
                                    else -> ProviderTierStatus.PAID_API
                                },
                                rateLimitNote = "Discovered via live /models catalog",
                                contextWindow = info.contextWindow,
                                supportsStreaming = true,
                                supportsVision = info.supportsVision,
                                supportsToolCalling = true,
                                modelVersion = "live",
                                healthStatus = ModelHealthStatus.UNKNOWN,
                                lastCheckedMs = now,
                                verificationStatus = ModelVerificationStatus.VERIFIED_LIVE
                            )
                        )
                        addedCount++
                    }
                }
            }
        }
        var backendAdded = 0
        if (com.ayra.ai.app.security.SecureAiGatewayClient.shouldUseBackendGatewayProxy(context, false)) {
            backendAdded = syncBackendModelCatalog(
                context = context,
                backendBaseUrl = AyraPreferences.getBackendGatewayUrl(context),
                targetList = currentList,
                existingPairs = existingModelPairs
            )
            addedCount += backendAdded
        }

        if (addedCount > 0) {
            _entries.value = currentList
        }
        addedCount
    }

    suspend fun syncBackendModelCatalog(
        context: Context,
        backendBaseUrl: String,
        targetList: MutableList<RegistryModelEntry>? = null,
        existingPairs: MutableSet<String>? = null,
        httpClient: OkHttpClient? = null,
        bearerTokenOverride: String? = null,
        allowUnvalidatedUrlForTests: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val res = com.ayra.ai.app.security.SecureAiGatewayClient.fetchBackendModelCatalog(
            context = context,
            backendBaseUrl = backendBaseUrl,
            httpClient = httpClient,
            bearerTokenOverride = bearerTokenOverride,
            allowUnvalidatedUrlForTests = allowUnvalidatedUrlForTests
        )
        val payload = res.getOrElse { err ->
            android.util.Log.w("AiModelRegistry", "Route B catalog sync skipped: ${err.message}")
            return@withContext 0
        }
        AyraPreferences.setRemoteModelCatalogJson(context, payload.toString())

        val modelsArr = payload.optJSONArray("models") ?: return@withContext 0
        val workingList = targetList ?: _entries.value.toMutableList()
        val seenPairs = existingPairs ?: workingList.map { "${it.provider.id}:${it.modelId}" }.toMutableSet()
        val now = System.currentTimeMillis()
        var merged = 0

        for (i in 0 until modelsArr.length()) {
            val obj = modelsArr.optJSONObject(i) ?: continue
            val modelId = obj.optString("id").trim()
            if (modelId.isBlank()) continue
            val rawProv = obj.optString("provider").trim().lowercase()
            val mappedProvider = when (rawProv) {
                "gemini" -> AiProviderType.GEMINI
                "openrouter" -> AiProviderType.OPENROUTER
                "nvidia", "nvidia_nim" -> AiProviderType.NVIDIA_NIM
                "groq" -> AiProviderType.GROQ
                else -> AiProviderType.OPENROUTER
            }
            val pairKey = "${mappedProvider.id}:$modelId"
            if (!seenPairs.add(pairKey)) continue

            val caps = obj.optJSONArray("capabilities")
            val capSet = buildSet {
                if (caps != null) {
                    for (j in 0 until caps.length()) add(caps.optString(j).uppercase())
                }
            }
            val statusStr = obj.optString("verificationStatus", "STATIC_DEFAULT")
            val available = obj.optBoolean("availableViaGateway", false)
            val category = when {
                "IMAGE_GEN" in capSet -> ModelCategory.IMAGE
                "AUDIO" in capSet -> ModelCategory.AUDIO
                "CODE" in capSet -> ModelCategory.CODING
                "REASONING" in capSet -> ModelCategory.REASONING
                else -> ModelCategory.GENERAL
            }
            val contextTokens = obj.optInt("contextTokens", 128_000)
            val contextWindow = if (contextTokens >= 1_000_000) "${contextTokens / 1_000_000}M tokens" else "${contextTokens / 1_000}k tokens"

            workingList.add(
                RegistryModelEntry(
                    id = "gateway_${mappedProvider.id}_${modelId.replace(Regex("[^a-zA-Z0-9_]"), "_").take(32)}",
                    provider = mappedProvider,
                    modelId = modelId,
                    displayName = "${obj.optString("displayName", modelId)} (Gateway)",
                    category = category,
                    endpoint = "${backendBaseUrl.trimEnd('/')}/api/v1/ai/chat",
                    authRequirement = "Firebase Auth + Route B Gateway",
                    tierStatus = ProviderTierStatus.FREE_TIER_API,
                    rateLimitNote = if (available) "Verified via Route B Gateway" else "Gateway provider unconfigured",
                    contextWindow = contextWindow,
                    supportsStreaming = false,
                    supportsVision = "VISION" in capSet,
                    supportsToolCalling = "TOOLS" in capSet,
                    supportsImageGen = "IMAGE_GEN" in capSet,
                    supportsAudio = "AUDIO" in capSet,
                    modelVersion = "gateway",
                    healthStatus = if (available) ModelHealthStatus.OPERATIONAL else ModelHealthStatus.UNCONFIGURED,
                    lastCheckedMs = now,
                    lastError = obj.optString("failureReason").takeIf { it.isNotBlank() },
                    verificationStatus = when (statusStr) {
                        "LIVE_VERIFIED" -> ModelVerificationStatus.VERIFIED_LIVE
                        "RETIRED" -> ModelVerificationStatus.RETIRED
                        "UNAVAILABLE" -> ModelVerificationStatus.UNAVAILABLE
                        else -> ModelVerificationStatus.CONFIGURED
                    }
                )
            )
            merged++
        }

        if (targetList == null && merged > 0) {
            _entries.value = workingList
        }
        merged
    }
}
