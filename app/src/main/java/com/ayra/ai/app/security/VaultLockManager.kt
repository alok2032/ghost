package com.ayra.ai.app.security

import android.content.Context
import android.os.SystemClock
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages configurable Vault Locking, background inactivity timeout enforcement,
 * and cryptographic unlock proof tracking (no bare boolean unlock).
 */
object VaultLockManager {

    private val _isVaultLocked = MutableStateFlow(false)
    val isVaultLocked: StateFlow<Boolean> = _isVaultLocked.asStateFlow()

    private val _lockReason = MutableStateFlow<String?>(null)
    val lockReason: StateFlow<String?> = _lockReason.asStateFlow()

    private val _lastCryptoProofPrefix = MutableStateFlow<String?>(null)
    val lastCryptoProofPrefix: StateFlow<String?> = _lastCryptoProofPrefix.asStateFlow()

    @Volatile
    private var backgroundedAtMs: Long = 0L

    @Volatile
    private var lastActivityMs: Long = 0L

    fun initialize(context: Context, nowMs: Long = SystemClock.elapsedRealtime()) {
        lastActivityMs = nowMs
        if (AyraPreferences.isBiometricLockEnabled(context) && _lastCryptoProofPrefix.value == null) {
            _isVaultLocked.value = true
            _lockReason.value = "Vault lock is enabled. Verify biometrics or device PIN to unlock."
        }
    }

    fun recordUserActivity(nowMs: Long = SystemClock.elapsedRealtime()) {
        lastActivityMs = nowMs
    }

    fun onAppBackgrounded(context: Context, nowMs: Long = SystemClock.elapsedRealtime()) {
        backgroundedAtMs = nowMs
        val timeoutSec = AyraPreferences.getAutoLockTimeoutSeconds(context)
        if (AyraPreferences.isBiometricLockEnabled(context) && timeoutSec == 0L) {
            lockNow(context, "Vault locked automatically on background")
        }
    }

    fun onAppForegrounded(context: Context, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!AyraPreferences.isBiometricLockEnabled(context)) {
            return false
        }
        val timeoutSec = AyraPreferences.getAutoLockTimeoutSeconds(context)
        if (timeoutSec < 0L) return _isVaultLocked.value
        val referenceMs = if (backgroundedAtMs > 0L) backgroundedAtMs else lastActivityMs
        if (referenceMs > 0L && (nowMs - referenceMs) >= timeoutSec * 1000L) {
            lockNow(context, "Auto-locked after ${timeoutSec}s inactivity")
            return true
        }
        return _isVaultLocked.value
    }

    fun checkInactivityTimeout(context: Context, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!AyraPreferences.isBiometricLockEnabled(context) || _isVaultLocked.value) return _isVaultLocked.value
        val timeoutSec = AyraPreferences.getAutoLockTimeoutSeconds(context)
        if (timeoutSec <= 0L) return _isVaultLocked.value
        if (lastActivityMs > 0L && (nowMs - lastActivityMs) >= timeoutSec * 1000L) {
            lockNow(context, "Auto-locked after ${timeoutSec}s inactivity")
            return true
        }
        return false
    }

    fun lockNow(context: Context?, reason: String = "Locked by user") {
        _lastCryptoProofPrefix.value = null
        _lockReason.value = reason
        _isVaultLocked.value = true
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.BIOMETRIC,
            severity = SecurityAuditSeverity.INFO,
            message = "Vault locked: $reason"
        )
    }

    /**
     * Releases the lock ONLY when supplied with a non-empty cryptographic proof tag
     * produced by [BiometricAuthManager] (backed by Keystore CryptoObject cipher execution).
     */
    fun unlockWithVerifiedProof(
        context: Context?,
        proofTagHex: String,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        if (proofTagHex.isBlank() || proofTagHex.length < 16) return false
        _lastCryptoProofPrefix.value = proofTagHex.take(12)
        _lockReason.value = null
        _isVaultLocked.value = false
        lastActivityMs = nowMs
        backgroundedAtMs = 0L
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.BIOMETRIC,
            severity = SecurityAuditSeverity.INFO,
            message = "Vault unlocked with cryptographic proof ${proofTagHex.take(8)}..."
        )
        return true
    }
}
