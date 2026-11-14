package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatSession
import com.ayra.ai.app.data.InstalledGitHubPlugin
import com.ayra.ai.app.data.StorageHelper
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedBackupMetadata(
    val backupId: String,
    val createdAtMs: Long,
    val kdfAlgorithm: String,
    val iterations: Int,
    val payloadSha256: String,
    val encryptedSizeBytes: Int,
    val containsSecretsOrDeviceKeys: Boolean = false
)

/**
 * Recovery-Passphrase Encrypted Backup & Restore Manager (Phase 9).
 *
 * Security Architecture:
 * - Derives an independent 256-bit Backup KEK from a user recovery passphrase using
 *   PBKDF2WithHmacSHA256 (210,000 iterations + unique 128-bit SecureRandom salt).
 * - Encrypts the key-free workspace payload with AES-256-GCM + AAD binding `(format|saltB64|payloadSha256)`.
 * - NEVER embeds Android Keystore KEKs, API keys, or OAuth tokens in any backup blob.
 * - Does NOT store recovery passphrases on device or server; loss of passphrase means permanent unrecoverability.
 */
object EncryptedBackupManager {

    const val FORMAT_V2 = "AYRA_ENCRYPTED_BACKUP_V2"
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    fun validatePassphraseStrength(passphrase: String): String? {
        if (passphrase.length < 10) return "Recovery passphrase must be at least 10 characters."
        val hasLetter = passphrase.any { it.isLetter() }
        val hasDigitOrSymbol = passphrase.any { !it.isLetterOrDigit() || it.isDigit() }
        if (!hasLetter || !hasDigitOrSymbol) {
            return "Include letters plus digits or symbols for strong backup key derivation."
        }
        return null
    }

    /**
     * Produces a self-contained encrypted `.ayrabak` JSON string protected by [recoveryPassphrase].
     */
    fun createEncryptedBackupJson(
        context: Context,
        recoveryPassphrase: String,
        sessions: List<ChatSession>,
        messages: List<ChatMessage>,
        plugins: List<InstalledGitHubPlugin>
    ): Pair<String, EncryptedBackupMetadata> {
        require(recoveryPassphrase.length >= 8) { "Passphrase must be at least 8 characters." }

        val workspaceJson = StorageHelper.exportSafeWorkspaceBackupJson(
            context = context,
            sessions = sessions,
            messages = messages,
            plugins = plugins
        )
        val plainBytes = workspaceJson.toByteArray(StandardCharsets.UTF_8)
        val payloadSha256 = sha256Hex(plainBytes)

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val backupKey = deriveBackupKey(recoveryPassphrase, salt, PBKDF2_ITERATIONS)

        val saltB64 = AyraKeyManager.encodeB64(salt)
        val aad = "$FORMAT_V2|$saltB64|$payloadSha256".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(plainBytes)
        } finally {
            backupKey.fill(0)
            plainBytes.fill(0)
        }

        val now = System.currentTimeMillis()
        val backupId = "bak_${now}_${payloadSha256.take(8)}"
        val ctB64 = AyraKeyManager.encodeB64(ciphertext)

        val envelope = JSONObject().apply {
            put("format", FORMAT_V2)
            put("backupId", backupId)
            put("kdf", KDF_ALGORITHM)
            put("iterations", PBKDF2_ITERATIONS)
            put("saltB64", saltB64)
            put("ivB64", AyraKeyManager.encodeB64(iv))
            put("payloadSha256", payloadSha256)
            put("ciphertextB64", ctB64)
            put("createdAtMs", now)
            put("containsSecrets", false)
            put("containsDeviceKeys", false)
            put("warning", "Recovery passphrase is never stored. If forgotten, this backup is mathematically unrecoverable.")
        }.toString(2)

        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.BACKUP,
            severity = SecurityAuditSeverity.INFO,
            message = "Created PBKDF2-SHA256 (${PBKDF2_ITERATIONS}i) + AES-256-GCM encrypted backup $backupId (${ciphertext.size}B)"
        )

        return envelope to EncryptedBackupMetadata(
            backupId = backupId,
            createdAtMs = now,
            kdfAlgorithm = KDF_ALGORITHM,
            iterations = PBKDF2_ITERATIONS,
            payloadSha256 = payloadSha256,
            encryptedSizeBytes = ciphertext.size,
            containsSecretsOrDeviceKeys = false
        )
    }

    /**
     * Decrypts, verifies SHA-256 & AES-GCM integrity, and restores a `.ayrabak` JSON backup.
     * Throws SecurityException or AEADBadTagException on wrong passphrase or tampered bytes.
     */
    fun restoreFromEncryptedBackupJson(
        context: Context,
        encryptedBackupJson: String,
        recoveryPassphrase: String
    ): StorageHelper.WorkspaceBackupPayload {
        val root = JSONObject(encryptedBackupJson)
        val format = root.getString("format")
        require(format == FORMAT_V2) { "Unsupported backup format: $format" }

        val iterations = root.getInt("iterations").coerceAtLeast(100_000)
        val saltB64 = root.getString("saltB64")
        val salt = AyraKeyManager.decodeB64(saltB64)
        val iv = AyraKeyManager.decodeB64(root.getString("ivB64"))
        val expectedSha256 = root.getString("payloadSha256")
        val ciphertext = AyraKeyManager.decodeB64(root.getString("ciphertextB64"))

        val backupKey = deriveBackupKey(recoveryPassphrase, salt, iterations)
        val aad = "$FORMAT_V2|$saltB64|$expectedSha256".toByteArray(StandardCharsets.UTF_8)

        val decryptedBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } finally {
            backupKey.fill(0)
        }

        val actualSha256 = sha256Hex(decryptedBytes)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            decryptedBytes.fill(0)
            throw SecurityException("Backup SHA-256 integrity mismatch; payload may be corrupted.")
        }

        val payloadJson = String(decryptedBytes, StandardCharsets.UTF_8)
        decryptedBytes.fill(0)

        val restored = StorageHelper.importSafeWorkspaceBackupJson(context, payloadJson)
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.BACKUP,
            severity = SecurityAuditSeverity.INFO,
            message = "Verified & restored encrypted backup (${restored.sessions.size} sessions, ${restored.messages.size} msgs)."
        )
        return restored
    }

    internal fun deriveBackupKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = passphrase.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, iterations, 256)
            val skf = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            skf.generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
