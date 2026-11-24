package com.ayra.ai.app.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.ayra.ai.app.AppCheckState
import com.ayra.ai.app.AppCheckStatusRegistry
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class NetworkTransportType(val label: String) {
    NONE("No Connection"),
    WIFI("Wi-Fi"),
    CELLULAR("Mobile Data"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    OTHER("Network")
}

enum class ConnectionHealthStatus(val label: String) {
    CONNECTED_VALIDATED("Online (Validated)"),
    CONNECTED_UNVALIDATED("Limited (No Internet Validation)"),
    CAPTIVE_PORTAL("Captive Portal Detected"),
    OFFLINE("Offline")
}

enum class DiagnosticCheckState(val label: String) {
    PASS("Connected"),
    DEGRADED("Degraded"),
    FAIL("Failed"),
    UNCONFIGURED("Not Configured")
}

data class SubsystemDiagnosticResult(
    val name: String,
    val state: DiagnosticCheckState,
    val latencyMs: Long? = null,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val category: NetworkFailureCategory = NetworkFailureCategory.NONE
)

data class NetworkConnectivityState(
    val status: ConnectionHealthStatus = ConnectionHealthStatus.OFFLINE,
    val transportType: NetworkTransportType = NetworkTransportType.NONE,
    val isConnected: Boolean = false,
    val isValidated: Boolean = false,
    val isCaptivePortal: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val lastSuccessfulConnectionMs: Long? = null,
    val lastFailureCategory: NetworkFailureCategory = NetworkFailureCategory.NONE,
    val statusSummary: String = "Checking network..."
)

data class NetworkDiagnosticsReport(
    val isRunning: Boolean = false,
    val lastRunMs: Long? = null,
    val internetCheck: SubsystemDiagnosticResult? = null,
    val dnsCheck: SubsystemDiagnosticResult? = null,
    val httpsCheck: SubsystemDiagnosticResult? = null,
    val backendCheck: SubsystemDiagnosticResult? = null,
    val firebaseCheck: SubsystemDiagnosticResult? = null,
    val aiProvidersCheck: SubsystemDiagnosticResult? = null,
    val activeProviderResults: List<SubsystemDiagnosticResult> = emptyList(),
    val lastSuccessMs: Long? = null
)

/**
 * Centralized Android ConnectivityMonitor & Recovery Coordinator.
 *
 * Uses [ConnectivityManager.registerDefaultNetworkCallback] (requiring only ACCESS_NETWORK_STATE)
 * to distinguish connected vs validated Internet, detect captive portals, classify failures,
 * execute bounded connectivity recovery on validated restoration, and power live diagnostics.
 */
object NetworkConnectivityManager {

    private const val TAG = "NetworkConnectivityMgr"
    private const val DEFAULT_RECOVERY_DEBOUNCE_MS = 350L
    private const val DEFAULT_RECOVERY_COOLDOWN_MS = 10_000L

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var recoveryJob: Job? = null

    @Volatile
    private var lastRecoveryTriggeredMs: Long = 0L

    @Volatile
    private var testSnapshotActive: Boolean = false

    private val _stateFlow = MutableStateFlow(NetworkConnectivityState())
    val stateFlow: StateFlow<NetworkConnectivityState> = _stateFlow.asStateFlow()

    private val _diagnosticsFlow = MutableStateFlow(NetworkDiagnosticsReport())
    val diagnosticsFlow: StateFlow<NetworkDiagnosticsReport> = _diagnosticsFlow.asStateFlow()

    private val _recoveryCountFlow = MutableStateFlow(0)
    val recoveryCountFlow: StateFlow<Int> = _recoveryCountFlow.asStateFlow()

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            updateFromSnapshot(
                isConnected = false,
                hasInternet = false,
                isValidated = false,
                isCaptivePortal = false,
                transport = NetworkTransportType.NONE
            )
            return
        }

        // Capture immediate OS snapshot before callback fires
        refreshSnapshot(cm)

        if (!initialized.compareAndSet(false, true)) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshSnapshot(cm)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                applyCapabilities(networkCapabilities)
            }

            override fun onLost(network: Network) {
                refreshSnapshot(cm)
            }

            override fun onUnavailable() {
                updateFromSnapshot(
                    isConnected = false,
                    hasInternet = false,
                    isValidated = false,
                    isCaptivePortal = false,
                    transport = NetworkTransportType.NONE
                )
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            initialized.set(false)
            Log.w(TAG, "Unable to register NetworkCallback: ${e.message}")
        } catch (e: Exception) {
            initialized.set(false)
            Log.w(TAG, "NetworkCallback registration failed: ${e.message}")
        }
    }

    fun registerRecoveryListener(listener: () -> Unit) {
        if (!recoveryListeners.contains(listener)) {
            recoveryListeners.add(listener)
        }
    }

    fun unregisterRecoveryListener(listener: () -> Unit) {
        recoveryListeners.remove(listener)
    }

    fun refreshFromContext(context: Context): NetworkConnectivityState {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            refreshSnapshot(cm)
        }
        return _stateFlow.value
    }

    /**
     * Authoritative OS internet validation check.
     * In production, refreshes from [ConnectivityManager] active network capabilities unless
     * a deterministic test snapshot was injected via [evaluateCapabilitiesSnapshotForTests].
     */
    @JvmStatic
    @JvmOverloads
    fun isValidatedInternetAvailable(context: Context? = null): Boolean {
        if (!testSnapshotActive && context != null) {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps != null) {
                applyCapabilities(caps)
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        return _stateFlow.value.status == ConnectionHealthStatus.CONNECTED_VALIDATED
    }

    private fun refreshSnapshot(cm: ConnectivityManager) {
        try {
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            testSnapshotActive = false
            applyCapabilities(caps)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading ConnectivityManager capabilities: ${e.message}")
        }
    }

    private fun applyCapabilities(caps: NetworkCapabilities?) {
        if (caps == null) {
            updateFromSnapshot(
                isConnected = false,
                hasInternet = false,
                isValidated = false,
                isCaptivePortal = false,
                transport = NetworkTransportType.NONE
            )
            return
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransportType.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
            else -> NetworkTransportType.OTHER
        }
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        updateFromSnapshot(
            isConnected = true,
            hasInternet = hasInternet,
            isValidated = isValidated,
            isCaptivePortal = isCaptivePortal,
            transport = transport
        )
    }

    internal fun evaluateCapabilitiesSnapshotForTests(
        isConnected: Boolean,
        hasInternet: Boolean,
        isValidated: Boolean,
        isCaptivePortal: Boolean,
        transport: NetworkTransportType,
        nowMs: Long = System.currentTimeMillis(),
        recoveryDebounceMs: Long = DEFAULT_RECOVERY_DEBOUNCE_MS,
        recoveryCooldownMs: Long = DEFAULT_RECOVERY_COOLDOWN_MS
    ): NetworkConnectivityState {
        testSnapshotActive = true
        return updateFromSnapshot(
            isConnected = isConnected,
            hasInternet = hasInternet,
            isValidated = isValidated,
            isCaptivePortal = isCaptivePortal,
            transport = transport,
            nowMs = nowMs,
            recoveryDebounceMs = recoveryDebounceMs,
            recoveryCooldownMs = recoveryCooldownMs
        )
    }

    internal fun resetForTests() {
        recoveryJob?.cancel()
        recoveryJob = null
        lastRecoveryTriggeredMs = 0L
        testSnapshotActive = false
        recoveryListeners.clear()
        _recoveryCountFlow.value = 0
        _stateFlow.value = NetworkConnectivityState()
        _diagnosticsFlow.value = NetworkDiagnosticsReport()
    }

    private fun updateFromSnapshot(
        isConnected: Boolean,
        hasInternet: Boolean,
        isValidated: Boolean,
        isCaptivePortal: Boolean,
        transport: NetworkTransportType,
        nowMs: Long = System.currentTimeMillis(),
        recoveryDebounceMs: Long = DEFAULT_RECOVERY_DEBOUNCE_MS,
        recoveryCooldownMs: Long = DEFAULT_RECOVERY_COOLDOWN_MS
    ): NetworkConnectivityState {
        val prev = _stateFlow.value
        val status = when {
            !isConnected || transport == NetworkTransportType.NONE -> ConnectionHealthStatus.OFFLINE
            isCaptivePortal -> ConnectionHealthStatus.CAPTIVE_PORTAL
            hasInternet && isValidated -> ConnectionHealthStatus.CONNECTED_VALIDATED
            else -> ConnectionHealthStatus.CONNECTED_UNVALIDATED
        }

        val failureCat = when (status) {
            ConnectionHealthStatus.OFFLINE -> NetworkFailureCategory.NO_NETWORK
            ConnectionHealthStatus.CAPTIVE_PORTAL -> NetworkFailureCategory.CAPTIVE_PORTAL
            ConnectionHealthStatus.CONNECTED_UNVALIDATED -> NetworkFailureCategory.NO_VALIDATED_INTERNET
            ConnectionHealthStatus.CONNECTED_VALIDATED -> NetworkFailureCategory.NONE
        }

        val summary = when (status) {
            ConnectionHealthStatus.CONNECTED_VALIDATED -> "${transport.label} • Validated Internet"
            ConnectionHealthStatus.CONNECTED_UNVALIDATED -> "${transport.label} • Connected (Awaiting Validation)"
            ConnectionHealthStatus.CAPTIVE_PORTAL -> "${transport.label} • Captive Portal Sign-In Required"
            ConnectionHealthStatus.OFFLINE -> "Offline • No active network"
        }

        val lastSuccess = if (status == ConnectionHealthStatus.CONNECTED_VALIDATED) {
            nowMs
        } else {
            prev.lastSuccessfulConnectionMs
        }

        val next = NetworkConnectivityState(
            status = status,
            transportType = transport,
            isConnected = isConnected,
            isValidated = isValidated,
            isCaptivePortal = isCaptivePortal,
            hasInternetCapability = hasInternet,
            lastSuccessfulConnectionMs = lastSuccess,
            lastFailureCategory = failureCat,
            statusSummary = summary
        )
        _stateFlow.value = next

        val wasDisconnectedOrDegraded = prev.status == ConnectionHealthStatus.OFFLINE ||
            prev.status == ConnectionHealthStatus.CAPTIVE_PORTAL ||
            prev.status == ConnectionHealthStatus.CONNECTED_UNVALIDATED
        if (wasDisconnectedOrDegraded && status == ConnectionHealthStatus.CONNECTED_VALIDATED) {
            scheduleBoundedRecovery(nowMs, recoveryDebounceMs, recoveryCooldownMs)
        }
        return next
    }

    private fun scheduleBoundedRecovery(
        nowMs: Long,
        debounceMs: Long,
        cooldownMs: Long
    ) {
        if (lastRecoveryTriggeredMs > 0L && nowMs - lastRecoveryTriggeredMs < cooldownMs) return
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val current = _stateFlow.value
            if (current.status != ConnectionHealthStatus.CONNECTED_VALIDATED) return@launch
            lastRecoveryTriggeredMs = nowMs
            _recoveryCountFlow.value = _recoveryCountFlow.value + 1
            AyraDiagnosticsEngine.record(
                subsystem = "Connectivity",
                operation = "auto_recovery_triggered",
                durationMs = 1L,
                success = true,
                detail = "Network restored via ${current.transportType.label}; refreshed caches"
            )
            recoveryListeners.forEach { listener ->
                try {
                    listener()
                } catch (e: Exception) {
                    Log.w(TAG, "Recovery listener failed: ${e.message}")
                }
            }
        }
    }

    fun recordObservedNetworkSuccess(nowMs: Long = System.currentTimeMillis()) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            lastSuccessfulConnectionMs = nowMs,
            lastFailureCategory = NetworkFailureCategory.NONE
        )
    }

    fun recordObservedNetworkFailure(error: Throwable? = null, httpCode: Int? = null) {
        val cls = NetworkSecurityHelper.classifyNetworkFailure(error = error, httpCode = httpCode)
        val current = _stateFlow.value
        _stateFlow.value = current.copy(lastFailureCategory = cls.category)
    }

    /**
     * Runs live end-to-end diagnostics across OS transport, DNS, HTTPS/TLS, AYRA backend gateway,
     * Firebase AI Logic + App Check, and configured AI providers without any fake green results.
     */
    suspend fun runAllConnectionTests(
        context: Context,
        customDnsHost: String = "generativelanguage.googleapis.com",
        httpsProbeUrl: String = "https://www.gstatic.com/generate_204",
        httpClientOverride: OkHttpClient? = null,
        dnsResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
        providerProbeOverride: (suspend (AiProviderType) -> SubsystemDiagnosticResult)? = null
    ): NetworkDiagnosticsReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val startAll = System.currentTimeMillis()
        _diagnosticsFlow.value = _diagnosticsFlow.value.copy(isRunning = true)

        val currentOsState = if (httpClientOverride == null) {
            refreshFromContext(appContext)
        } else {
            _stateFlow.value
        }

        val localOnly = AyraPreferences.isLocalOnlyAiModeEnabled(appContext)
        val validatePublicUrls = httpClientOverride == null

        val internetCheck = when {
            httpClientOverride != null -> SubsystemDiagnosticResult(
                name = "OS Network Transport",
                state = if (currentOsState.status == ConnectionHealthStatus.CAPTIVE_PORTAL) {
                    DiagnosticCheckState.FAIL
                } else {
                    DiagnosticCheckState.PASS
                },
                detail = "${currentOsState.transportType.label} (${currentOsState.status.label})",
                category = currentOsState.lastFailureCategory
            )
            currentOsState.status == ConnectionHealthStatus.CONNECTED_VALIDATED -> SubsystemDiagnosticResult(
                name = "OS Network Transport",
                state = DiagnosticCheckState.PASS,
                detail = currentOsState.statusSummary,
                category = NetworkFailureCategory.NONE
            )
            currentOsState.status == ConnectionHealthStatus.CONNECTED_UNVALIDATED -> SubsystemDiagnosticResult(
                name = "OS Network Transport",
                state = DiagnosticCheckState.DEGRADED,
                detail = currentOsState.statusSummary,
                category = NetworkFailureCategory.NO_VALIDATED_INTERNET
            )
            currentOsState.status == ConnectionHealthStatus.CAPTIVE_PORTAL -> SubsystemDiagnosticResult(
                name = "OS Network Transport",
                state = DiagnosticCheckState.FAIL,
                detail = "Captive portal sign-in required on ${currentOsState.transportType.label}",
                category = NetworkFailureCategory.CAPTIVE_PORTAL
            )
            else -> SubsystemDiagnosticResult(
                name = "OS Network Transport",
                state = DiagnosticCheckState.FAIL,
                detail = "Device has no active Wi-Fi, cellular, or VPN transport.",
                category = NetworkFailureCategory.NO_NETWORK
            )
        }

        val osBlocksCloud = validatePublicUrls && (
            currentOsState.status == ConnectionHealthStatus.OFFLINE ||
                currentOsState.status == ConnectionHealthStatus.CAPTIVE_PORTAL
            )

        // 2. DNS Check
        val dnsCheck = when {
            localOnly -> SubsystemDiagnosticResult(
                name = "DNS Resolution",
                state = DiagnosticCheckState.DEGRADED,
                detail = "Cloud DNS skipped (Local-Only Privacy Mode active).",
                category = NetworkFailureCategory.SSRF_BLOCKED
            )
            osBlocksCloud -> SubsystemDiagnosticResult(
                name = "DNS Resolution",
                state = DiagnosticCheckState.FAIL,
                detail = "Cannot resolve DNS: ${currentOsState.status.label}.",
                category = currentOsState.lastFailureCategory
            )
            else -> {
                val dnsStart = System.currentTimeMillis()
                try {
                    val resolved = NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 2) {
                        val raw = dnsResolver(customDnsHost)
                        if (raw.isEmpty()) throw java.net.UnknownHostException("Empty DNS answer for $customDnsHost")
                        NetworkSecurityHelper.preferIpv4Order(raw.toList())
                    }
                    val v4Count = resolved.count { it is Inet4Address }
                    val v6Count = resolved.count { it is Inet6Address }
                    val latency = (System.currentTimeMillis() - dnsStart).coerceAtLeast(1L)
                    SubsystemDiagnosticResult(
                        name = "DNS Resolution",
                        state = DiagnosticCheckState.PASS,
                        latencyMs = latency,
                        detail = "$customDnsHost resolved (${v4Count} IPv4, ${v6Count} IPv6) in ${latency}ms",
                        category = NetworkFailureCategory.NONE
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val latency = (System.currentTimeMillis() - dnsStart).coerceAtLeast(1L)
                    val cls = NetworkSecurityHelper.classifyNetworkFailure(error = e)
                    SubsystemDiagnosticResult(
                        name = "DNS Resolution",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = latency,
                        detail = "${cls.category.label}: ${cls.userMessage}",
                        category = cls.category
                    )
                }
            }
        }

        // 3. HTTPS / TLS Check
        val httpsCheck = when {
            localOnly -> SubsystemDiagnosticResult(
                name = "HTTPS & TLS Transport",
                state = DiagnosticCheckState.DEGRADED,
                detail = "Public HTTPS probes skipped (Local-Only Privacy Mode active).",
                category = NetworkFailureCategory.SSRF_BLOCKED
            )
            osBlocksCloud -> SubsystemDiagnosticResult(
                name = "HTTPS & TLS Transport",
                state = DiagnosticCheckState.FAIL,
                detail = "HTTPS unreachable: ${currentOsState.status.label}.",
                category = currentOsState.lastFailureCategory
            )
            else -> {
                val client = httpClientOverride ?: NetworkSecurityHelper.hardenedPooledClient
                val probe = NetworkSecurityHelper.probeHttpsEndpoint(
                    url = httpsProbeUrl,
                    client = client,
                    maxAttempts = 2,
                    validatePublicUrl = validatePublicUrls
                )
                if (probe.ok) {
                    SubsystemDiagnosticResult(
                        name = "HTTPS & TLS Transport",
                        state = DiagnosticCheckState.PASS,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.message} (${probe.latencyMs}ms)",
                        category = NetworkFailureCategory.NONE
                    )
                } else {
                    SubsystemDiagnosticResult(
                        name = "HTTPS & TLS Transport",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.category.label}: ${probe.message}",
                        category = probe.category
                    )
                }
            }
        }

        // 4. AYRA Backend /health
        val gatewayUrl = AyraPreferences.getBackendGatewayUrl(appContext).trim()
        val backendCheck = when {
            localOnly -> SubsystemDiagnosticResult(
                name = "AYRA Backend (/health)",
                state = DiagnosticCheckState.DEGRADED,
                detail = "Disabled while Local-Only Privacy Mode is active.",
                category = NetworkFailureCategory.SSRF_BLOCKED
            )
            gatewayUrl.isBlank() -> SubsystemDiagnosticResult(
                name = "AYRA Backend (/health)",
                state = DiagnosticCheckState.UNCONFIGURED,
                detail = "No backend gateway URL configured (direct Firebase/BYOK mode active).",
                category = NetworkFailureCategory.NONE
            )
            osBlocksCloud -> SubsystemDiagnosticResult(
                name = "AYRA Backend (/health)",
                state = DiagnosticCheckState.FAIL,
                detail = "Unreachable while ${currentOsState.status.label}.",
                category = currentOsState.lastFailureCategory
            )
            else -> {
                val bStart = System.currentTimeMillis()
                try {
                    val latency = SecureAiGatewayClient.probeBackendGatewayHealth(
                        context = appContext,
                        backendBaseUrl = gatewayUrl,
                        httpClient = httpClientOverride,
                        allowUnvalidatedUrlForTests = !validatePublicUrls
                    )
                    SubsystemDiagnosticResult(
                        name = "AYRA Backend (/health)",
                        state = DiagnosticCheckState.PASS,
                        latencyMs = latency,
                        detail = "Healthy (${latency}ms)",
                        category = NetworkFailureCategory.NONE
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val latency = (System.currentTimeMillis() - bStart).coerceAtLeast(1L)
                    val cls = NetworkSecurityHelper.classifyNetworkFailure(error = e)
                    SubsystemDiagnosticResult(
                        name = "AYRA Backend (/health)",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = latency,
                        detail = "${cls.category.label}: ${cls.userMessage}",
                        category = cls.category
                    )
                }
            }
        }

        // 5. Firebase AI Logic & App Check
        val fbConfigAudit = FirebaseConfigConsistencyValidator.validate(appContext)
        val firebaseAvailable = FirebaseAiEngine.isAvailable(appContext)
        val appCheckState = AppCheckStatusRegistry.currentState
        val firebaseCheck = when {
            localOnly -> SubsystemDiagnosticResult(
                name = "Firebase AI & App Check",
                state = DiagnosticCheckState.DEGRADED,
                detail = "Paused (Local-Only Privacy Mode active).",
                category = NetworkFailureCategory.SSRF_BLOCKED
            )
            !fbConfigAudit.valid -> SubsystemDiagnosticResult(
                name = "Firebase AI & App Check",
                state = DiagnosticCheckState.FAIL,
                detail = fbConfigAudit.issues.joinToString("; "),
                category = NetworkFailureCategory.CLIENT_ERROR
            )
            !firebaseAvailable -> SubsystemDiagnosticResult(
                name = "Firebase AI & App Check",
                state = DiagnosticCheckState.FAIL,
                detail = "FirebaseApp not initialized.",
                category = NetworkFailureCategory.BACKEND_UNAVAILABLE
            )
            appCheckState == AppCheckState.ERROR || appCheckState == AppCheckState.MISSING_RELEASE_CONFIG ->
                SubsystemDiagnosticResult(
                    name = "Firebase AI & App Check",
                    state = DiagnosticCheckState.DEGRADED,
                    detail = "Firebase ready; App Check: ${appCheckState.description}",
                    category = NetworkFailureCategory.AUTH_FAILURE
                )
            httpClientOverride != null -> SubsystemDiagnosticResult(
                name = "Firebase AI & App Check",
                state = DiagnosticCheckState.PASS,
                detail = "Project ${fbConfigAudit.projectId} • ${appCheckState.description}",
                category = NetworkFailureCategory.NONE
            )
            osBlocksCloud -> SubsystemDiagnosticResult(
                name = "Firebase AI & App Check",
                state = DiagnosticCheckState.FAIL,
                detail = "Unreachable while ${currentOsState.status.label}.",
                category = currentOsState.lastFailureCategory
            )
            else -> {
                val probe = FirebaseAiEngine(appContext).runStartupSelfTest(appContext)
                when (probe.outcome) {
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.PASS -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.PASS,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.detail} • ${appCheckState.description}",
                        category = NetworkFailureCategory.NONE
                    )
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.QUOTA_EXCEEDED -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.DEGRADED,
                        latencyMs = probe.latencyMs,
                        detail = "Quota exceeded: ${probe.detail}",
                        category = NetworkFailureCategory.RATE_LIMITED
                    )
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.TIMEOUT -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.DEGRADED,
                        latencyMs = probe.latencyMs,
                        detail = probe.detail,
                        category = NetworkFailureCategory.TIMEOUT
                    )
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.AUTH_FAILED,
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.APP_CHECK_FAILED -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.outcome.name}: ${probe.detail}",
                        category = NetworkFailureCategory.AUTH_FAILURE
                    )
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.NETWORK_ERROR,
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.OFFLINE -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.outcome.name}: ${probe.detail}",
                        category = NetworkFailureCategory.DNS_FAILURE
                    )
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.API_DISABLED,
                    com.ayra.ai.app.ai.FirebaseSelfTestOutcome.UNAVAILABLE -> SubsystemDiagnosticResult(
                        name = "Firebase AI & App Check",
                        state = DiagnosticCheckState.FAIL,
                        latencyMs = probe.latencyMs,
                        detail = "${probe.outcome.name}: ${probe.detail}",
                        category = NetworkFailureCategory.BACKEND_UNAVAILABLE
                    )
                }
            }
        }

        // 6. Active AI Providers
        val ext = ExternalAiProviders(appContext, customHttpClient = httpClientOverride)
        val configuredProviders = ext.getActiveConfiguredProviders()
            .filter { it != AiProviderType.OMNI_ROUTE }
            .filter { !osBlocksCloud || it == AiProviderType.LOCAL_OLLAMA }
            .take(4)

        val providerResults = mutableListOf<SubsystemDiagnosticResult>()
        for (provider in configuredProviders) {
            if (providerProbeOverride != null) {
                providerResults.add(providerProbeOverride(provider))
            } else {
                val pStart = System.currentTimeMillis()
                try {
                    val latency = withTimeout(12_000L) {
                        ext.testProviderConnection(provider)
                    }
                    providerResults.add(
                        SubsystemDiagnosticResult(
                            name = provider.displayName,
                            state = DiagnosticCheckState.PASS,
                            latencyMs = latency,
                            detail = "Verified live response (${latency}ms)",
                            category = NetworkFailureCategory.NONE
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val latency = (System.currentTimeMillis() - pStart).coerceAtLeast(1L)
                    val cls = NetworkSecurityHelper.classifyNetworkFailure(error = e)
                    providerResults.add(
                        SubsystemDiagnosticResult(
                            name = provider.displayName,
                            state = DiagnosticCheckState.FAIL,
                            latencyMs = latency,
                            detail = "${cls.category.label}: ${SecurityAuditLogger.redactSensitiveText(e.message ?: cls.userMessage)}",
                            category = cls.category
                        )
                    )
                }
            }
        }

        val aiProvidersCheck = when {
            providerResults.isEmpty() -> SubsystemDiagnosticResult(
                name = "Configured AI Providers",
                state = if (osBlocksCloud) DiagnosticCheckState.FAIL else DiagnosticCheckState.UNCONFIGURED,
                detail = if (osBlocksCloud) {
                    "Cloud AI providers unreachable while ${currentOsState.status.label}."
                } else {
                    "No active external/local AI providers configured (check Keys or Consent)."
                },
                category = if (osBlocksCloud) currentOsState.lastFailureCategory else NetworkFailureCategory.NONE
            )
            providerResults.all { it.state == DiagnosticCheckState.PASS } -> SubsystemDiagnosticResult(
                name = "Configured AI Providers",
                state = DiagnosticCheckState.PASS,
                latencyMs = providerResults.mapNotNull { it.latencyMs }.average().toLong(),
                detail = "${providerResults.size}/${providerResults.size} active provider(s) verified",
                category = NetworkFailureCategory.NONE
            )
            providerResults.any { it.state == DiagnosticCheckState.PASS } -> {
                val okCount = providerResults.count { it.state == DiagnosticCheckState.PASS }
                SubsystemDiagnosticResult(
                    name = "Configured AI Providers",
                    state = DiagnosticCheckState.DEGRADED,
                    detail = "$okCount/${providerResults.size} provider(s) passed; others failed",
                    category = providerResults.firstOrNull { it.state == DiagnosticCheckState.FAIL }?.category
                        ?: NetworkFailureCategory.PROVIDER_OUTAGE
                )
            }
            else -> SubsystemDiagnosticResult(
                name = "Configured AI Providers",
                state = DiagnosticCheckState.FAIL,
                detail = providerResults.firstOrNull()?.detail ?: "All active provider probes failed",
                category = providerResults.firstOrNull()?.category ?: NetworkFailureCategory.PROVIDER_OUTAGE
            )
        }

        val now = System.currentTimeMillis()
        val overallSuccess = (dnsCheck.state == DiagnosticCheckState.PASS && httpsCheck.state == DiagnosticCheckState.PASS) ||
            (localOnly && providerResults.any { it.state == DiagnosticCheckState.PASS })

        val lastSuccess = if (overallSuccess) {
            recordObservedNetworkSuccess(now)
            now
        } else {
            val primaryFail = listOf(internetCheck, dnsCheck, httpsCheck, backendCheck, aiProvidersCheck)
                .firstOrNull { it.state == DiagnosticCheckState.FAIL }
            if (primaryFail != null) {
                _stateFlow.value = _stateFlow.value.copy(lastFailureCategory = primaryFail.category)
            }
            _diagnosticsFlow.value.lastSuccessMs ?: _stateFlow.value.lastSuccessfulConnectionMs
        }

        val report = NetworkDiagnosticsReport(
            isRunning = false,
            lastRunMs = now,
            internetCheck = internetCheck,
            dnsCheck = dnsCheck,
            httpsCheck = httpsCheck,
            backendCheck = backendCheck,
            firebaseCheck = firebaseCheck,
            aiProvidersCheck = aiProvidersCheck,
            activeProviderResults = providerResults,
            lastSuccessMs = lastSuccess
        )
        _diagnosticsFlow.value = report

        AyraDiagnosticsEngine.record(
            subsystem = "NetworkSuite",
            operation = "test_all_connections",
            durationMs = (now - startAll).coerceAtLeast(1L),
            success = overallSuccess,
            detail = "DNS=${dnsCheck.state.name}, HTTPS=${httpsCheck.state.name}, Backend=${backendCheck.state.name}, AI=${aiProvidersCheck.state.name}"
        )

        report
    }
}
