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
    private const val KEY_CLOUD_SYNC_ENABLED = "sec_cloud_sync_enabled"
    private const val KEY_CLOUD_E2EE_SYNC_ENABLED = "sec_cloud_e2ee_sync_enabled"
    private const val KEY_EXTERNAL_AI_CONSENT = "sec_external_ai_consent"
    private const val KEY_FREE_POOL_CONSENT = "sec_free_pool_consent"
    private const val KEY_PROMPT_INJECTION_GUARD = "sec_prompt_injection_guard"
    private const val KEY_ALLOW_LAN_ENDPOINTS = "sec_allow_lan_endpoints"
    private const val KEY_CLIPBOARD_AUTO_CLEAR = "sec_clipboard_auto_clear"
    private const val KEY_BIOMETRIC_FOR_EXPORT = "sec_biometric_for_export"
    private const val KEY_DEVICE_ID = "sec_device_uuid"
    private const val KEY_REMOTE_MODEL_CATALOG_JSON = "remote_model_catalog_json"
    private const val KEY_COMPANION_AGE_VERIFIED = "sec_companion_age_verified_18_plus"
    private const val KEY_BACKEND_GATEWAY_URL = "sec_backend_gateway_url"
    private const val KEY_PREFER_BYOK_DIRECT = "sec_prefer_byok_direct"

    private const val KEY_ACCENT_PRESET = "ui_accent_preset"
    private const val KEY_CUSTOM_ACCENT_HEX = "ui_custom_accent_hex"
    private const val KEY_FONT_SCALE = "ui_font_scale"
    private const val KEY_BUBBLE_CORNER_DP = "ui_bubble_corner_dp"
    private const val KEY_UI_DENSITY = "ui_density_mode"
    private const val KEY_REDUCED_MOTION = "ui_reduced_motion"
    private const val KEY_THINKING_MODE = "ai_thinking_mode"
    private const val KEY_ACTIVE_PROJECT_ID = "workspace_active_project_id"
    private const val KEY_ACTIVE_ASSISTANT_ID = "workspace_active_assistant_id"

    // Response Characteristics & Structured Instructions
    private const val KEY_FORMALITY_LEVEL = "ai_char_formality"
    private const val KEY_DETAIL_LEVEL = "ai_char_detail"
    private const val KEY_TECHNICAL_DEPTH = "ai_char_tech_depth"
    private const val KEY_USE_EXAMPLES = "ai_char_use_examples"
    private const val KEY_USE_STRUCTURED = "ai_char_use_structured"
    private const val KEY_EMOJI_FREQ = "ai_char_emoji_freq"
    private const val KEY_RESPONSE_LENGTH = "ai_char_response_length"

    private const val KEY_STRUCT_ROLE = "ai_struct_role"
    private const val KEY_STRUCT_ABOUT_USER = "ai_struct_about_user"
    private const val KEY_STRUCT_HOW_RESPOND = "ai_struct_how_respond"
    private const val KEY_STRUCT_OUTPUT_FORMAT = "ai_struct_output_format"
    private const val KEY_STRUCT_INTERESTS = "ai_struct_interests"
    private const val KEY_STRUCT_RESTRICTIONS = "ai_struct_restrictions"

    private val _themeModeFlow = kotlinx.coroutines.flow.MutableStateFlow("dark")
    val themeModeFlow: kotlinx.coroutines.flow.StateFlow<String> = _themeModeFlow

    private val _accentPresetFlow = kotlinx.coroutines.flow.MutableStateFlow("cyan")
    val accentPresetFlow: kotlinx.coroutines.flow.StateFlow<String> = _accentPresetFlow

    private val _customAccentHexFlow = kotlinx.coroutines.flow.MutableStateFlow("#00F0FF")
    val customAccentHexFlow: kotlinx.coroutines.flow.StateFlow<String> = _customAccentHexFlow

    private val _fontScaleFlow = kotlinx.coroutines.flow.MutableStateFlow(1.0f)
    val fontScaleFlow: kotlinx.coroutines.flow.StateFlow<Float> = _fontScaleFlow

    private val _bubbleCornerDpFlow = kotlinx.coroutines.flow.MutableStateFlow(18)
    val bubbleCornerDpFlow: kotlinx.coroutines.flow.StateFlow<Int> = _bubbleCornerDpFlow

    private val _uiDensityFlow = kotlinx.coroutines.flow.MutableStateFlow("balanced")
    val uiDensityFlow: kotlinx.coroutines.flow.StateFlow<String> = _uiDensityFlow

    private val _reducedMotionFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val reducedMotionFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _reducedMotionFlow

    private val _thinkingModeFlow = kotlinx.coroutines.flow.MutableStateFlow("balanced")
    val thinkingModeFlow: kotlinx.coroutines.flow.StateFlow<String> = _thinkingModeFlow

    private val _activeProjectIdFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val activeProjectIdFlow: kotlinx.coroutines.flow.StateFlow<Long> = _activeProjectIdFlow

    private val _activeAssistantIdFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val activeAssistantIdFlow: kotlinx.coroutines.flow.StateFlow<Long> = _activeAssistantIdFlow

    private val _screenCaptureBlockedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val screenCaptureBlockedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _screenCaptureBlockedFlow

    fun refreshUiFlows(context: Context) {
        getThemeMode(context)
        getAccentPreset(context)
        getCustomAccentHex(context)
        getFontScale(context)
        getBubbleCornerDp(context)
        getUiDensity(context)
        isReducedMotionEnabled(context)
        getThinkingMode(context)
        getActiveProjectId(context)
        getActiveAssistantId(context)
        isScreenCaptureBlocked(context)
    }

    fun getThemeMode(context: Context): String {
        val mode = getPrefs(context).getString(KEY_THEME_MODE, "dark") ?: "dark"
        if (_themeModeFlow.value != mode) {
            _themeModeFlow.value = mode
        }
        return mode
    }

    fun setThemeMode(context: Context, mode: String) {
        val clean = when (mode.lowercase()) {
            "light", "dark", "system", "auto", "high_contrast", "custom" -> mode.lowercase()
            else -> "dark"
        }
        getPrefs(context).edit().putString(KEY_THEME_MODE, clean).apply()
        _themeModeFlow.value = clean
    }

    fun getAccentPreset(context: Context): String {
        val preset = getPrefs(context).getString(KEY_ACCENT_PRESET, "cyan") ?: "cyan"
        if (_accentPresetFlow.value != preset) _accentPresetFlow.value = preset
        return preset
    }

    fun setAccentPreset(context: Context, preset: String) {
        val clean = when (preset.lowercase()) {
            "cyan", "blue", "purple", "green", "orange", "pink", "red", "custom" -> preset.lowercase()
            else -> "cyan"
        }
        getPrefs(context).edit().putString(KEY_ACCENT_PRESET, clean).apply()
        _accentPresetFlow.value = clean
    }

    fun getCustomAccentHex(context: Context): String {
        val hex = getPrefs(context).getString(KEY_CUSTOM_ACCENT_HEX, "#00F0FF") ?: "#00F0FF"
        if (_customAccentHexFlow.value != hex) _customAccentHexFlow.value = hex
        return hex
    }

    fun setCustomAccentHex(context: Context, hex: String) {
        val clean = hex.trim().let { if (it.startsWith("#")) it else "#$it" }
        val valid = if (Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$").matches(clean)) clean else "#00F0FF"
        getPrefs(context).edit().putString(KEY_CUSTOM_ACCENT_HEX, valid).apply()
        _customAccentHexFlow.value = valid
    }

    fun getFontScale(context: Context): Float {
        val scale = getPrefs(context).getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.85f, 1.35f)
        if (_fontScaleFlow.value != scale) _fontScaleFlow.value = scale
        return scale
    }

    fun setFontScale(context: Context, scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.35f)
        getPrefs(context).edit().putFloat(KEY_FONT_SCALE, clamped).apply()
        _fontScaleFlow.value = clamped
    }

    fun getBubbleCornerDp(context: Context): Int {
        val dp = getPrefs(context).getInt(KEY_BUBBLE_CORNER_DP, 18).coerceIn(6, 28)
        if (_bubbleCornerDpFlow.value != dp) _bubbleCornerDpFlow.value = dp
        return dp
    }

    fun setBubbleCornerDp(context: Context, dp: Int) {
        val clamped = dp.coerceIn(6, 28)
        getPrefs(context).edit().putInt(KEY_BUBBLE_CORNER_DP, clamped).apply()
        _bubbleCornerDpFlow.value = clamped
    }

    fun getUiDensity(context: Context): String {
        val density = getPrefs(context).getString(KEY_UI_DENSITY, "balanced") ?: "balanced"
        if (_uiDensityFlow.value != density) _uiDensityFlow.value = density
        return density
    }

    fun setUiDensity(context: Context, density: String) {
        val clean = when (density.lowercase()) {
            "compact", "balanced", "spacious" -> density.lowercase()
            else -> "balanced"
        }
        getPrefs(context).edit().putString(KEY_UI_DENSITY, clean).apply()
        _uiDensityFlow.value = clean
    }

    fun isReducedMotionEnabled(context: Context): Boolean {
        val reduced = getPrefs(context).getBoolean(KEY_REDUCED_MOTION, false)
        if (_reducedMotionFlow.value != reduced) _reducedMotionFlow.value = reduced
        return reduced
    }

    fun setReducedMotionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
        _reducedMotionFlow.value = enabled
    }

    fun getThinkingMode(context: Context): String {
        val mode = getPrefs(context).getString(KEY_THINKING_MODE, "balanced") ?: "balanced"
        if (_thinkingModeFlow.value != mode) _thinkingModeFlow.value = mode
        return mode
    }

    fun setThinkingMode(context: Context, mode: String) {
        val clean = when (mode.lowercase()) {
            "fast", "balanced", "deep_analysis", "reasoning", "research", "coding", "creative", "custom" -> mode.lowercase()
            else -> "balanced"
        }
        getPrefs(context).edit().putString(KEY_THINKING_MODE, clean).apply()
        _thinkingModeFlow.value = clean
    }

    fun getActiveProjectId(context: Context): Long {
        val id = getPrefs(context).getLong(KEY_ACTIVE_PROJECT_ID, 0L)
        if (_activeProjectIdFlow.value != id) _activeProjectIdFlow.value = id
        return id
    }

    fun setActiveProjectId(context: Context, id: Long) {
        getPrefs(context).edit().putLong(KEY_ACTIVE_PROJECT_ID, id).apply()
        _activeProjectIdFlow.value = id
    }

    fun getActiveAssistantId(context: Context): Long {
        val id = getPrefs(context).getLong(KEY_ACTIVE_ASSISTANT_ID, 0L)
        if (_activeAssistantIdFlow.value != id) _activeAssistantIdFlow.value = id
        return id
    }

    fun setActiveAssistantId(context: Context, id: Long) {
        getPrefs(context).edit().putLong(KEY_ACTIVE_ASSISTANT_ID, id).apply()
        _activeAssistantIdFlow.value = id
    }

    // Response Characteristics
    fun getFormalityLevel(context: Context): Int = getPrefs(context).getInt(KEY_FORMALITY_LEVEL, 2).coerceIn(0, 4)
    fun setFormalityLevel(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_FORMALITY_LEVEL, level.coerceIn(0, 4)).apply()

    fun getDetailLevel(context: Context): Int = getPrefs(context).getInt(KEY_DETAIL_LEVEL, 3).coerceIn(0, 4)
    fun setDetailLevel(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_DETAIL_LEVEL, level.coerceIn(0, 4)).apply()

    fun getTechnicalDepth(context: Context): Int = getPrefs(context).getInt(KEY_TECHNICAL_DEPTH, 2).coerceIn(0, 4)
    fun setTechnicalDepth(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_TECHNICAL_DEPTH, level.coerceIn(0, 4)).apply()

    fun getUseExamples(context: Context): Boolean = getPrefs(context).getBoolean(KEY_USE_EXAMPLES, true)
    fun setUseExamples(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_USE_EXAMPLES, enabled).apply()

    fun getUseStructuredFormatting(context: Context): Boolean = getPrefs(context).getBoolean(KEY_USE_STRUCTURED, true)
    fun setUseStructuredFormatting(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_USE_STRUCTURED, enabled).apply()

    fun getEmojiFrequency(context: Context): String = getPrefs(context).getString(KEY_EMOJI_FREQ, "low") ?: "low"
    fun setEmojiFrequency(context: Context, freq: String) = getPrefs(context).edit().putString(KEY_EMOJI_FREQ, freq).apply()

    fun getResponseLength(context: Context): String = getPrefs(context).getString(KEY_RESPONSE_LENGTH, "medium") ?: "medium"
    fun setResponseLength(context: Context, len: String) = getPrefs(context).edit().putString(KEY_RESPONSE_LENGTH, len).apply()

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

    // --- Provider Keys (Hardware Keystore Consolidated SSOT: SecureKeyStore) ---

    private fun readSecretWithLegacyMigration(
        context: Context,
        secureKeyName: String,
        legacyEncPrefKey: String
    ): String {
        val current = SecureKeyStore.getSecret(context, secureKeyName)
        if (current.isNotBlank()) return current
        val legacyPrefs = EncryptedPreferences(context)
        val legacy = legacyPrefs.getString(legacyEncPrefKey)
        if (legacy.isNotBlank()) {
            SecureKeyStore.putSecret(context, secureKeyName, legacy)
            legacyPrefs.remove(legacyEncPrefKey)
        }
        return legacy
    }

    private fun writeSecretOnly(
        context: Context,
        secureKeyName: String,
        legacyEncPrefKey: String,
        value: String,
        markExternalConsent: Boolean = false
    ) {
        SecureKeyStore.putSecret(context, secureKeyName, value)
        EncryptedPreferences(context).remove(legacyEncPrefKey)
        if (markExternalConsent && value.isNotBlank()) {
            setExternalAiConsent(context, true)
        }
    }

    fun getEffectiveGeminiKey(context: Context?): String {
        if (context == null) return ""
        return readSecretWithLegacyMigration(context, SecureKeyStore.KEY_GEMINI, EncryptedPreferences.KEY_GEMINI)
    }

    fun getEffectiveOpenRouterKey(context: Context?): String {
        if (context == null) return ""
        return readSecretWithLegacyMigration(context, SecureKeyStore.KEY_OPENROUTER, EncryptedPreferences.KEY_OPENROUTER)
    }

    fun setCustomGeminiKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_GEMINI, EncryptedPreferences.KEY_GEMINI, key)
    }

    fun setCustomOpenRouterKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_OPENROUTER, EncryptedPreferences.KEY_OPENROUTER, key)
    }

    fun getCustomGeminiKey(context: Context): String = getEffectiveGeminiKey(context)

    fun getCustomOpenRouterKey(context: Context): String = getEffectiveOpenRouterKey(context)

    // NVIDIA NIM Multi-Model Encrypted Keys
    fun getNvidiaKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_NVIDIA, EncryptedPreferences.KEY_NVIDIA)

    fun setNvidiaKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_NVIDIA, EncryptedPreferences.KEY_NVIDIA, key)
    }

    fun getNvidiaMinimaxKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_NVIDIA_MINIMAX, EncryptedPreferences.KEY_NVIDIA_MINIMAX)
            .ifBlank { getNvidiaKey(context) }

    fun setNvidiaMinimaxKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_NVIDIA_MINIMAX, EncryptedPreferences.KEY_NVIDIA_MINIMAX, key)
    }

    fun getNvidiaGlmKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_NVIDIA_GLM, EncryptedPreferences.KEY_NVIDIA_GLM)
            .ifBlank { getNvidiaKey(context) }

    fun setNvidiaGlmKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_NVIDIA_GLM, EncryptedPreferences.KEY_NVIDIA_GLM, key)
    }

    fun getNvidiaMuseKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_NVIDIA_MUSE, EncryptedPreferences.KEY_NVIDIA_MUSE)
            .ifBlank { getNvidiaKey(context) }

    fun setNvidiaMuseKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_NVIDIA_MUSE, EncryptedPreferences.KEY_NVIDIA_MUSE, key)
    }

    fun getNvidiaGemmaKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_NVIDIA_GEMMA, EncryptedPreferences.KEY_NVIDIA_GEMMA)
            .ifBlank { getNvidiaKey(context) }

    fun setNvidiaGemmaKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_NVIDIA_GEMMA, EncryptedPreferences.KEY_NVIDIA_GEMMA, key)
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
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_GROQ, EncryptedPreferences.KEY_GROQ)

    fun setGroqKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_GROQ, EncryptedPreferences.KEY_GROQ, key)
    }

    fun getHuggingFaceKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_HUGGINGFACE, EncryptedPreferences.KEY_HUGGINGFACE)

    fun setHuggingFaceKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_HUGGINGFACE, EncryptedPreferences.KEY_HUGGINGFACE, key)
    }

    fun getGitHubModelsKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_GITHUB_MODELS, EncryptedPreferences.KEY_GITHUB_MODELS)

    fun setGitHubModelsKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_GITHUB_MODELS, EncryptedPreferences.KEY_GITHUB_MODELS, key)
    }

    fun getCerebrasKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_CEREBRAS, EncryptedPreferences.KEY_CEREBRAS)

    fun setCerebrasKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_CEREBRAS, EncryptedPreferences.KEY_CEREBRAS, key)
    }

    fun getSambaNovaKey(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_SAMBANOVA, EncryptedPreferences.KEY_SAMBANOVA)

    fun setSambaNovaKey(context: Context, key: String) {
        writeSecretOnly(context, SecureKeyStore.KEY_SAMBANOVA, EncryptedPreferences.KEY_SAMBANOVA, key)
    }

    fun getComfyUiUrl(context: Context): String =
        readSecretWithLegacyMigration(context, SecureKeyStore.KEY_COMFYUI_URL, EncryptedPreferences.KEY_COMFYUI_URL)

    fun setComfyUiUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        writeSecretOnly(context, SecureKeyStore.KEY_COMFYUI_URL, EncryptedPreferences.KEY_COMFYUI_URL, clean, markExternalConsent = false)
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

    fun isCompanionAgeVerified(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_COMPANION_AGE_VERIFIED, false)

    fun setCompanionAgeVerified(context: Context, verified: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_COMPANION_AGE_VERIFIED, verified)
    }

    /**
     * Prevents the 18+ Companion persona from activating unless explicitly confirmed by the user.
     */
    fun resolveSafePersona(context: Context?, requestedPersona: String?): String {
        val clean = requestedPersona?.trim().takeUnless { it.isNullOrEmpty() } ?: "AI_ASSISTANT"
        if (clean.equals("GF_COMPANION", ignoreCase = true)) {
            val verified = context?.let { isCompanionAgeVerified(it) } ?: false
            if (!verified) return "AI_ASSISTANT"
        }
        return clean
    }

    fun savePersona(context: Context, persona: String) {
        val safe = resolveSafePersona(context, persona)
        getPrefs(context).edit().putString(KEY_ACTIVE_PERSONA, safe).apply()
    }

    fun getSavedPersona(context: Context): String {
        val stored = getPrefs(context).getString(KEY_ACTIVE_PERSONA, "AI_ASSISTANT") ?: "AI_ASSISTANT"
        return resolveSafePersona(context, stored)
    }

    private fun migrateLegacyTierId(raw: String): String {
        val clean = raw.trim().lowercase(java.util.Locale.ROOT)
        return when (clean) {
            "godmode" -> com.ayra.ai.app.ai.providers.ModelTier.SECURITY_RESEARCH.id
            "qwen_uncensored" -> com.ayra.ai.app.ai.providers.ModelTier.OPEN_WEIGHTS.id
            else -> raw
        }
    }

    fun saveModelTier(context: Context, tier: String) {
        val canonical = com.ayra.ai.app.ai.providers.ModelTier.canonicalizeId(migrateLegacyTierId(tier))
        getPrefs(context).edit().putString(KEY_SELECTED_MODEL_TIER, canonical).apply()
    }

    fun getSavedModelTier(context: Context): String {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_SELECTED_MODEL_TIER, "auto") ?: "auto"
        val migrated = migrateLegacyTierId(stored)
        val canonical = com.ayra.ai.app.ai.providers.ModelTier.canonicalizeId(migrated)
        if (migrated != stored) {
            prefs.edit().putString(KEY_SELECTED_MODEL_TIER, canonical).apply()
        }
        return canonical
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
    private const val KEY_CRISIS_REGION = "safety_crisis_helpline_region"
    private const val KEY_ONBOARDING_DONE = "ui_onboarding_completed_v1"
    private const val KEY_RAG_EXTERNAL_CONSENT = "sec_rag_external_ai_consent"

    fun getRoutingMode(context: Context): String {
        val raw = getPrefs(context).getString(KEY_ROUTING_MODE, "auto") ?: "auto"
        return com.ayra.ai.app.ai.providers.AiRoutingMode.fromId(raw).id
    }

    fun setRoutingMode(context: Context, mode: String) {
        val clean = com.ayra.ai.app.ai.providers.AiRoutingMode.fromId(mode).id
        getPrefs(context).edit().putString(KEY_ROUTING_MODE, clean).apply()
    }

    /**
     * Built-in keyless cloud AI pool is fail-closed (disabled) by default and requires
     * explicit opt-in consent alongside active external AI consent and non-local-only mode.
     */
    fun isFreeAiPoolEnabled(context: Context): Boolean {
        if (isLocalOnlyAiModeEnabled(context) || !hasExternalAiConsent(context)) return false
        return getSensitivePrefs(context).getBoolean(KEY_FREE_AI_POOL_ENABLED, false) &&
            getSensitivePrefs(context).getBoolean(KEY_FREE_POOL_CONSENT, false)
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

    fun getCrisisRegion(context: Context): String =
        getPrefs(context).getString(KEY_CRISIS_REGION, "AUTO") ?: "AUTO"

    fun setCrisisRegion(context: Context, regionCode: String) {
        getPrefs(context).edit().putString(KEY_CRISIS_REGION, regionCode.uppercase()).apply()
    }

    fun isOnboardingCompleted(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, completed).apply()
    }

    fun hasRagExternalSendConsent(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_RAG_EXTERNAL_CONSENT, false)

    fun setRagExternalSendConsent(context: Context, consented: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_RAG_EXTERNAL_CONSENT, consented)
    }

    // --- Encrypted AI Memory & Personalization Settings ---
    private const val KEY_MEMORY_ENABLED = "ai_memory_enabled"
    private const val KEY_USER_CUSTOM_INSTRUCTIONS = "user_custom_instructions"
    private const val KEY_REMEMBERED_FACTS = "user_remembered_facts"

    fun isMemoryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MEMORY_ENABLED, false)
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

    data class StructuredInstructions(
        val rolePrompt: String = "",
        val aboutUser: String = "",
        val howToRespond: String = "",
        val preferredOutputFormat: String = "",
        val interests: String = "",
        val restrictions: String = ""
    )

    fun getStructuredInstructions(context: Context): StructuredInstructions {
        val enc = getSensitivePrefs(context)
        return StructuredInstructions(
            rolePrompt = enc.getString(KEY_STRUCT_ROLE, ""),
            aboutUser = enc.getString(KEY_STRUCT_ABOUT_USER, ""),
            howToRespond = enc.getString(KEY_STRUCT_HOW_RESPOND, ""),
            preferredOutputFormat = enc.getString(KEY_STRUCT_OUTPUT_FORMAT, ""),
            interests = enc.getString(KEY_STRUCT_INTERESTS, ""),
            restrictions = enc.getString(KEY_STRUCT_RESTRICTIONS, "")
        )
    }

    fun saveStructuredInstructions(context: Context, instructions: StructuredInstructions) {
        val enc = getSensitivePrefs(context)
        enc.putString(KEY_STRUCT_ROLE, instructions.rolePrompt.trim())
        enc.putString(KEY_STRUCT_ABOUT_USER, instructions.aboutUser.trim())
        enc.putString(KEY_STRUCT_HOW_RESPOND, instructions.howToRespond.trim())
        enc.putString(KEY_STRUCT_OUTPUT_FORMAT, instructions.preferredOutputFormat.trim())
        enc.putString(KEY_STRUCT_INTERESTS, instructions.interests.trim())
        enc.putString(KEY_STRUCT_RESTRICTIONS, instructions.restrictions.trim())
    }

    fun buildCombinedPersonalizationBlock(context: Context): String {
        val freeform = getUserCustomInstructions(context).trim()
        val s = getStructuredInstructions(context)
        val formality = listOf("Very Casual", "Casual", "Balanced", "Formal", "Academic/Executive")[getFormalityLevel(context)]
        val detail = listOf("Minimal", "Brief", "Balanced", "Detailed", "Exhaustive")[getDetailLevel(context)]
        val tech = listOf("Layperson", "Beginner", "Intermediate", "Advanced", "Expert")[getTechnicalDepth(context)]
        val useExamples = getUseExamples(context)
        val useStructured = getUseStructuredFormatting(context)
        val emojiFreq = getEmojiFrequency(context)
        val lengthPref = getResponseLength(context)

        return buildString {
            if (freeform.isNotBlank()) append(freeform).append("\n")
            if (s.rolePrompt.isNotBlank()) append("AI Role Directive: ${s.rolePrompt}\n")
            if (s.aboutUser.isNotBlank()) append("About User: ${s.aboutUser}\n")
            if (s.howToRespond.isNotBlank()) append("Response Style: ${s.howToRespond}\n")
            if (s.preferredOutputFormat.isNotBlank()) append("Preferred Output Format: ${s.preferredOutputFormat}\n")
            if (s.interests.isNotBlank()) append("User Interests & Stack: ${s.interests}\n")
            if (s.restrictions.isNotBlank()) append("Avoid / Restrictions: ${s.restrictions}\n")
            append(
                "Response Calibration: Tone=$formality, Detail=$detail, TechnicalDepth=$tech, Length=$lengthPref, " +
                    "Examples=${if (useExamples) "Yes" else "Minimal"}, StructuredMarkdown=${if (useStructured) "Yes" else "PlainProse"}, Emojis=$emojiFreq."
            )
        }.trim()
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

    fun addRememberedFact(context: Context, fact: String): Boolean {
        val clean = fact.trim()
        if (clean.length < 3 || com.ayra.ai.app.ai.memory.LongTermMemoryManager.containsSensitiveCredentialOrPii(clean)) {
            return false
        }
        val current = getRememberedFacts(context).toMutableSet()
        current.add(clean)
        saveRememberedFactsEncrypted(context, current)
        getPrefs(context).edit().remove(KEY_REMEMBERED_FACTS).apply()
        return true
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

    fun isCloudSyncEnabled(context: Context): Boolean =
        !isLocalOnlyAiModeEnabled(context) &&
            getSensitivePrefs(context).getBoolean(KEY_CLOUD_SYNC_ENABLED, false)

    fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled)
    }

    fun isCloudE2eeSyncEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_CLOUD_E2EE_SYNC_ENABLED, true)

    fun setCloudE2eeSyncEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_CLOUD_E2EE_SYNC_ENABLED, enabled)
    }

    fun hasExternalAiConsent(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_EXTERNAL_AI_CONSENT, false)

    fun setExternalAiConsent(context: Context, consented: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_EXTERNAL_AI_CONSENT, consented)
    }

    fun hasFreePoolConsent(context: Context): Boolean =
        !isLocalOnlyAiModeEnabled(context) &&
            hasExternalAiConsent(context) &&
            getSensitivePrefs(context).getBoolean(KEY_FREE_POOL_CONSENT, false)

    fun getBackendGatewayUrl(context: Context): String =
        getSensitivePrefs(context).getString(KEY_BACKEND_GATEWAY_URL, "").trim()

    fun setBackendGatewayUrl(context: Context, url: String) {
        getSensitivePrefs(context).putString(KEY_BACKEND_GATEWAY_URL, url.trim())
    }

    fun isByokPreferred(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_PREFER_BYOK_DIRECT, false)

    fun setByokPreferred(context: Context, preferred: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_PREFER_BYOK_DIRECT, preferred)
    }

    fun isPromptInjectionGuardEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_PROMPT_INJECTION_GUARD, true)

    fun setPromptInjectionGuardEnabled(context: Context, enabled: Boolean) {
        getSensitivePrefs(context).putBoolean(KEY_PROMPT_INJECTION_GUARD, enabled)
    }

    fun isAllowLanEndpointsEnabled(context: Context): Boolean =
        getSensitivePrefs(context).getBoolean(KEY_ALLOW_LAN_ENDPOINTS, false)

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

    fun getRemoteModelCatalogJson(context: Context): String =
        getSensitivePrefs(context).getString(KEY_REMOTE_MODEL_CATALOG_JSON, "")

    fun setRemoteModelCatalogJson(context: Context, json: String) {
        getSensitivePrefs(context).putString(KEY_REMOTE_MODEL_CATALOG_JSON, json)
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
        _accentPresetFlow.value = "cyan"
        _customAccentHexFlow.value = "#00F0FF"
        _fontScaleFlow.value = 1.0f
        _bubbleCornerDpFlow.value = 18
        _uiDensityFlow.value = "balanced"
        _reducedMotionFlow.value = false
        _thinkingModeFlow.value = "balanced"
        _activeProjectIdFlow.value = 0L
        _activeAssistantIdFlow.value = 0L
        _screenCaptureBlockedFlow.value = false
    }
}
