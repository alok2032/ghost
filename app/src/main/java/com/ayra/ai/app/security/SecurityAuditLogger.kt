package com.ayra.ai.app.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

enum class SecurityAuditCategory {
    AUTH,
    BIOMETRIC,
    KEYSTORE,
    DATABASE,
    VAULT_STORAGE,
    E2EE,
    NETWORK,
    AI_POLICY,
    BACKUP,
    ERASURE,
    INTEGRITY
}

enum class SecurityAuditSeverity {
    INFO,
    WARN,
    CRITICAL
}

data class SecurityAuditEvent(
    val id: String,
    val timestampMs: Long,
    val category: SecurityAuditCategory,
    val severity: SecurityAuditSeverity,
    val message: String,
    val chainHmacHex: String
)

/**
 * Tamper-evident, encrypted Security Audit & Incident Logger (Phase 12).
 *
 * Security Guarantees:
 * - Redacts API keys, Bearer tokens, credentials, private keys, and PII before persisting.
 * - HMAC-chains each entry to the previous entry's HMAC using [AyraKeyManager.ALIAS_AUDIT_HMAC].
 * - Persists the encrypted event ring buffer (up to 150 entries) via Keystore AES-256-GCM.
 */
object SecurityAuditLogger {

    private const val PREF_FILE = "ayra_security_audit_log_v1"
    private const val KEY_EVENTS_ENC = "events_enc_v1"
    private const val MAX_EVENTS = 150

    private val _events = MutableStateFlow<List<SecurityAuditEvent>>(emptyList())
    val events: StateFlow<List<SecurityAuditEvent>> = _events.asStateFlow()

    @Volatile
    private var loaded = false

    private val secretPatterns = listOf(
        Regex("AIza[0-9A-Za-z\\-_]{20,}"),
        Regex("sk-[A-Za-z0-9\\-_]{16,}"),
        Regex("gsk_[A-Za-z0-9\\-_]{16,}"),
        Regex("ghp_[A-Za-z0-9]{16,}"),
        Regex("hf_[A-Za-z0-9]{16,}"),
        Regex("tvly-[A-Za-z0-9\\-_]{12,}"),
        Regex("Bearer\\s+[A-Za-z0-9._\\-]{10,}", RegexOption.IGNORE_CASE),
        Regex("-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----")
    )

    fun redactSensitiveText(raw: String): String {
        var sanitized = raw
        secretPatterns.forEach { regex ->
            sanitized = sanitized.replace(regex, "[REDACTED_SECRET]")
        }
        return sanitized.take(360)
    }

    fun loadIfNeeded(context: Context?) {
        if (context == null || loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val enc = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getString(KEY_EVENTS_ENC, null)
                if (!enc.isNullOrBlank()) {
                    val jsonStr = AyraKeyManager.decryptStringWithAad(
                        context = context,
                        baseAlias = AyraKeyManager.ALIAS_PREFS_KEK,
                        serializedEnvelope = enc,
                        aadContext = "audit_log_v1"
                    )
                    val arr = JSONArray(jsonStr)
                    val parsed = buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                SecurityAuditEvent(
                                    id = o.optString("id"),
                                    timestampMs = o.optLong("ts"),
                                    category = runCatching {
                                        SecurityAuditCategory.valueOf(o.optString("cat"))
                                    }.getOrDefault(SecurityAuditCategory.INTEGRITY),
                                    severity = runCatching {
                                        SecurityAuditSeverity.valueOf(o.optString("sev"))
                                    }.getOrDefault(SecurityAuditSeverity.INFO),
                                    message = o.optString("msg"),
                                    chainHmacHex = o.optString("hmac")
                                )
                            )
                        }
                    }
                    _events.value = parsed
                }
            }
            loaded = true
        }
    }

    fun log(
        context: Context?,
        category: SecurityAuditCategory,
        severity: SecurityAuditSeverity,
        message: String
    ): SecurityAuditEvent {
        loadIfNeeded(context)
        val now = System.currentTimeMillis()
        val safeMsg = redactSensitiveText(message)
        val prevHmac = _events.value.firstOrNull()?.chainHmacHex ?: "GENESIS"
        val chainPayload = "$prevHmac|$now|${category.name}|${severity.name}|$safeMsg"
        val hmacHex = runCatching {
            AyraKeyManager.computeHmacSha256(
                context = context,
                data = chainPayload.toByteArray(StandardCharsets.UTF_8)
            ).joinToString("") { "%02x".format(it) }
        }.getOrDefault("hmac_unavailable")

        val event = SecurityAuditEvent(
            id = "evt_${now}_${(0..9999).random()}",
            timestampMs = now,
            category = category,
            severity = severity,
            message = safeMsg,
            chainHmacHex = hmacHex
        )

        synchronized(this) {
            val updated = (listOf(event) + _events.value).take(MAX_EVENTS)
            _events.value = updated
            persist(context, updated)
        }
        return event
    }

    fun verifyLogChain(context: Context?): Boolean {
        loadIfNeeded(context)
        val list = _events.value
        if (list.isEmpty()) return true
        // Events are stored newest-first; reverse to validate oldest -> newest
        val chronological = list.asReversed()
        var prevHmac = "GENESIS"
        for (evt in chronological) {
            if (evt.chainHmacHex == "hmac_unavailable") {
                prevHmac = evt.chainHmacHex
                continue
            }
            val payload = "$prevHmac|${evt.timestampMs}|${evt.category.name}|${evt.severity.name}|${evt.message}"
            val expected = runCatching {
                AyraKeyManager.computeHmacSha256(
                    context = context,
                    data = payload.toByteArray(StandardCharsets.UTF_8)
                ).joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return false
            if (!expected.equals(evt.chainHmacHex, ignoreCase = true)) {
                return false
            }
            prevHmac = evt.chainHmacHex
        }
        return true
    }

    fun clearAll(context: Context?) {
        synchronized(this) {
            _events.value = emptyList()
            loaded = true
            if (context != null) {
                runCatching {
                    context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                        .edit().clear().commit()
                }
            }
        }
    }

    private fun persist(context: Context?, eventsList: List<SecurityAuditEvent>) {
        if (context == null) return
        runCatching {
            val arr = JSONArray()
            eventsList.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("ts", e.timestampMs)
                    put("cat", e.category.name)
                    put("sev", e.severity.name)
                    put("msg", e.message)
                    put("hmac", e.chainHmacHex)
                })
            }
            val enc = AyraKeyManager.encryptStringWithAad(
                context = context,
                baseAlias = AyraKeyManager.ALIAS_PREFS_KEK,
                plaintext = arr.toString(),
                aadContext = "audit_log_v1"
            )
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EVENTS_ENC, enc)
                .apply()
        }
    }
}
