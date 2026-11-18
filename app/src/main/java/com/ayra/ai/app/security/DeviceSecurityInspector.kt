package com.ayra.ai.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

enum class IntegrityVerdictStatus(val label: String) {
    VERIFIED_SIGNAL("Verified Signal"),
    WARNING("Security Warning Detected"),
    UNKNOWN("UNKNOWN (Server Verification Required)")
}

data class DeviceSecurityReport(
    val isDebuggableBuild: Boolean,
    val isDebuggerAttached: Boolean,
    val rootSignalsDetected: List<String>,
    val isTestKeysBuild: Boolean,
    val apkSigningSha256: String,
    val keystoreLevel: KeySecurityLevel,
    val biometricCapability: BiometricCapability,
    val databaseState: DatabaseEncryptionState,
    val playIntegrityStatus: IntegrityVerdictStatus,
    val playIntegrityNote: String
) {
    val riskSignalsCount: Int
        get() = (if (isDebuggableBuild) 1 else 0) +
            (if (isDebuggerAttached) 1 else 0) +
            (if (rootSignalsDetected.isNotEmpty()) 1 else 0) +
            (if (isTestKeysBuild) 1 else 0)
}

/**
 * Genuine Mobile Attack Defense & Runtime Device Security Inspector (Phase 10).
 *
 * Security Honesty Rule:
 * - Never fabricates "Secure" or "Passed" for Play Integrity when a Cloud Project decryption
 *   backend is not linked; explicitly reports [IntegrityVerdictStatus.UNKNOWN].
 */
object DeviceSecurityInspector {

    private val knownRootPaths = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/data/adb/magisk",
        "/data/adb/ksu"
    )

    fun inspectDeviceSecurity(context: Context): DeviceSecurityReport {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val testKeys = Build.TAGS?.contains("test-keys", ignoreCase = true) == true
        val rootSignals = detectRootSignals()
        val certSha = readApkSigningCertSha256(context)
        val keyReport = AyraKeyManager.inspectKeySecurity(context, AyraKeyManager.ALIAS_MASTER_SECRETS)
        val biometric = BiometricAuthManager.checkCapability(context)
        val dbState = VaultDatabaseKeyManager.currentEncryptionState

        return DeviceSecurityReport(
            isDebuggableBuild = isDebuggable,
            isDebuggerAttached = debuggerAttached,
            rootSignalsDetected = rootSignals,
            isTestKeysBuild = testKeys,
            apkSigningSha256 = certSha,
            keystoreLevel = keyReport.securityLevel,
            biometricCapability = biometric,
            databaseState = dbState,
            playIntegrityStatus = IntegrityVerdictStatus.UNKNOWN,
            playIntegrityNote = "Play Integrity token requires backend Google Cloud project decryption & verdict verification before claiming device attestation."
        )
    }

    private fun detectRootSignals(): List<String> {
        val hits = mutableListOf<String>()
        knownRootPaths.forEach { path ->
            if (runCatching { File(path).exists() }.getOrDefault(false)) {
                hits.add(path)
            }
        }
        return hits
    }

    fun readApkSigningCertSha256(context: Context): String {
        return runCatching {
            val pm = context.packageManager
            val pkgName = context.packageName
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners ?: info.signingInfo?.signingCertificateHistory
                signers?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (certBytes == null) {
                "UNAVAILABLE"
            } else {
                MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString(":") { "%02X".format(it) }
            }
        }.getOrElse { "UNAVAILABLE" }
    }

    /**
     * Attempts a genuine Google Play Integrity token request when [cloudProjectNumber] > 0.
     * Note: Even when a token is returned on-device, an app cannot trust an unverified token without
     * server-side decryption; hence status remains UNKNOWN unless verified by the trusted backend.
     */
    suspend fun requestPlayIntegrityTokenIfConfigured(
        context: Context,
        nonceBase64: String,
        cloudProjectNumber: Long
    ): Pair<IntegrityVerdictStatus, String> = suspendCancellableCoroutine { cont ->
        if (cloudProjectNumber <= 0L) {
            cont.resume(
                IntegrityVerdictStatus.UNKNOWN to
                    "UNKNOWN: No Cloud Project Number configured for Play Integrity attestation."
            )
            return@suspendCancellableCoroutine
        }
        runCatching {
            val mgr = IntegrityManagerFactory.create(context.applicationContext)
            val req = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .setNonce(nonceBase64)
                .build()
            mgr.requestIntegrityToken(req)
                .addOnSuccessListener { resp ->
                    val tokenLen = resp.token()?.length ?: 0
                    cont.resume(
                        IntegrityVerdictStatus.UNKNOWN to
                            "Integrity token obtained ($tokenLen chars) — send to Ayra AI backend to decrypt & verify verdict."
                    )
                }
                .addOnFailureListener { err ->
                    cont.resume(
                        IntegrityVerdictStatus.UNKNOWN to
                            "Play Integrity unavailable on this device/build (${err.javaClass.simpleName})."
                    )
                }
        }.onFailure { err ->
            cont.resume(
                IntegrityVerdictStatus.UNKNOWN to
                    "Play Integrity API error (${err.javaClass.simpleName})."
            )
        }
    }
}
