package com.ayra.ai.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DiagnosticEvent(
    val timestampMs: Long,
    val correlationId: String,
    val subsystem: String,
    val operation: String,
    val durationMs: Long,
    val success: Boolean,
    val detail: String
)

data class DiagnosticsTelemetrySnapshot(
    val totalEvents: Int = 0,
    val failureCount: Int = 0,
    val meanLatencyMs: Long = 0L,
    val p95LatencyMs: Long = 0L,
    val recentEvents: List<DiagnosticEvent> = emptyList()
)

/**
 * Bounded structured observability & secret-redacted telemetry engine (Phase 12).
 *
 * Maintains correlation IDs, per-subsystem latency distributions (mean & p95),
 * and strictly scrubs API keys, Bearer headers, GitHub tokens, and password fields
 * before persisting in memory or exporting JSON diagnostics.
 */
object AyraDiagnosticsEngine {

    private const val MAX_RING_EVENTS = 250
    private val lock = Any()
    private val ringBuffer = ArrayDeque<DiagnosticEvent>(MAX_RING_EVENTS)
    private val _snapshotFlow = MutableStateFlow(DiagnosticsTelemetrySnapshot())
    val snapshotFlow: StateFlow<DiagnosticsTelemetrySnapshot> = _snapshotFlow.asStateFlow()

    private val REDACTION_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("AIza[0-9A-Za-z_-]{10,}") to "AIza[REDACTED]",
        Regex("sk-[A-Za-z0-9_-]{10,}") to "sk-[REDACTED]",
        Regex("gh[pousr]_[A-Za-z0-9_]{10,}") to "gh_[REDACTED]",
        Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}") to "$1[REDACTED]",
        Regex("(?i)(x-api-key|api[_-]?key|secret|token|password|authorization)\\s*([:=])\\s*\"?[^\\s,;\"'&}]+\"?") to "$1$2[REDACTED]"
    )

    fun newCorrelationId(prefix: String = "ayra"): String {
        val cleanPrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "ayra" }
        return "$cleanPrefix-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    fun redactSecrets(raw: String): String {
        if (raw.isEmpty()) return raw
        var scrubbed = raw
        for ((pattern, replacement) in REDACTION_PATTERNS) {
            scrubbed = pattern.replace(scrubbed, replacement)
        }
        return scrubbed
    }

    fun record(
        subsystem: String,
        operation: String,
        durationMs: Long,
        success: Boolean,
        detail: String = "",
        correlationId: String = newCorrelationId(subsystem.lowercase())
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            timestampMs = System.currentTimeMillis(),
            correlationId = redactSecrets(correlationId).take(48),
            subsystem = redactSecrets(subsystem).take(48),
            operation = redactSecrets(operation).take(80),
            durationMs = durationMs.coerceAtLeast(0L),
            success = success,
            detail = redactSecrets(detail).take(240)
        )
        synchronized(lock) {
            while (ringBuffer.size >= MAX_RING_EVENTS) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(event)
            _snapshotFlow.value = computeSnapshotLocked()
        }
        return event
    }

    fun getTelemetrySnapshot(): DiagnosticsTelemetrySnapshot = synchronized(lock) {
        computeSnapshotLocked()
    }

    fun exportRedactedJson(): String = synchronized(lock) {
        val snap = computeSnapshotLocked()
        val arr = JSONArray()
        snap.recentEvents.forEach { ev ->
            arr.put(
                JSONObject().apply {
                    put("timestampMs", ev.timestampMs)
                    put("correlationId", redactSecrets(ev.correlationId))
                    put("subsystem", redactSecrets(ev.subsystem))
                    put("operation", redactSecrets(ev.operation))
                    put("durationMs", ev.durationMs)
                    put("success", ev.success)
                    put("detail", redactSecrets(ev.detail))
                }
            )
        }
        JSONObject().apply {
            put("totalEvents", snap.totalEvents)
            put("failureCount", snap.failureCount)
            put("meanLatencyMs", snap.meanLatencyMs)
            put("p95LatencyMs", snap.p95LatencyMs)
            put("events", arr)
        }.toString(2)
    }

    internal fun clearForTesting() {
        synchronized(lock) {
            ringBuffer.clear()
            _snapshotFlow.value = DiagnosticsTelemetrySnapshot()
        }
    }

    private fun computeSnapshotLocked(): DiagnosticsTelemetrySnapshot {
        if (ringBuffer.isEmpty()) return DiagnosticsTelemetrySnapshot()
        val list = ringBuffer.toList()
        val sortedLatencies = list.map { it.durationMs }.sorted()
        val total = list.size
        val failures = list.count { !it.success }
        val mean = sortedLatencies.sum() / total
        val p95Index = (((total - 1) * 0.95).toInt()).coerceIn(0, total - 1)
        val p95 = sortedLatencies[p95Index]
        return DiagnosticsTelemetrySnapshot(
            totalEvents = total,
            failureCount = failures,
            meanLatencyMs = mean,
            p95LatencyMs = p95,
            recentEvents = list.asReversed()
        )
    }
}
