package com.ayra.ai.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ayra.ai.app.security.SecureKeyStore

/**
 * Manages Ayra AI local preferences, custom API credentials, and runtime user overrides.
 * All API keys are securely delegated to [SecureKeyStore] using hardware-backed AES-256-GCM.
 */
object AyraPreferences {
    private const val PREF_NAME = "ayra_ai_user_prefs"
    private const val KEY_ACTIVE_PERSONA = "active_persona_mode"
    private const val KEY_SELECTED_MODEL_TIER = "selected_model_tier"
    private const val KEY_SELECTED_PROVIDER = "selected_provider_type"
    private const val KEY_AUTO_ROUTER_ENABLED = "auto_router_enabled"
    private const val KEY_SEARCH_GROUNDING_ENABLED = "search_grounding_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // --- Provider Keys (Hardware Keystore Encrypted) ---

    fun getEffectiveGeminiKey(context: Context?): String {
        if (context == null) return ""
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_GEMINI).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_GEMINI
            )
        }
    }

    fun getEffectiveOpenRouterKey(context: Context?): String {
        if (context == null) return ""
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_OPENROUTER).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_OPENROUTER
            )
        }
    }

    fun setCustomGeminiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GEMINI, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_GEMINI, key
        )
    }

    fun setCustomOpenRouterKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_OPENROUTER, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_OPENROUTER, key
        )
    }

    fun getCustomGeminiKey(context: Context): String = getEffectiveGeminiKey(context)

    fun getCustomOpenRouterKey(context: Context): String = getEffectiveOpenRouterKey(context)

    // NVIDIA NIM Multi-Model Encrypted Keys
    fun getNvidiaKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA
            )
        }
    }

    fun setNvidiaKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA, key
        )
    }

    fun getNvidiaMinimaxKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_MINIMAX).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_MINIMAX
            )
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaMinimaxKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_MINIMAX, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_MINIMAX, key
        )
    }

    fun getNvidiaGlmKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_GLM).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_GLM
            )
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaGlmKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_GLM, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_GLM, key
        )
    }

    fun getNvidiaMuseKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_MUSE).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_MUSE
            )
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaMuseKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_MUSE, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_MUSE, key
        )
    }

    fun getNvidiaGemmaKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_GEMMA).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_GEMMA
            )
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaGemmaKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_GEMMA, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_NVIDIA_GEMMA, key
        )
    }

    // Additional Major Providers
    fun getOpenAiKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_OPENAI)

    fun setOpenAiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_OPENAI, key)
    }

    fun getAnthropicKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_ANTHROPIC)

    fun setAnthropicKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_ANTHROPIC, key)
    }

    fun getDeepSeekKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_DEEPSEEK)

    fun setDeepSeekKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_DEEPSEEK, key)
    }

    fun getXAiKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_XAI)

    fun setXAiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_XAI, key)
    }

    fun getMistralKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_MISTRAL)

    fun setMistralKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_MISTRAL, key)
    }

    fun getPerplexityKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_PERPLEXITY)

    fun setPerplexityKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_PERPLEXITY, key)
    }

    fun getCustomEndpointUrl(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_URL)

    fun setCustomEndpointUrl(context: Context, url: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_URL, url)
    }

    fun getCustomEndpointKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_KEY)

    fun setCustomEndpointKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_KEY, key)
    }

    fun getTavilySearchKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_TAVILY_SEARCH)

    fun setTavilySearchKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_TAVILY_SEARCH, key)
    }

    fun getElevenLabsKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_ELEVENLABS)

    fun setElevenLabsKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_ELEVENLABS, key)
    }

    fun getGroqKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_GROQ).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_GROQ
            )
        }

    fun setGroqKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GROQ, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_GROQ, key
        )
    }

    fun getHuggingFaceKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_HUGGINGFACE).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_HUGGINGFACE
            )
        }

    fun setHuggingFaceKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_HUGGINGFACE, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_HUGGINGFACE, key
        )
    }

    fun getGitHubModelsKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_GITHUB_MODELS).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_GITHUB_MODELS
            )
        }

    fun setGitHubModelsKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GITHUB_MODELS, key)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_GITHUB_MODELS, key
        )
    }

    fun getComfyUiUrl(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_COMFYUI_URL).ifBlank {
            com.ayra.ai.app.security.EncryptedPreferences(context).getString(
                com.ayra.ai.app.security.EncryptedPreferences.KEY_COMFYUI_URL
            )
        }

    fun setComfyUiUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_COMFYUI_URL, clean)
        com.ayra.ai.app.security.EncryptedPreferences(context).putString(
            com.ayra.ai.app.security.EncryptedPreferences.KEY_COMFYUI_URL, clean
        )
    }

    fun hasAnyCustomKeyConfigured(context: Context): Boolean {
        return getCustomGeminiKey(context).isNotBlank() ||
                getNvidiaKey(context).isNotBlank() ||
                getNvidiaMinimaxKey(context).isNotBlank() ||
                getNvidiaGlmKey(context).isNotBlank() ||
                getNvidiaMuseKey(context).isNotBlank() ||
                getNvidiaGemmaKey(context).isNotBlank() ||
                getCustomOpenRouterKey(context).isNotBlank() ||
                getOpenAiKey(context).isNotBlank() ||
                getAnthropicKey(context).isNotBlank() ||
                getDeepSeekKey(context).isNotBlank() ||
                getXAiKey(context).isNotBlank() ||
                getMistralKey(context).isNotBlank() ||
                getPerplexityKey(context).isNotBlank() ||
                getGroqKey(context).isNotBlank() ||
                getHuggingFaceKey(context).isNotBlank() ||
                getGitHubModelsKey(context).isNotBlank() ||
                getTavilySearchKey(context).isNotBlank() ||
                getElevenLabsKey(context).isNotBlank() ||
                getCustomEndpointUrl(context).isNotBlank()
    }

    fun hasCustomKeyConfigured(context: Context): Boolean = hasAnyCustomKeyConfigured(context)

    // --- Persona & Routing Preferences ---

    fun savePersona(context: Context, persona: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_PERSONA, persona).apply()
    }

    fun getSavedPersona(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_PERSONA, "AI_ASSISTANT") ?: "AI_ASSISTANT"
    }

    fun saveModelTier(context: Context, tier: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_MODEL_TIER, tier).apply()
    }

    fun getSavedModelTier(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_MODEL_TIER, "auto") ?: "auto"
    }

    fun saveSelectedProvider(context: Context, providerId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_PROVIDER, providerId).apply()
    }

    fun getSelectedProvider(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_PROVIDER, "AUTO") ?: "AUTO"
    }

    fun isAutoRouterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_ROUTER_ENABLED, true)
    }

    fun setAutoRouterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_ROUTER_ENABLED, enabled).apply()
    }

    fun isSearchGroundingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SEARCH_GROUNDING_ENABLED, true)
    }

    fun setSearchGroundingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SEARCH_GROUNDING_ENABLED, enabled).apply()
    }

    // --- Intelligent Routing Modes ---
    private const val KEY_ROUTING_MODE = "intelligent_routing_mode"
    private const val KEY_FREE_AI_POOL_ENABLED = "free_ai_pool_enabled"
    private const val KEY_LOCK_SCREEN_WAKE = "lock_screen_wake_enabled"
    private const val KEY_PREFERRED_LANGUAGE = "preferred_language_code"

    fun getRoutingMode(context: Context): String {
        return getPrefs(context).getString(KEY_ROUTING_MODE, "auto") ?: "auto"
    }

    fun setRoutingMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_ROUTING_MODE, mode).apply()
    }

    fun isFreeAiPoolEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FREE_AI_POOL_ENABLED, true)
    }

    fun setFreeAiPoolEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FREE_AI_POOL_ENABLED, enabled).apply()
    }

    fun isLockScreenWakeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOCK_SCREEN_WAKE, true)
    }

    fun setLockScreenWakeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOCK_SCREEN_WAKE, enabled).apply()
    }

    fun getPreferredLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_PREFERRED_LANGUAGE, "Auto") ?: "Auto"
    }

    fun setPreferredLanguage(context: Context, langCode: String) {
        getPrefs(context).edit().putString(KEY_PREFERRED_LANGUAGE, langCode).apply()
    }

    // --- AI Memory & Personalization Settings ---
    private const val KEY_MEMORY_ENABLED = "ai_memory_enabled"
    private const val KEY_USER_CUSTOM_INSTRUCTIONS = "user_custom_instructions"
    private const val KEY_REMEMBERED_FACTS = "user_remembered_facts"

    fun isMemoryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MEMORY_ENABLED, true)
    }

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
    }

    fun getUserCustomInstructions(context: Context): String {
        return getPrefs(context).getString(KEY_USER_CUSTOM_INSTRUCTIONS, "") ?: ""
    }

    fun setUserCustomInstructions(context: Context, instructions: String) {
        getPrefs(context).edit().putString(KEY_USER_CUSTOM_INSTRUCTIONS, instructions).apply()
    }

    fun getRememberedFacts(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_REMEMBERED_FACTS, emptySet()) ?: emptySet()
    }

    fun addRememberedFact(context: Context, fact: String) {
        val current = getRememberedFacts(context).toMutableSet()
        current.add(fact)
        getPrefs(context).edit().putStringSet(KEY_REMEMBERED_FACTS, current).apply()
    }

    fun removeRememberedFact(context: Context, fact: String) {
        val current = getRememberedFacts(context).toMutableSet()
        current.remove(fact)
        getPrefs(context).edit().putStringSet(KEY_REMEMBERED_FACTS, current).apply()
    }

    fun clearAllMemory(context: Context) {
        getPrefs(context).edit().remove(KEY_REMEMBERED_FACTS).apply()
    }

    // --- Local Ollama / LM Studio Host Configuration ---
    private const val KEY_OLLAMA_HOST = "local_ollama_host"

    fun getOllamaHost(context: Context): String {
        return getPrefs(context).getString(KEY_OLLAMA_HOST, "http://10.0.2.2:11434") ?: "http://10.0.2.2:11434"
    }

    fun setOllamaHost(context: Context, host: String) {
        val clean = if (host.endsWith("/")) host.dropLast(1) else host
        getPrefs(context).edit().putString(KEY_OLLAMA_HOST, clean).apply()
    }

    // --- Active Room Chat Session Persistence ---
    private const val KEY_LAST_ACTIVE_SESSION_ID = "last_active_chat_session_id"

    fun getLastActiveSessionId(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_ACTIVE_SESSION_ID, 1L)
    }

    fun setLastActiveSessionId(context: Context, sessionId: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_ACTIVE_SESSION_ID, sessionId).apply()
    }
}
