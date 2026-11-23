package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class AiExecutionEnvironment {
    ROUTE_A_LOCAL,
    ROUTE_B_OWN_BACKEND,
    ROUTE_C_EXTERNAL_CLOUD
}

data class AiRouteDecision(
    val allowed: Boolean,
    val environment: AiExecutionEnvironment,
    val denialReason: String? = null
)

/**
 * Zero-Trust Hybrid AI Gateway & Policy Enforcer (Phases 5 & 6).
 *
 * Unifies AYRA's three AI environments under consistent privacy & authorization policies:
 * - Route A: Local AI (Local Ollama / LAN, offline knowledge, on-device RAG).
 * - Route B: AYRA Zero-Trust Node/TS Backend Proxy (authenticated with Firebase ID Token).
 * - Route C: External AI Cloud Providers (Gemini / Firebase AI Logic / OpenRouter / NVIDIA / etc.).
 */
object SecureAiGatewayClient {

    /**
     * Evaluates whether dispatching a prompt to [provider] is permitted under current security settings:
     * - Blocks all external cloud providers when Local-Only Privacy Mode is enabled.
     * - Requires explicit user consent for External Cloud AI and anonymous Public Free AI Pool.
     */
    fun evaluateProviderAccess(context: Context?, provider: AiProviderType): AiRouteDecision {
        val env = when (provider) {
            AiProviderType.LOCAL_OLLAMA -> AiExecutionEnvironment.ROUTE_A_LOCAL
            else -> AiExecutionEnvironment.ROUTE_C_EXTERNAL_CLOUD
        }

        if (context == null) {
            return AiRouteDecision(
                allowed = false,
                environment = env,
                denialReason = "Blocked external AI provider '${provider.displayName}': Android Context is required to verify privacy consent."
            )
        }

        if (AyraPreferences.isLocalOnlyAiModeEnabled(context) && env != AiExecutionEnvironment.ROUTE_A_LOCAL) {
            return AiRouteDecision(
                allowed = false,
                environment = env,
                denialReason = "Blocked external AI provider '${provider.displayName}' because Local-Only Privacy Mode is active."
            )
        }

        if (env == AiExecutionEnvironment.ROUTE_C_EXTERNAL_CLOUD && !AyraPreferences.hasExternalAiConsent(context)) {
            return AiRouteDecision(
                allowed = false,
                environment = env,
                denialReason = "External Cloud AI consent is disabled in Security Center. Enable consent or switch to Local AI."
            )
        }

        if (provider == AiProviderType.POLLINATIONS_FREE && !AyraPreferences.isFreeAiPoolEnabled(context)) {
            return AiRouteDecision(
                allowed = false,
                environment = env,
                denialReason = "Public Free AI Pool requires explicit opt-in consent in Security Center / Settings."
            )
        }

        return AiRouteDecision(true, env)
    }

    fun shouldUseBackendGatewayProxy(
        context: Context?,
        hasExplicitTestOverrides: Boolean = false
    ): Boolean {
        if (hasExplicitTestOverrides || context == null) return false
        if (AyraPreferences.isLocalOnlyAiModeEnabled(context)) return false
        if (!AyraPreferences.hasExternalAiConsent(context)) return false
        if (AyraPreferences.isByokPreferred(context)) return false
        if (AyraPreferences.getBackendGatewayUrl(context).isBlank()) return false
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return false
        return user.uid.isNotBlank()
    }

    /**
     * Strips accidental secret/API key tokens before transmitting diagnostic context or logging.
     */
    fun redactSecretsForOutboundSafety(input: String): String {
        return SecurityAuditLogger.redactSensitiveText(input)
    }

    fun mapProviderToGatewaySlug(provider: AiProviderType?): String = when (provider) {
        AiProviderType.OPENROUTER -> "openrouter"
        AiProviderType.NVIDIA_NIM -> "nvidia"
        AiProviderType.GROQ -> "groq"
        else -> "gemini"
    }

    suspend fun probeBackendGatewayHealth(
        context: Context,
        backendBaseUrl: String,
        httpClient: okhttp3.OkHttpClient? = null,
        allowUnvalidatedUrlForTests: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        if (AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            throw SecurityException("Blocked backend check: Local-Only Privacy Mode is active.")
        }
        if (!allowUnvalidatedUrlForTests) {
            val urlCheck = NetworkSecurityHelper.validatePublicHttpsUrl(backendBaseUrl)
            if (!urlCheck.allowed) {
                throw IllegalArgumentException(urlCheck.reason ?: "Unsafe backend URL (HTTPS public domain required).")
            }
        }
        val activeClient = httpClient ?: NetworkSecurityHelper.hardenedPooledClient
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url("${backendBaseUrl.trimEnd('/')}/health")
            .get()
            .build()
        activeClient.newCall(req).execute().use { resp ->
            val raw = resp.readBounded()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Backend gateway /health HTTP ${resp.code}: ${raw.take(150)}")
            }
        }
        (System.currentTimeMillis() - start).coerceAtLeast(1L)
    }

    suspend fun fetchBackendModelCatalog(
        context: Context,
        backendBaseUrl: String,
        appCheckToken: String? = null,
        httpClient: okhttp3.OkHttpClient? = null,
        bearerTokenOverride: String? = null,
        allowUnvalidatedUrlForTests: Boolean = false
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            return@withContext Result.failure(SecurityException("Blocked backend catalog: Local-Only Privacy Mode is active."))
        }
        if (!AyraPreferences.hasExternalAiConsent(context)) {
            return@withContext Result.failure(SecurityException("Blocked backend catalog: External Cloud AI consent is disabled."))
        }
        if (!allowUnvalidatedUrlForTests) {
            val urlCheck = NetworkSecurityHelper.validatePublicHttpsUrl(backendBaseUrl)
            if (!urlCheck.allowed) {
                return@withContext Result.failure(SecurityException("Unsafe backend URL: ${urlCheck.reason}"))
            }
        }

        val tokenResult = if (!bearerTokenOverride.isNullOrBlank()) {
            bearerTokenOverride
        } else {
            val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
                ?: return@withContext Result.failure(SecurityException("Firebase authentication required for Ayra AI Backend."))
            runCatching {
                val task = user.getIdToken(false)
                com.google.android.gms.tasks.Tasks.await(task).token
            }.getOrNull()
        }
        if (tokenResult.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Unable to obtain verified Firebase ID token."))
        }

        val resolvedAppCheckToken = appCheckToken?.takeIf { it.isNotBlank() } ?: runCatching {
            val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
            com.google.android.gms.tasks.Tasks.await(appCheck.getAppCheckToken(false)).token
        }.getOrNull()

        val reqBuilder = Request.Builder()
            .url("${backendBaseUrl.trimEnd('/')}/api/v1/models/catalog")
            .header("Authorization", "Bearer $tokenResult")
            .header("X-Ayra-Device-Id", AyraPreferences.getOrCreateDeviceId(context))
            .get()

        if (!resolvedAppCheckToken.isNullOrBlank()) {
            reqBuilder.header("X-Firebase-AppCheck", resolvedAppCheckToken)
        }

        val activeClient = httpClient ?: NetworkSecurityHelper.hardenedPooledClient
        runCatching {
            activeClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.readBounded()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Backend catalog HTTP ${resp.code}: ${raw.take(200)}")
                }
                JSONObject(raw)
            }
        }
    }

    /**
     * Sends a Zero-Trust authenticated chat request to AYRA's own backend AI gateway (`backend/`),
     * attaching a fresh Firebase ID Token in `Authorization: Bearer` and Firebase App Check token
     * in `X-Firebase-AppCheck` over strict TLS 1.3 HTTPS.
     */
    suspend fun callAuthenticatedBackendAiProxy(
        context: Context,
        backendBaseUrl: String,
        prompt: String,
        model: String = com.ayra.ai.app.ai.FirebaseAiEngine.MODEL_FLASH,
        provider: String = "gemini",
        appCheckToken: String? = null,
        systemPrompt: String = "",
        httpClient: okhttp3.OkHttpClient? = null,
        bearerTokenOverride: String? = null,
        allowUnvalidatedUrlForTests: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        if (AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            return@withContext Result.failure(SecurityException("Blocked backend call: Local-Only Privacy Mode is active."))
        }
        if (!AyraPreferences.hasExternalAiConsent(context)) {
            return@withContext Result.failure(SecurityException("Blocked backend call: External Cloud AI consent is disabled in Security Center."))
        }

        if (!allowUnvalidatedUrlForTests) {
            val urlCheck = NetworkSecurityHelper.validatePublicHttpsUrl(backendBaseUrl)
            if (!urlCheck.allowed) {
                return@withContext Result.failure(SecurityException("Unsafe backend URL: ${urlCheck.reason}"))
            }
        }

        val tokenResult = if (!bearerTokenOverride.isNullOrBlank()) {
            bearerTokenOverride
        } else {
            val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
                ?: return@withContext Result.failure(SecurityException("Firebase authentication required for Ayra AI Backend."))
            runCatching {
                val task = user.getIdToken(false)
                com.google.android.gms.tasks.Tasks.await(task).token
            }.getOrNull()
        }

        if (tokenResult.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Unable to obtain verified Firebase ID token."))
        }

        val resolvedAppCheckToken = appCheckToken?.takeIf { it.isNotBlank() } ?: runCatching {
            val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
            com.google.android.gms.tasks.Tasks.await(appCheck.getAppCheckToken(false)).token
        }.getOrNull()

        val cleanPrompt = if (AyraPreferences.isPrivacyModeEnabled(context)) {
            redactSecretsForOutboundSafety(prompt)
        } else prompt
        val cleanSystem = if (AyraPreferences.isPrivacyModeEnabled(context) && systemPrompt.isNotBlank()) {
            redactSecretsForOutboundSafety(systemPrompt)
        } else systemPrompt

        val messagesArr = org.json.JSONArray().apply {
            if (cleanSystem.isNotBlank()) {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", cleanSystem)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", cleanPrompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("provider", provider)
            put("model", model)
            put("messages", messagesArr)
        }.toString()

        val reqBuilder = Request.Builder()
            .url("${backendBaseUrl.trimEnd('/')}/api/v1/ai/chat")
            .header("Authorization", "Bearer $tokenResult")
            .header("Content-Type", "application/json")
            .header("X-Ayra-Device-Id", AyraPreferences.getOrCreateDeviceId(context))
            .post(bodyJson.toRequestBody("application/json".toMediaType()))

        if (!resolvedAppCheckToken.isNullOrBlank()) {
            reqBuilder.header("X-Firebase-AppCheck", resolvedAppCheckToken)
        }

        val activeClient = httpClient ?: NetworkSecurityHelper.hardenedPooledClient
        runCatching {
            activeClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.readBounded()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Backend gateway HTTP ${resp.code}: ${raw.take(250)}")
                }
                val parsed = JSONObject(raw)
                parsed.optString("content").ifBlank { parsed.optString("text", raw) }
            }
        }
    }
}
