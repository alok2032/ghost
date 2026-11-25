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
import java.util.concurrent.TimeUnit

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

enum class BackendGatewaySource {
    NONE,
    ENCRYPTED_VAULT,
    REMOTE_CONFIG_CATALOG,
    REMOTE_CONFIG_ENDPOINT
}

data class BackendGatewayInitResult(
    val initialized: Boolean,
    val endpointUrl: String? = null,
    val source: BackendGatewaySource = BackendGatewaySource.NONE,
    val validationResult: UrlValidationResult? = null,
    val healthLatencyMs: Long? = null,
    val verifiedAtMs: Long = System.currentTimeMillis(),
    val failureReason: String? = null
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

    @Volatile
    private var verifiedGatewayUrl: String? = null

    @Volatile
    private var rejectedCandidateUrl: String? = null

    @Volatile
    var lastInitResult: BackendGatewayInitResult? = null
        private set

    fun invalidateEndpointCache() {
        verifiedGatewayUrl = null
        rejectedCandidateUrl = null
        lastInitResult = null
    }

    /**
     * Reads the Route B gateway URL from the hardware-backed encrypted vault without falling back
     * to any hardcoded default URL. Returns `null` when unset.
     */
    fun resolveVaultBackendUrl(context: Context): String? {
        return AyraPreferences.getBackendGatewayUrl(context)
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
    }

    /**
     * Returns the initialized & HTTPS-verified Route B gateway URL (from remote config or
     * encrypted vault) when valid, or `null` otherwise.
     */
    fun getValidatedBackendGatewayUrl(context: Context): String? {
        val cached = verifiedGatewayUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (cached != null && NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(cached).allowed) {
            return cached
        }
        val vaulted = resolveVaultBackendUrl(context) ?: return null
        if (vaulted == rejectedCandidateUrl) return null
        return if (NetworkSecurityHelper.validatePublicHttpsSyntaxOnly(vaulted).allowed) {
            vaulted
        } else {
            null
        }
    }

    internal fun extractBackendUrlFromRemoteJson(rawPayload: String?): String? {
        val trimmed = rawPayload?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val directCandidates = listOf(
            root.optString("backendGatewayUrl", ""),
            root.optString("backend_gateway_url", ""),
            root.optString("gatewayUrl", ""),
            root.optString("gateway_url", ""),
            root.optJSONObject("gateway")?.optString("url", "").orEmpty(),
            root.optJSONObject("config")?.optString("backendGatewayUrl", "").orEmpty(),
            root.optJSONObject("endpoints")?.optString("gateway", "").orEmpty()
        )
        return directCandidates
            .asSequence()
            .map { it.trim().trimEnd('/') }
            .firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    /**
     * Dynamically initializes the Route B gateway URL from secure remote configuration or the
     * hardware-backed encrypted vault ([SecureKeyStore]), enforcing strict HTTPS, non-443 port,
     * and public-DNS SSRF integrity checks before caching or persisting the endpoint.
     */
    suspend fun initialize(
        context: Context,
        remoteConfigUrlOverride: String? = null,
        httpClient: okhttp3.OkHttpClient? = null,
        dnsResolver: (String) -> Array<java.net.InetAddress> = { host ->
            java.net.InetAddress.getAllByName(host)
        },
        remoteConfigFetcher: (suspend (Context) -> String?)? = null,
        probeHealthOnInit: Boolean = false,
        allowUnvalidatedUrlForTests: Boolean = false
    ): BackendGatewayInitResult = withContext(Dispatchers.IO) {
        val localOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context)
        var candidateUrl: String? = null
        var source = BackendGatewaySource.NONE
        var fetchFailureReason: String? = null

        if (!localOnly) {
            if (remoteConfigFetcher != null) {
                val rawRemote = runCatching { remoteConfigFetcher(context) }
                    .onFailure { fetchFailureReason = it.message ?: "Remote config provider failed." }
                    .getOrNull()
                val extracted = extractBackendUrlFromRemoteJson(rawRemote)
                if (extracted != null) {
                    candidateUrl = extracted
                    source = BackendGatewaySource.REMOTE_CONFIG_ENDPOINT
                }
            }

            if (candidateUrl == null) {
                val remoteConfigEndpoint = remoteConfigUrlOverride?.trim()?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: AyraPreferences.getBackendRemoteConfigUrl(context).trim().trimEnd('/')
                        .takeIf { it.isNotBlank() }

                if (remoteConfigEndpoint != null) {
                    val rcIntegrity = if (allowUnvalidatedUrlForTests) {
                        UrlValidationResult(true)
                    } else {
                        NetworkSecurityHelper.validatePublicHttpsUrlAsync(remoteConfigEndpoint, dnsResolver)
                    }
                    if (!rcIntegrity.allowed) {
                        fetchFailureReason = "Remote config URL rejected: ${rcIntegrity.reason}"
                        SecurityAuditLogger.log(
                            context = context,
                            category = SecurityAuditCategory.NETWORK,
                            severity = SecurityAuditSeverity.CRITICAL,
                            message = fetchFailureReason!!
                        )
                    } else {
                        val activeClient = httpClient ?: NetworkSecurityHelper.hardenedPooledClient
                        val rcRequest = Request.Builder()
                            .url(remoteConfigEndpoint)
                            .header("Accept", "application/json")
                            .header("X-Ayra-Device-Id", AyraPreferences.getOrCreateDeviceId(context))
                            .get()
                            .build()
                        val remoteJson = runCatching {
                            NetworkSecurityHelper.executePublicHttpsWithValidatedRedirects(
                                client = activeClient,
                                initialRequest = rcRequest,
                                urlValidator = { target ->
                                    if (allowUnvalidatedUrlForTests) UrlValidationResult(true)
                                    else NetworkSecurityHelper.validatePublicHttpsUrl(target, dnsResolver)
                                }
                            ).use { resp ->
                                val body = resp.readBounded()
                                if (!resp.isSuccessful) {
                                    throw NonRetryableHttpException(resp.code, "Remote config HTTP ${resp.code}")
                                }
                                body
                            }
                        }.onFailure { err ->
                            fetchFailureReason = "Remote config fetch failed: ${err.message}"
                        }.getOrNull()

                        val extracted = extractBackendUrlFromRemoteJson(remoteJson)
                        if (extracted != null) {
                            candidateUrl = extracted
                            source = BackendGatewaySource.REMOTE_CONFIG_ENDPOINT
                        }
                    }
                }
            }

            if (candidateUrl == null) {
                val catalogCandidate = extractBackendUrlFromRemoteJson(
                    AyraPreferences.getRemoteModelCatalogJson(context)
                )
                if (catalogCandidate != null) {
                    candidateUrl = catalogCandidate
                    source = BackendGatewaySource.REMOTE_CONFIG_CATALOG
                }
            }
        }

        if (candidateUrl == null) {
            val vaultedCandidate = resolveVaultBackendUrl(context)
            if (vaultedCandidate != null) {
                candidateUrl = vaultedCandidate
                source = BackendGatewaySource.ENCRYPTED_VAULT
            }
        }

        val cleanCandidate = candidateUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (cleanCandidate == null) {
            verifiedGatewayUrl = null
            return@withContext BackendGatewayInitResult(
                initialized = false,
                endpointUrl = null,
                source = BackendGatewaySource.NONE,
                failureReason = fetchFailureReason ?: "No backend gateway URL configured in remote config or encrypted vault."
            ).also { lastInitResult = it }
        }

        val integrityCheck = if (allowUnvalidatedUrlForTests) {
            UrlValidationResult(true)
        } else {
            NetworkSecurityHelper.validatePublicHttpsUrlAsync(cleanCandidate, dnsResolver)
        }
        if (!integrityCheck.allowed) {
            verifiedGatewayUrl = null
            rejectedCandidateUrl = cleanCandidate
            val reason = integrityCheck.reason ?: "Backend gateway endpoint failed HTTPS integrity verification."
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.NETWORK,
                severity = SecurityAuditSeverity.CRITICAL,
                message = "Rejected backend gateway URL from $source: $reason"
            )
            return@withContext BackendGatewayInitResult(
                initialized = false,
                endpointUrl = null,
                source = source,
                validationResult = integrityCheck,
                failureReason = reason
            ).also { lastInitResult = it }
        }

        var healthLatency: Long? = null
        if (probeHealthOnInit && !localOnly) {
            val probeResult = runCatching {
                probeBackendGatewayHealth(
                    context = context,
                    backendBaseUrl = cleanCandidate,
                    httpClient = httpClient,
                    allowUnvalidatedUrlForTests = allowUnvalidatedUrlForTests
                )
            }
            if (probeResult.isFailure) {
                verifiedGatewayUrl = null
                val reason = probeResult.exceptionOrNull()?.message ?: "Backend gateway /health probe failed."
                SecurityAuditLogger.log(
                    context = context,
                    category = SecurityAuditCategory.NETWORK,
                    severity = SecurityAuditSeverity.WARN,
                    message = "Backend gateway health verification failed during init: $reason"
                )
                return@withContext BackendGatewayInitResult(
                    initialized = false,
                    endpointUrl = null,
                    source = source,
                    validationResult = integrityCheck,
                    failureReason = reason
                ).also { lastInitResult = it }
            }
            healthLatency = probeResult.getOrNull()
        }

        val currentVault = resolveVaultBackendUrl(context)
        if (currentVault != cleanCandidate) {
            AyraPreferences.setBackendGatewayUrl(context, cleanCandidate)
        }
        rejectedCandidateUrl = null
        verifiedGatewayUrl = cleanCandidate

        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.NETWORK,
            severity = SecurityAuditSeverity.INFO,
            message = "Initialized HTTPS-verified backend gateway URL from $source."
        )
        return@withContext BackendGatewayInitResult(
            initialized = true,
            endpointUrl = cleanCandidate,
            source = source,
            validationResult = integrityCheck,
            healthLatencyMs = healthLatency
        ).also { lastInitResult = it }
    }

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
        if (getValidatedBackendGatewayUrl(context) == null) return false
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
        try {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 2) {
                val req = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/health")
                    .get()
                    .build()
                activeClient.newCall(req).execute().use { resp ->
                    val raw = resp.readBounded()
                    if (!resp.isSuccessful) {
                        val msg = "Backend gateway /health HTTP ${resp.code}: ${raw.take(150)}"
                        if (resp.code == 429 || resp.code in 502..504) {
                            throw RetryableHttpException(resp.code, msg)
                        }
                        throw NonRetryableHttpException(resp.code, msg)
                    }
                }
            }
            NetworkConnectivityManager.recordObservedNetworkSuccess()
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkConnectivityManager.recordObservedNetworkFailure(error = e)
            throw e
        }
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
                com.google.android.gms.tasks.Tasks.await(task, 5L, TimeUnit.SECONDS).token
            }.getOrNull()
        }
        if (tokenResult.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Unable to obtain verified Firebase ID token."))
        }

        val resolvedAppCheckToken = appCheckToken?.takeIf { it.isNotBlank() } ?: if (allowUnvalidatedUrlForTests && !bearerTokenOverride.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
                com.google.android.gms.tasks.Tasks.await(appCheck.getAppCheckToken(false), 5L, TimeUnit.SECONDS).token
            }.getOrNull()
        }

        val activeClient = httpClient ?: NetworkSecurityHelper.hardenedPooledClient
        runCatching {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 2) {
                val reqBuilder = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/api/v1/models/catalog")
                    .header("Authorization", "Bearer $tokenResult")
                    .header("X-Ayra-Device-Id", AyraPreferences.getOrCreateDeviceId(context))
                    .get()

                if (!resolvedAppCheckToken.isNullOrBlank()) {
                    reqBuilder.header("X-Firebase-AppCheck", resolvedAppCheckToken)
                }

                activeClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val raw = resp.readBounded()
                    if (!resp.isSuccessful) {
                        val msg = "Backend catalog HTTP ${resp.code}: ${raw.take(200)}"
                        if (resp.code == 429 || resp.code in 502..504) {
                            throw RetryableHttpException(resp.code, msg)
                        }
                        throw NonRetryableHttpException(resp.code, msg)
                    }
                    JSONObject(raw)
                }
            }.also {
                NetworkConnectivityManager.recordObservedNetworkSuccess()
            }
        }.onFailure { err ->
            NetworkConnectivityManager.recordObservedNetworkFailure(error = err)
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
                com.google.android.gms.tasks.Tasks.await(task, 5L, TimeUnit.SECONDS).token
            }.getOrNull()
        }

        if (tokenResult.isNullOrBlank()) {
            return@withContext Result.failure(SecurityException("Unable to obtain verified Firebase ID token."))
        }

        val resolvedAppCheckToken = appCheckToken?.takeIf { it.isNotBlank() } ?: if (allowUnvalidatedUrlForTests && !bearerTokenOverride.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
                com.google.android.gms.tasks.Tasks.await(appCheck.getAppCheckToken(false), 5L, TimeUnit.SECONDS).token
            }.getOrNull()
        }

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
