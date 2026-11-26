package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.AyraApplication
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import com.ayra.ai.app.security.SecureAiGatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object ExternalAiKeyAndDiscovery {

    fun resolveChannelKey(
        channel: AiModelChannel,
        ctx: Context?,
        channelKeyOverrides: Map<String, String>
    ): String {
        channelKeyOverrides[channel.id]?.takeIf { it.isNotBlank() }?.let { return it }
        if (channel.provider != AiProviderType.POLLINATIONS_FREE) {
            channelKeyOverrides[channel.provider.id]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return channel.keyResolver(ctx)
    }

    fun getKeyForProviderAndModel(
        provider: AiProviderType,
        modelId: String,
        context: Context?,
        channelKeyOverrides: Map<String, String>
    ): String {
        if (provider == AiProviderType.NVIDIA_NIM) {
            val m = modelId.lowercase()
            val channelId = when {
                m.contains("llama") -> "nvidia_llama_3_3_70b"
                m.contains("minimax") -> "nvidia_minimax_m3_1"
                m.contains("glm") -> "nvidia_glm_5_3"
                m.contains("muse") -> "nvidia_muse_30b"
                m.contains("gemma") -> "nvidia_gemma_4_31b"
                else -> ""
            }
            if (channelId.isNotEmpty()) channelKeyOverrides[channelId]?.takeIf { it.isNotBlank() }?.let { return it }
            val ctx = context
            if (ctx != null) {
                AyraApplication.ensureActiveAiKeysSeeded(ctx)
                val resolved = when {
                    m.contains("llama") -> AyraPreferences.getNvidiaKey(ctx)
                    m.contains("minimax") -> AyraPreferences.getNvidiaMinimaxKey(ctx)
                    m.contains("glm") -> AyraPreferences.getNvidiaGlmKey(ctx)
                    m.contains("muse") -> AyraPreferences.getNvidiaMuseKey(ctx)
                    m.contains("gemma") -> AyraPreferences.getNvidiaGemmaKey(ctx)
                    else -> AyraPreferences.getNvidiaKey(ctx)
                }
                if (resolved.isNotBlank()) return resolved
            }
        }
        return getProviderKey(provider, context, channelKeyOverrides)
    }

    fun getProviderKey(
        provider: AiProviderType,
        context: Context?,
        channelKeyOverrides: Map<String, String>
    ): String {
        channelKeyOverrides[provider.id]?.let { if (it.isNotBlank()) return it }
        if (provider == AiProviderType.NVIDIA_NIM) {
            val overriddenNim = channelKeyOverrides["nvidia_llama_3_3_70b"]
                ?: channelKeyOverrides["nvidia_minimax_m3_1"]
                ?: channelKeyOverrides["nvidia_muse_30b"]
                ?: channelKeyOverrides["nvidia_glm_5_3"]
                ?: channelKeyOverrides["nvidia_gemma_4_31b"]
            if (!overriddenNim.isNullOrBlank()) return overriddenNim
        }
        val ctx = context ?: return ""
        AyraApplication.ensureActiveAiKeysSeeded(ctx)
        return when (provider) {
            AiProviderType.GEMINI -> AyraPreferences.getEffectiveGeminiKey(ctx)
            AiProviderType.NVIDIA_NIM -> AyraPreferences.getNvidiaKey(ctx)
                .ifBlank { AyraPreferences.getNvidiaMinimaxKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaMuseKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGlmKey(ctx) }
                .ifBlank { AyraPreferences.getNvidiaGemmaKey(ctx) }
            AiProviderType.GROQ -> AyraPreferences.getGroqKey(ctx)
            AiProviderType.CEREBRAS -> AyraPreferences.getCerebrasKey(ctx)
            AiProviderType.SAMBANOVA -> AyraPreferences.getSambaNovaKey(ctx)
            AiProviderType.HUGGINGFACE -> AyraPreferences.getHuggingFaceKey(ctx)
            AiProviderType.GITHUB_MODELS -> AyraPreferences.getGitHubModelsKey(ctx)
            AiProviderType.OPENAI -> AyraPreferences.getOpenAiKey(ctx)
            AiProviderType.ANTHROPIC -> AyraPreferences.getAnthropicKey(ctx)
            AiProviderType.DEEPSEEK -> AyraPreferences.getDeepSeekKey(ctx)
            AiProviderType.XAI -> AyraPreferences.getXAiKey(ctx)
            AiProviderType.MISTRAL -> AyraPreferences.getMistralKey(ctx)
            AiProviderType.PERPLEXITY -> AyraPreferences.getPerplexityKey(ctx)
            AiProviderType.OPENROUTER -> AyraPreferences.getEffectiveOpenRouterKey(ctx)
            AiProviderType.LOCAL_OLLAMA -> {
                val url = AyraPreferences.getCustomEndpointUrl(ctx)
                val hasOllama = AyraPreferences.hasExplicitOllamaHost(ctx)
                if (url.isNotBlank() || hasOllama) {
                    AyraPreferences.getCustomEndpointKey(ctx).ifBlank {
                        url.ifBlank { AyraPreferences.getOllamaHost(ctx) }
                    }
                } else ""
            }
            AiProviderType.POLLINATIONS_FREE -> if (AyraPreferences.isFreeAiPoolEnabled(ctx)) "free" else ""
            AiProviderType.OMNI_ROUTE -> AyraPreferences.getEffectiveGeminiKey(ctx)
        }
    }

    fun isProviderConfigured(
        provider: AiProviderType,
        context: Context?,
        isTestHarnessMode: Boolean,
        baseUrlOverrides: Map<AiProviderType, String>,
        channelKeyOverrides: Map<String, String>
    ): Boolean {
        if (!isTestHarnessMode) {
            if (!SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) return false
        }
        if (provider == AiProviderType.GITHUB_MODELS && !baseUrlOverrides.containsKey(AiProviderType.GITHUB_MODELS)) {
            return false
        }
        if (provider == AiProviderType.OMNI_ROUTE) return !(context != null && AyraPreferences.isLocalOnlyAiModeEnabled(context))
        if (provider == AiProviderType.LOCAL_OLLAMA) {
            return (channelKeyOverrides[AiProviderType.LOCAL_OLLAMA.id]?.isNotBlank() == true) ||
                (context?.let {
                    AyraPreferences.getCustomEndpointUrl(it).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(it)
                } ?: false)
        }
        if (provider == AiProviderType.POLLINATIONS_FREE) {
            if (channelKeyOverrides.isNotEmpty()) {
                return channelKeyOverrides.any { (k, v) -> k.startsWith("pollinations_free") && v.isNotBlank() }
            }
            return context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
        }
        return getProviderKey(provider, context, channelKeyOverrides).isNotBlank()
    }

    fun getSavedKeyProvidersIgnoringConsent(
        context: Context?,
        channelKeyOverrides: Map<String, String>
    ): List<AiProviderType> =
        listOf(
            AiProviderType.GEMINI, AiProviderType.GROQ, AiProviderType.CEREBRAS,
            AiProviderType.SAMBANOVA, AiProviderType.NVIDIA_NIM, AiProviderType.HUGGINGFACE,
            AiProviderType.OPENAI, AiProviderType.ANTHROPIC, AiProviderType.DEEPSEEK,
            AiProviderType.PERPLEXITY, AiProviderType.XAI, AiProviderType.MISTRAL,
            AiProviderType.OPENROUTER, AiProviderType.LOCAL_OLLAMA, AiProviderType.POLLINATIONS_FREE
        ).filter { provider ->
            when (provider) {
                AiProviderType.LOCAL_OLLAMA -> (channelKeyOverrides[AiProviderType.LOCAL_OLLAMA.id]?.isNotBlank() == true) ||
                    (context?.let {
                        AyraPreferences.getCustomEndpointUrl(it).isNotBlank() || AyraPreferences.hasExplicitOllamaHost(it)
                    } ?: false)
                AiProviderType.POLLINATIONS_FREE -> context?.let { AyraPreferences.isFreeAiPoolEnabled(it) } ?: false
                else -> getProviderKey(provider, context, channelKeyOverrides).isNotBlank()
            }
        }

    suspend fun discoverDynamicModels(
        provider: AiProviderType,
        context: Context?,
        httpClient: OkHttpClient,
        baseUrlOverrides: Map<AiProviderType, String>,
        channelKeyOverrides: Map<String, String>,
        isTestHarnessMode: Boolean,
        dynamicModelCache: ConcurrentHashMap<AiProviderType, List<AiModelInfo>>
    ): List<AiModelInfo> = withContext(Dispatchers.IO) {
        dynamicModelCache[provider]?.let { if (it.isNotEmpty()) return@withContext it }
        if (!isTestHarnessMode &&
            !SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed
        ) {
            return@withContext ExternalAiCatalog.curatedModels.filter { it.provider == provider }
        }
        val key = getProviderKey(provider, context, channelKeyOverrides)
        val discovered = mutableListOf<AiModelInfo>()
        try {
            when (provider) {
                AiProviderType.GEMINI -> ProviderGeminiTransport.discoverModels(httpClient, key, discovered)
                AiProviderType.ANTHROPIC -> ProviderAnthropicAndFreeTransport.discoverAnthropicModels(httpClient, key, discovered)
                AiProviderType.NVIDIA_NIM, AiProviderType.GROQ, AiProviderType.CEREBRAS, AiProviderType.SAMBANOVA,
                AiProviderType.HUGGINGFACE, AiProviderType.GITHUB_MODELS, AiProviderType.OPENAI, AiProviderType.DEEPSEEK,
                AiProviderType.XAI, AiProviderType.MISTRAL, AiProviderType.PERPLEXITY, AiProviderType.OPENROUTER,
                AiProviderType.LOCAL_OLLAMA ->
                    ProviderOpenAiTransport.discoverModels(httpClient, context, provider, baseUrlOverrides, key, discovered)
                else -> {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ExternalAiProviders", "Dynamic model discovery failed for ${provider.id}: ${e.message}")
        }

        if (discovered.isNotEmpty()) {
            dynamicModelCache[provider] = discovered
            ModelCatalog.registerLiveDiscoveredModels(provider, discovered)
            discovered
        } else {
            ExternalAiCatalog.curatedModels.filter { it.provider == provider }
        }
    }

    suspend fun transcribeAudioOpenAiCompatible(
        audioFile: File,
        promptHint: String,
        context: Context?,
        httpClient: OkHttpClient,
        customHttpClient: OkHttpClient?,
        baseUrlOverrides: Map<AiProviderType, String>,
        channelKeyOverrides: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() <= 0L) return@withContext ""
        val candidates = listOf(
            Triple(AiProviderType.GROQ, ModelCatalog.GROQ_WHISPER_MODEL, getProviderKey(AiProviderType.GROQ, context, channelKeyOverrides)),
            Triple(AiProviderType.SAMBANOVA, ModelCatalog.SAMBANOVA_WHISPER_MODEL, getProviderKey(AiProviderType.SAMBANOVA, context, channelKeyOverrides)),
            Triple(AiProviderType.OPENAI, "whisper-1", getProviderKey(AiProviderType.OPENAI, context, channelKeyOverrides))
        ).filter { it.third.isNotBlank() }

        for ((provider, model, key) in candidates) {
            if (customHttpClient == null && !SecureAiGatewayClient.evaluateProviderAccess(context, provider).allowed) continue
            val start = System.currentTimeMillis()
            try {
                val baseUrl = ProviderOpenAiTransport.getBaseUrlForProvider(provider, context, baseUrlOverrides)
                val endpoint = "${baseUrl}audio/transcriptions"
                val mime = if (audioFile.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", model)
                if (promptHint.isNotBlank()) bodyBuilder.addFormDataPart("prompt", promptHint)
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $key")
                    .post(bodyBuilder.build())
                    .build()

                val response = if (customHttpClient == null && !baseUrlOverrides.containsKey(provider) && endpoint.startsWith("https://", ignoreCase = true)) {
                    NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                        client = httpClient,
                        initialRequest = req,
                        urlValidator = { NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(it) }
                    )
                } else {
                    httpClient.newCall(req).execute()
                }
                response.use { resp ->
                    val raw = resp.readBounded()
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    if (resp.isSuccessful && raw.isNotBlank()) {
                        val text = runCatching { JSONObject(raw).optString("text", "") }.getOrDefault("")
                        if (text.isNotBlank()) {
                            AiUsageTracker.recordCall(context, provider, true, latency)
                            return@withContext text.trim()
                        }
                    }
                    AiUsageTracker.recordCall(context, provider, false, latency)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ExternalAiProviders", "Whisper transcription failed for ${provider.id}: ${e.message}")
                AiUsageTracker.recordCall(context, provider, false, System.currentTimeMillis() - start)
            }
        }
        ""
    }
}
