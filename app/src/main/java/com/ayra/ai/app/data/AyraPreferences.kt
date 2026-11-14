package com.ayra.ai.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.EncryptedPreferences
import com.ayra.ai.app.security.SecureKeyStore
import org.json.JSONArray

/**
 * Manages Ayra AI local preferences, custom API credentials, runtime user overrides,
 * and Zero-Trust Security Center controls.
 *
 * Security Architecture:
 * - API keys are stored exclusively in [SecureKeyStore] / [EncryptedPreferences] via [AyraKeyManager.ALIAS_MASTER_SECRETS].
 * - Sensitive user preferences (custom instructions, remembered facts, local server hosts, active session,
 *   and security lock/privacy controls) are encrypted via [AyraKeyManager.ALIAS_PREFS_KEK] and transparently
 *   migrated/scrubbed from legacy plaintext SharedPreferences.
 */
object AyraPreferences {
    private const val PREF_NAME = "ayra_ai_user_prefs"
    private const val ENCRYPTED_SENSITIVE_PREFS_FILE = "ayra_sensitive_user_prefs_v2"

    private const val KEY_ACTIVE_PERSONA = "active_persona_mode"
    private const val KEY_SELECTED_MODEL_TIER = "selected_model_tier"
    private const val KEY_SELECTED_PROVIDER = "selected_provider_type"
    private const val KEY_AUTO_ROUTER_ENABLED = "auto_router_enabled"
    private const val KEY_SEARCH_GROUNDING_ENABLED = "search_grounding_enabled"
    private const val KEY_THEME_MODE = "ui_theme_mode"

    // Enterprise Security Controls
    private const val KEY_BIOMETRIC_LOCK_ENABLED = "sec_biometric_lock_enabled"
    private const val KEY_AUTO_LOCK_TIMEOUT_SEC = "sec_auto_lock_timeout_sec"
    private const val KEY_SCREEN_CAPTURE_BLOCKED = "sec_screen_capture_blocked"
    private const val KEY_LOCAL_ONLY_AI_MODE = "sec_local_only_ai_mode"
    private const val KEY_PRIVACY_MODE = "sec_privacy_mode"
    private const val KEY_CLOUD_E2EE_SYNC_ENABLED = "sec_cloud_e2ee_sync_enabled"
    private const val KEY_EXTERNAL_AI_CONSENT = "sec_external_ai_consent"
    private const val KEY_FREE_POOL_CONSENT = "sec_free_pool_consent"
    private const val KEY_PROMPT_INJECTION_GUARD = "sec_prompt_injection_guard"
    private const val KEY_ALLOW_LAN_ENDPOINTS = "sec_allow_lan_endpoints"
    private const val KEY_CLIPBOARD_AUTO_CLEAR = "sec_clipboard_auto_clear"
    private const val KEY_BIOMETRIC_FOR_EXPORT = "sec_biometric_for_export"
    private const val KEY_DEVICE_ID = "sec_device_uuid"

    private val _themeModeFlow = kotlinx.coroutines.flow.MutableStateFlow("dark")
    val themeModeFlow: kotlinx.coroutines.flow.StateFlow<String> = _themeModeFlow

    private val _screenCaptureBlockedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val screenCaptureBlockedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _screenCaptureBlockedFlow

    fun getThemeMode(context: Context): String {
        val mode = getPrefs(context).getString(KEY_THEME_MODE, "dark") ?: "dark"
        if (_themeModeFlow.value != mode) {
            _themeModeFlow.value = mode
        }
        return mode
    }

    fun setThemeMode(context: Context, mode: String) {
        val clean = when (mode.lowercase()) {
            "light", "dark", "system" -> mode.lowercase()
            else -> "dark"
        }
        getPrefs(context).edit().putString(KEY_THEME_MODE, clean).apply()
        _themeModeFlow.value = clean
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getSensitivePrefs(context: Context): EncryptedPreferences {
        return EncryptedPreferences(
            context = context,
            prefsFileName = ENCRYPTED_SENSITIVE_PREFS_FILE,
            keyAlias = AyraKeyManager.ALIAS_PREFS_KEK
        )
    }

    // --- Provider Keys (Hardware Keystore Encrypted) ---

    fun getEffectiveGeminiKey(context: Context?): String {
        if (context == null) return ""
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_GEMINI).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_GEMINI)
        }
    }

    fun getEffectiveOpenRouterKey(context: Context?): String {
        if (context == null) return ""
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_OPENROUTER).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_OPENROUTER)
        }
    }

    fun setCustomGeminiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GEMINI, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_GEMINI, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun setCustomOpenRouterKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_OPENROUTER, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_OPENROUTER, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getCustomGeminiKey(context: Context): String = getEffectiveGeminiKey(context)

    fun getCustomOpenRouterKey(context: Context): String = getEffectiveOpenRouterKey(context)

    // NVIDIA NIM Multi-Model Encrypted Keys
    fun getNvidiaKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_NVIDIA)
        }
    }

    fun setNvidiaKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_NVIDIA, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getNvidiaMinimaxKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_MINIMAX).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_NVIDIA_MINIMAX)
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaMinimaxKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_MINIMAX, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_NVIDIA_MINIMAX, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getNvidiaGlmKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_GLM).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_NVIDIA_GLM)
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaGlmKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_GLM, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_NVIDIA_GLM, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getNvidiaMuseKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_MUSE).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_NVIDIA_MUSE)
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaMuseKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_MUSE, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_NVIDIA_MUSE, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getNvidiaGemmaKey(context: Context): String {
        return SecureKeyStore.getSecret(context, SecureKeyStore.KEY_NVIDIA_GEMMA).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_NVIDIA_GEMMA)
        }.ifBlank { getNvidiaKey(context) }
    }

    fun setNvidiaGemmaKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_NVIDIA_GEMMA, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_NVIDIA_GEMMA, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    // Additional Major Providers
    fun getOpenAiKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_OPENAI)

    fun setOpenAiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_OPENAI, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getAnthropicKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_ANTHROPIC)

    fun setAnthropicKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_ANTHROPIC, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getDeepSeekKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_DEEPSEEK)

    fun setDeepSeekKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_DEEPSEEK, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getXAiKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_XAI)

    fun setXAiKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_XAI, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getMistralKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_MISTRAL)

    fun setMistralKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_MISTRAL, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getPerplexityKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_PERPLEXITY)

    fun setPerplexityKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_PERPLEXITY, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getCustomEndpointUrl(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_URL)

    fun setCustomEndpointUrl(context: Context, url: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_CUSTOM_ENDPOINT_URL, url)
        if (url.isNotBlank()) setExternalAiConsent(context, true)
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
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_GROQ)
        }

    fun setGroqKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GROQ, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_GROQ, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getHuggingFaceKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_HUGGINGFACE).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_HUGGINGFACE)
        }

    fun setHuggingFaceKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_HUGGINGFACE, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_HUGGINGFACE, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getGitHubModelsKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_GITHUB_MODELS).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_GITHUB_MODELS)
        }

    fun setGitHubModelsKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GITHUB_MODELS, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_GITHUB_MODELS, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getCerebrasKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_CEREBRAS).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_CEREBRAS)
        }

    fun setCerebrasKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_CEREBRAS, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_CEREBRAS, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getSambaNovaKey(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_SAMBANOVA).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_SAMBANOVA)
        }

    fun setSambaNovaKey(context: Context, key: String) {
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_SAMBANOVA, key)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_SAMBANOVA, key)
        if (key.isNotBlank()) setExternalAiConsent(context, true)
    }

    fun getComfyUiUrl(context: Context): String =
        SecureKeyStore.getSecret(context, SecureKeyStore.KEY_COMFYUI_URL).ifBlank {
            EncryptedPreferences(context).getString(EncryptedPreferences.KEY_COMFYUI_URL)
        }

    fun setComfyUiUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        SecureKeyStore.putSecret(context, SecureKeyStore.KEY_COMFYUI_URL, clean)
        EncryptedPreferences(context).putString(EncryptedPreferences.KEY_COMFYUI_URL, clean)
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
            getCerebrasKey(context).isNotBlank() ||
            getSambaNovaKey(context).isNotBlank() ||
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

    /**
     * Anonymous public fallback endpoints (Pollinations) now default to OFF (`false`)
     * to prevent unintended third-party prompt leakage without explicit user opt-in.
     */
    fun isFreeAiPoolEnabled(context: Context): Boolean {
        return getSensitivePrefs(context).getBoolean(KEY_FREE_AI_POOL_ENABLED, false)
    }

    fun setFreeAiPoolEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_FREE_POOL_CONSENT, enabled)
        getSensitivePrefs(context).putBoolean(KEY_FREE_AI_POOL_ENABLED, enabled)
        getPrefs(context).edit().remove(KEY_FREE_AI_POOL_ENABLED).apply()
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

    // --- Encrypted AI Memory & Personalization Settings ---
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
        val enc = getSensitivePrefs(context)
        val encryptedVal = enc.getString(KEY_USER_CUSTOM_INSTRUCTIONS, "")
        if (encryptedVal.isNotEmpty()) return encryptedVal

        // Auto-migrate & scrub legacy plaintext custom instructions
        val legacyPrefs = getPrefs(context)
        val legacyVal = legacyPrefs.getString(KEY_USER_CUSTOM_INSTRUCTIONS, "") ?: ""
        if (legacyVal.isNotEmpty()) {
            enc.putString(KEY_USER_CUSTOM_INSTRUCTIONS, legacyVal)
            legacyPrefs.edit().remove(KEY_USER_CUSTOM_INSTRUCTIONS).apply()
        }
        return legacyVal
    }

    fun setUserCustomInstructions(context: Context, instructions: String) {
        getSensitivePrefs(context).putString(KEY_USER_CUSTOM_INSTRUCTIONS, instructions)
        getPrefs(context).edit().remove(KEY_USER_CUSTOM_INSTRUCTIONS).apply()
    }

    fun getRememberedFacts(context: Context): Set<String> {
        val enc = getSensitivePrefs(context)
        val jsonStr = enc.getString(KEY_REMEMBERED_FACTS, "")
        if (jsonStr.isNotEmpty()) {
            return runCatching {
                val arr = JSONArray(jsonStr)
                buildSet {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) add(s)
                    }
                }
            }.getOrDefault(emptySet())
        }

        // Auto-migrate & scrub legacy plaintext string set
        val legacyPrefs = getPrefs(context)
        val legacySet = legacyPrefs.getStringSet(KEY_REMEMBERED_FACTS, emptySet()) ?: emptySet()
        if (legacySet.isNotEmpty()) {
            saveRememberedFactsEncrypted(context, legacySet)
            legacyPrefs.edit().remove(KEY_REMEMBERED_FACTS).apply()
        }
        return legacySet
    }

    private fun saveRememberedFactsEncrypted(context: Context, facts: Set<String>) {
        if (facts.isEmpty()) {
            getSensitivePrefs(context).remove(KEY_REMEMBERED_FACTS)
            return
        }
        val arr = JSONArray()
        facts.forEach { arr.put(it) }
        getSensitivePrefs(context).putString(KEY_REMEMBERED_FACTS, arr.toString())
    }

    fun addRememberedFact(context: Context, fact: String) {
        val current = getRememberedFacts(context).toMutableSet()
        current.add(fact)
        saveRememberedFactsEncrypted(context, current)
        getPrefs(context).edit().remove(KEY_REMEMBERED_FACTS).apply()
    }

    fun removeRememberedFact(context: Context, fact: String) {
        val current = getRememberedFacts(context).toMutableSet()
        current.remove(fact)
        saveRememberedFactsEncrypted(context, current)
        getPrefs(context).edit().remove(KEY_REMEMBERED_FACTS).apply()
    }

    fun clearAllMemory(context: Context) {
        getSensitivePrefs(context).remove(KEY_REMEMBERED_FACTS)
        getPrefs(context).edit().remove(KEY_REMEMBERED_FACTS).apply()
    }

    // --- Local Ollama / LM Studio Host Configuration (Encrypted) ---
    private const val KEY_OLLAMA_HOST = "local_ollama_host"

    fun getOllamaHost(context: Context): String {
        val enc = getSensitivePrefs(context)
        val value = enc.getString(KEY_OLLAMA_HOST, "")
        if (value.isNotEmpty()) return value

        val legacyPrefs = getPrefs(context)
        val legacy = legacyPrefs.getString(KEY_OLLAMA_HOST, null)
        if (!legacy.isNullOrBlank()) {
            enc.putString(KEY_OLLAMA_HOST, legacy)
            legacyPrefs.edit().remove(KEY_OLLAMA_HOST).apply()
            return legacy
        }
        return "http://10.0.2.2:11434"
    }

    fun setOllamaHost(context: Context, host: String) {
        val clean = if (host.endsWith("/")) host.dropLast(1) else host
        getSensitivePrefs(context).putString(KEY_OLLAMA_HOST, clean)
        getPrefs(context).edit().remove(KEY_OLLAMA_HOST).apply()
    }

    fun hasExplicitOllamaHost(context: Context): Boolean {
        return getSensitivePrefs(context).getString(KEY_OLLAMA_HOST, "").isNotBlank()
    }

    // --- Active Room Chat Session Persistence (Encrypted) ---
    private const val KEY_LAST_ACTIVE_SESSION_ID = "last_active_chat_session_id"

    fun getLastActiveSessionId(context: Context): Long {
        val enc = getSensitivePrefs(context)
        val value = enc.getLong(KEY_LAST_ACTIVE_SESSION_ID, -1L)
        if (value > 0L) return value

        val legacyPrefs = getPrefs(context)
        val legacy = legacyPrefs.getLong(KEY_LAST_ACTIVE_SESSION_ID, 1L)
        if (legacyPrefs.contains(KEY_LAST_ACTIVE_SESSION_ID)) {
            enc.putLong(KEY_LAST_ACTIVE_SESSION_ID, legacy)
            legacyPrefs.edit().remove(KEY_LAST_ACTIVE_SESSION_ID).apply()
        }
        return legacy
    }

    fun setLastActiveSessionId(context: Context, sessionId: Long) {
        getSensitivePrefs(context).putLong(KEY_LAST_ACTIVE_SESSION_ID, sessionId)
        getPrefs(context).edit().remove(KEY_LAST_ACTIVE_SESSION_ID).apply()
    }

    // --- Enterprise Zero-Trust Security Controls (Encrypted via ALIAS_PREFS_KEK) ---

    fun isBiometricLockEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_BIOMETRIC_LOCK_ENABLED, false)

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_BIOMETRIC_LOCK_ENABLED, enabled)
    }

    fun getAutoLockTimeoutSeconds(context: Context): Long =
        getSensitivePrefs(context).getLong(KEY_AUTO_LOCK_TIMEOUT_SEC, 60L)

    fun setAutoLockTimeoutSeconds(context: Context, seconds: Long) {
        getSensitivePrefs(context).putLong(KEY_AUTO_LOCK_TIMEOUT_SEC, seconds)
    }

    fun isScreenCaptureBlocked(context: Context): Boolean {
        val blocked = getSensitivePrefs(context).getBoolean(KEY_SCREEN_CAPTURE_BLOCKED, false)
        if (_screenCaptureBlockedFlow.value != blocked) {
            _screenCaptureBlockedFlow.value = blocked
        }
        return blocked
    }

    fun setScreenCaptureBlocked(context: Context, blocked: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_SCREEN_CAPTURE_BLOCKED, blocked)
        _screenCaptureBlockedFlow.value = blocked
    }

    fun isLocalOnlyAiModeEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_LOCAL_ONLY_AI_MODE, false)

    fun setLocalOnlyAiModeEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_LOCAL_ONLY_AI_MODE, enabled)
    }

    fun isPrivacyModeEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_PRIVACY_MODE, false)

    fun setPrivacyModeEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_PRIVACY_MODE, enabled)
    }

    fun isCloudE2eeSyncEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_CLOUD_E2EE_SYNC_ENABLED, true)

    fun setCloudE2eeSyncEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_CLOUD_E2EE_SYNC_ENABLED, enabled)
    }

    fun hasExternalAiConsent(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_EXTERNAL_AI_CONSENT, true)

    fun setExternalAiConsent(context: Context, consented: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_EXTERNAL_AI_CONSENT, consented)
    }

    fun hasFreePoolConsent(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_FREE_POOL_CONSENT, false)

    fun isPromptInjectionGuardEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_PROMPT_INJECTION_GUARD, true)

    fun setPromptInjectionGuardEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_PROMPT_INJECTION_GUARD, enabled)
    }

    fun isAllowLanEndpointsEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_ALLOW_LAN_ENDPOINTS, true)

    fun setAllowLanEndpointsEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_ALLOW_LAN_ENDPOINTS, enabled)
    }

    fun isClipboardAutoClearEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_CLIPBOARD_AUTO_CLEAR, true)

    fun setClipboardAutoClearEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_CLIPBOARD_AUTO_CLEAR, enabled)
    }

    fun isBiometricRequiredForExport(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_BIOMETRIC_FOR_EXPORT, true)

    fun setBiometricRequiredForExport(context: Context, required: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_BIOMETRIC_FOR_EXPORT, required)
    }

    fun getOrCreateDeviceId(context: Context): String {
        val enc = getSensitivePrefs(context)
        val existing = enc.getString(KEY_DEVICE_ID, "")
        if (existing.isNotBlank()) return existing
        val generated = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        enc.putString(KEY_DEVICE_ID, generated)
        return generated
    }

    fun clearAllPreferences(context: Context) {
        getSensitivePrefs(context).clear()
        getPrefs(context).edit().clear().commit()
        _themeModeFlow.value = "dark"
        _screenCaptureBlockedFlow.value = false
    }
}
