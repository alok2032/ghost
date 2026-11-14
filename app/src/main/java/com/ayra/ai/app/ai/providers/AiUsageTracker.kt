package com.ayra.ai.app.ai.providers

import android.content.Context
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.EncryptedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ProviderUsageMetric(
    val providerId: String,
    val displayName: String,
    val totalRequests: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastLatencyMs: Long = 0L,
    val avgLatencyMs: Long = 0L,
    val lastUsedMs: Long = 0L
) {
    val successRatePercent: Int
        get() = if (totalRequests <= 0) 0 else ((successCount * 100.0) / totalRequests).toInt().coerceIn(0, 100)
}

/**
 * Local persistent telemetry tracker recording real AI provider call counts,
 * successes, failures, and measured latencies encrypted via [AyraKeyManager.ALIAS_PREFS_KEK].
 * Never sends external analytics; migrates & scrubs any legacy plaintext metrics file.
 */
object AiUsageTracker {

    private const val LEGACY_PREFS_NAME = "ayra_provider_usage_metrics"
    private const val ENCRYPTED_PREFS_NAME = "ayra_provider_usage_metrics_enc"
    private const val KEY_METRICS_JSON = "metrics_v1"

    private val metricsMap = ConcurrentHashMap<String, ProviderUsageMetric>()
    private val _metricsFlow = MutableStateFlow<List<ProviderUsageMetric>>(emptyList())
    val metricsFlow: StateFlow<List<ProviderUsageMetric>> = _metricsFlow.asStateFlow()

    @Volatile
    private var loadedFromDisk = false

    private fun encryptedPrefs(context: Context): EncryptedPreferences =
        EncryptedPreferences(
            context = context,
            prefsFileName = ENCRYPTED_PREFS_NAME,
            keyAlias = AyraKeyManager.ALIAS_PREFS_KEK
        )

    fun loadIfNeeded(context: Context?) {
        if (context == null || loadedFromDisk) return
        synchronized(this) {
            if (loadedFromDisk) return
            try {
                val enc = encryptedPrefs(context)
                var raw = enc.getString(KEY_METRICS_JSON, "")
                if (raw.isBlank()) {
                    // Auto-migrate legacy plaintext metrics and delete plaintext file
                    val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                    val legacyRaw = legacy.getString(KEY_METRICS_JSON, null)
                    if (!legacyRaw.isNullOrBlank()) {
                        raw = legacyRaw
                        enc.putString(KEY_METRICS_JSON, legacyRaw)
                    }
                    legacy.edit().clear().apply()
                }
                if (raw.isNotBlank()) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("providerId").ifBlank { continue }
                        metricsMap[id] = ProviderUsageMetric(
                            providerId = id,
                            displayName = o.optString("displayName", id),
                            totalRequests = o.optInt("totalRequests", 0),
                            successCount = o.optInt("successCount", 0),
                            failureCount = o.optInt("failureCount", 0),
                            lastLatencyMs = o.optLong("lastLatencyMs", 0L),
                            avgLatencyMs = o.optLong("avgLatencyMs", 0L),
                            lastUsedMs = o.optLong("lastUsedMs", 0L)
                        )
                    }
                    publishSorted()
                }
            } catch (_: Exception) {
            } finally {
                loadedFromDisk = true
            }
        }
    }

    fun recordCall(
        context: Context?,
        provider: AiProviderType,
        success: Boolean,
        latencyMs: Long
    ) {
        loadIfNeeded(context)
        val safeLatency = latencyMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        synchronized(this) {
            val prev = metricsMap[provider.id] ?: ProviderUsageMetric(
                providerId = provider.id,
                displayName = provider.displayName
            )
            val newSuccess = prev.successCount + if (success) 1 else 0
            val newFailure = prev.failureCount + if (success) 0 else 1
            val newTotal = prev.totalRequests + 1
            val newAvg = if (success && safeLatency > 0L) {
                if (prev.successCount == 0 || prev.avgLatencyMs == 0L) {
                    safeLatency
                } else {
                    ((prev.avgLatencyMs * prev.successCount) + safeLatency) / newSuccess
                }
            } else {
                prev.avgLatencyMs
            }

            metricsMap[provider.id] = prev.copy(
                displayName = provider.displayName,
                totalRequests = newTotal,
                successCount = newSuccess,
                failureCount = newFailure,
                lastLatencyMs = if (safeLatency > 0L) safeLatency else prev.lastLatencyMs,
                avgLatencyMs = newAvg,
                lastUsedMs = now
            )
            publishSorted()
            persist(context)
        }
    }

    fun getAllMetrics(context: Context? = null): List<ProviderUsageMetric> {
        loadIfNeeded(context)
        return _metricsFlow.value
    }

    fun clearAll(context: Context?) {
        synchronized(this) {
            metricsMap.clear()
            publishSorted()
            if (context != null) {
                runCatching {
                    encryptedPrefs(context).clear()
                    context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().clear().commit()
                }
            }
        }
    }

    private fun publishSorted() {
        _metricsFlow.value = metricsMap.values
            .sortedWith(compareByDescending<ProviderUsageMetric> { it.totalRequests }.thenBy { it.avgLatencyMs })
    }

    private fun persist(context: Context?) {
        if (context == null) return
        runCatching {
            val arr = JSONArray()
            metricsMap.values.forEach { m ->
                arr.put(JSONObject().apply {
                    put("providerId", m.providerId)
                    put("displayName", m.displayName)
                    put("totalRequests", m.totalRequests)
                    put("successCount", m.successCount)
                    put("failureCount", m.failureCount)
                    put("lastLatencyMs", m.lastLatencyMs)
                    put("avgLatencyMs", m.avgLatencyMs)
                    put("lastUsedMs", m.lastUsedMs)
                })
            }
            encryptedPrefs(context).putString(KEY_METRICS_JSON, arr.toString())
        }
    }
}
