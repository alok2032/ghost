package com.ayra.ai.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class KeySecurityLevel(val label: String) {
    HARDWARE_STRONGBOX("Hardware StrongBox"),
    HARDWARE_TEE("Hardware TEE / Secure Element"),
    SOFTWARE("Software Keystore"),
    JVM_TEST_HARNESS("JVM Unit-Test Ephemeral Key (No AndroidKeyStore)"),
    UNKNOWN("Unknown / Unavailable")
}

data class KeystoreKeyReport(
    val baseAlias: String,
    val activeAlias: String,
    val purpose: String,
    val generation: Int,
    val securityLevel: KeySecurityLevel,
    val isUserAuthRequired: Boolean,
    val isInvalidated: Boolean,
    val createdAtMs: Long
)

data class AesGcmEnvelope(
    val keyAlias: String,
    val keyGeneration: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val aad: ByteArray
)

/**
 * Unified Enterprise Android Keystore Manager for AYRA AI.
 *
 * Security Guarantees:
 * - Separate cryptographic keys per security purpose (Secrets, DB KEK, File KEK, Prefs KEK,
 *   Biometric-bound vault key, E2EE Device Identity ECDSA P-256, Audit HMAC-SHA256).
 * - Prefers StrongBox on API 28+ with graceful TEE fallback.
 * - Verifies actual hardware backing via [KeyInfo] — never fabricates StrongBox/TEE claims.
 * - Fails CLOSED on real Android devices if AndroidKeyStore is present and key operations fail;
 *   synthesizes ephemeral test keys ONLY when AndroidKeyStore provider is absent (JVM tests).
 * - Supports AES-256-GCM with AAD binding, versioned key rotation, and invalidation recovery.
 */
object AyraKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val META_PREFS = "ayra_keystore_metadata_v1"

    // Purpose-separated key base aliases
    const val ALIAS_MASTER_SECRETS = "ayra_master_secrets_v2"
    const val ALIAS_DB_KEK = "ayra_vault_db_kek_v1"
    const val ALIAS_FILE_KEK = "ayra_vault_file_kek_v1"
    const val ALIAS_PREFS_KEK = "ayra_prefs_kek_v1"
    const val ALIAS_AUDIT_HMAC = "ayra_audit_hmac_v1"
    const val ALIAS_BIOMETRIC_VAULT = "ayra_biometric_vault_key_v1"
    const val ALIAS_E2EE_IDENTITY = "ayra_e2ee_identity_ecdsa_v1"

    private val purposeDescriptions = mapOf(
        ALIAS_MASTER_SECRETS to "API Keys & Credentials Encryption",
        ALIAS_DB_KEK to "SQLCipher Database KEK",
        ALIAS_FILE_KEK to "Encrypted Vault File KEK",
        ALIAS_PREFS_KEK to "Sensitive Preferences Encryption",
        ALIAS_AUDIT_HMAC to "Tamper-Evident Audit Log HMAC",
        ALIAS_BIOMETRIC_VAULT to "Biometric / Credential-Bound Vault Key",
        ALIAS_E2EE_IDENTITY to "E2EE Device Identity Signing (ECDSA P-256)"
    )

    private val secureRandom = SecureRandom()
    private val jvmTestAesKeys = ConcurrentHashMap<String, SecretKey>()
    private val jvmTestEcKeyPairs = ConcurrentHashMap<String, java.security.KeyPair>()
    private val keyGenerations = ConcurrentHashMap<String, Int>()
    private val invalidatedAliases = ConcurrentHashMap<String, Boolean>()

    fun isAndroidKeyStoreAvailable(): Boolean {
        return Security.getProvider(ANDROID_KEYSTORE) != null
    }

    fun getActiveAlias(context: Context?, baseAlias: String): String {
        val gen = getKeyGeneration(context, baseAlias)
        return if (gen <= 1) baseAlias else "${baseAlias}_gen_$gen"
    }

    fun getKeyGeneration(context: Context?, baseAlias: String): Int {
        keyGenerations[baseAlias]?.let { return it }
        val gen = if (context != null) {
            runCatching {
                context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                    .getInt("gen_$baseAlias", 1)
            }.getOrDefault(1)
        } else 1
        keyGenerations[baseAlias] = gen
        return gen
    }

    private fun saveKeyGeneration(context: Context?, baseAlias: String, gen: Int) {
        keyGenerations[baseAlias] = gen
        if (context != null) {
            runCatching {
                context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("gen_$baseAlias", gen)
                    .putLong("created_$baseAlias", System.currentTimeMillis())
                    .apply()
            }
        }
    }

    fun getOrCreateAesKey(
        context: Context? = null,
        baseAlias: String,
        generationOverride: Int? = null
    ): SecretKey {
        val targetAlias = if (generationOverride != null) {
            if (generationOverride <= 1) baseAlias else "${baseAlias}_gen_$generationOverride"
        } else {
            getActiveAlias(context, baseAlias)
        }

        if (!isAndroidKeyStoreAvailable()) {
            return jvmTestAesKeys.getOrPut(targetAlias) {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256, secureRandom)
                kg.generateKey()
            }
        }

        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(targetAlias)) {
            val existing = ks.getKey(targetAlias, null) as? SecretKey
            if (existing != null) return existing
        }

        return generateAndroidKeyStoreAesKey(
            alias = targetAlias,
            requireUserAuth = (baseAlias == ALIAS_BIOMETRIC_VAULT),
            preferStrongBox = (baseAlias == ALIAS_DB_KEK || baseAlias == ALIAS_FILE_KEK || baseAlias == ALIAS_MASTER_SECRETS || baseAlias == ALIAS_PREFS_KEK)
        )
    }

    private fun generateAndroidKeyStoreAesKey(
        alias: String,
        requireUserAuth: Boolean,
        preferStrongBox: Boolean
    ): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        fun buildSpec(useStrongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)

            if (requireUserAuth) {
                builder.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setInvalidatedByBiometricEnrollment(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Per-unlock cryptographic proof (-1s requires CryptoObject auth per use)
                    builder.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(30)
                }
            }

            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGen.init(buildSpec(useStrongBox = true))
                return keyGen.generateKey()
            } catch (_: Exception) {
                // StrongBox hardware not present or unavailable on this device — fall back to TEE
            }
        }

        keyGen.init(buildSpec(useStrongBox = false))
        return keyGen.generateKey()
    }

    fun getOrCreateHmacKey(context: Context? = null, baseAlias: String = ALIAS_AUDIT_HMAC): SecretKey {
        val targetAlias = getActiveAlias(context, baseAlias)
        if (!isAndroidKeyStoreAvailable()) {
            return jvmTestAesKeys.getOrPut(targetAlias) {
                val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
                SecretKeySpec(bytes, "HmacSHA256")
            }
        }

        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(targetAlias)) {
            val existing = ks.getKey(targetAlias, null) as? SecretKey
            if (existing != null) return existing
        }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(targetAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    fun computeHmacSha256(context: Context?, data: ByteArray, baseAlias: String = ALIAS_AUDIT_HMAC): ByteArray {
        val key = getOrCreateHmacKey(context, baseAlias)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }

    fun getOrCreateDeviceIdentityKeyPair(
        context: Context? = null,
        baseAlias: String = ALIAS_E2EE_IDENTITY
    ): Pair<PublicKey, PrivateKey> {
        val alias = getActiveAlias(context, baseAlias)
        if (!isAndroidKeyStoreAvailable()) {
            val kp = jvmTestEcKeyPairs.getOrPut(alias) {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
                kpg.generateKeyPair()
            }
            return kp.public to kp.private
        }

        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) {
            val priv = ks.getKey(alias, null) as? PrivateKey
            val pub = ks.getCertificate(alias)?.publicKey
            if (priv != null && pub != null) return pub to priv
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .build()
        kpg.initialize(spec)
        val kp = kpg.generateKeyPair()
        return kp.public to kp.private
    }

    fun signWithDeviceIdentity(context: Context?, payload: ByteArray): ByteArray {
        val (_, priv) = getOrCreateDeviceIdentityKeyPair(context)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(priv)
        sig.update(payload)
        return sig.sign()
    }

    fun verifySignature(publicKey: PublicKey, payload: ByteArray, signatureBytes: ByteArray): Boolean {
        return runCatching {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(payload)
            sig.verify(signatureBytes)
        }.getOrDefault(false)
    }

    fun encryptAesGcm(
        context: Context?,
        baseAlias: String,
        plaintext: ByteArray,
        aad: ByteArray
    ): AesGcmEnvelope {
        val gen = getKeyGeneration(context, baseAlias)
        val key = getOrCreateAesKey(context, baseAlias, gen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (!isAndroidKeyStoreAvailable()) {
            val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return AesGcmEnvelope(
            keyAlias = baseAlias,
            keyGeneration = gen,
            iv = iv,
            ciphertext = ciphertext,
            aad = aad
        )
    }

    fun decryptAesGcm(
        context: Context?,
        baseAlias: String,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
        keyGeneration: Int? = null
    ): ByteArray {
        val gen = keyGeneration ?: getKeyGeneration(context, baseAlias)
        val key = getOrCreateAesKey(context, baseAlias, gen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    fun encryptStringWithAad(
        context: Context?,
        baseAlias: String,
        plaintext: String,
        aadContext: String
    ): String {
        val aadBytes = aadContext.toByteArray(StandardCharsets.UTF_8)
        val env = encryptAesGcm(
            context = context,
            baseAlias = baseAlias,
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            aad = aadBytes
        )
        return JSONObject().apply {
            put("v", 2)
            put("gen", env.keyGeneration)
            put("iv", encodeB64(env.iv))
            put("ct", encodeB64(env.ciphertext))
        }.toString()
    }

    fun decryptStringWithAad(
        context: Context?,
        baseAlias: String,
        serializedEnvelope: String,
        aadContext: String
    ): String {
        val obj = JSONObject(serializedEnvelope)
        val gen = obj.optInt("gen", 1)
        val iv = decodeB64(obj.getString("iv"))
        val ct = decodeB64(obj.getString("ct"))
        val aadBytes = aadContext.toByteArray(StandardCharsets.UTF_8)
        val plain = decryptAesGcm(
            context = context,
            baseAlias = baseAlias,
            iv = iv,
            ciphertext = ct,
            aad = aadBytes,
            keyGeneration = gen
        )
        return String(plain, StandardCharsets.UTF_8)
    }

    /**
     * Initializes an encryption [Cipher] backed by [ALIAS_BIOMETRIC_VAULT] for
     * [androidx.biometric.BiometricPrompt.CryptoObject].
     * Throws [KeyPermanentlyInvalidatedException] if biometrics changed since key creation.
     */
    fun initBiometricEncryptCipher(context: Context?): Cipher {
        val key = getOrCreateAesKey(context, ALIAS_BIOMETRIC_VAULT)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidatedAliases[ALIAS_BIOMETRIC_VAULT] = true
            throw e
        } catch (e: UserNotAuthenticatedException) {
            throw e
        }
    }

    /**
     * Resets an invalidated biometric key (e.g., after fingerprint re-enrollment) cleanly.
     */
    fun resetBiometricBoundKey(context: Context?): Boolean {
        return runCatching {
            val alias = getActiveAlias(context, ALIAS_BIOMETRIC_VAULT)
            deleteAlias(alias)
            invalidatedAliases.remove(ALIAS_BIOMETRIC_VAULT)
            getOrCreateAesKey(context, ALIAS_BIOMETRIC_VAULT)
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.KEYSTORE,
                severity = SecurityAuditSeverity.WARN,
                message = "Biometric-bound vault key reset after hardware invalidation"
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Rotates the active key generation for a given purpose-specific [baseAlias].
     * Previous key generations remain accessible until existing wrapped DEKs are re-wrapped,
     * unless [deletePreviousGeneration] is true.
     */
    fun rotatePurposeKey(
        context: Context?,
        baseAlias: String,
        deletePreviousGeneration: Boolean = false
    ): Int {
        val currentGen = getKeyGeneration(context, baseAlias)
        val nextGen = currentGen + 1
        saveKeyGeneration(context, baseAlias, nextGen)
        if (baseAlias == ALIAS_E2EE_IDENTITY) {
            getOrCreateDeviceIdentityKeyPair(context, baseAlias)
        } else if (baseAlias == ALIAS_AUDIT_HMAC) {
            getOrCreateHmacKey(context, baseAlias)
        } else {
            getOrCreateAesKey(context, baseAlias, nextGen)
        }
        if (deletePreviousGeneration) {
            val oldAlias = if (currentGen <= 1) baseAlias else "${baseAlias}_gen_$currentGen"
            deleteAlias(oldAlias)
        }
        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.KEYSTORE,
            severity = SecurityAuditSeverity.INFO,
            message = "Rotated Keystore key for $baseAlias -> gen $nextGen"
        )
        return nextGen
    }

    /**
     * Inspects actual Android Keystore hardware properties using [KeyInfo].
     * Never misreports software or test keys as StrongBox/TEE.
     */
    fun inspectKeySecurity(context: Context?, baseAlias: String): KeystoreKeyReport {
        val gen = getKeyGeneration(context, baseAlias)
        val activeAlias = getActiveAlias(context, baseAlias)
        val createdAt = context?.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            ?.getLong("created_$baseAlias", 0L) ?: 0L

        if (!isAndroidKeyStoreAvailable()) {
            return KeystoreKeyReport(
                baseAlias = baseAlias,
                activeAlias = activeAlias,
                purpose = purposeDescriptions[baseAlias] ?: "Purpose Key",
                generation = gen,
                securityLevel = KeySecurityLevel.JVM_TEST_HARNESS,
                isUserAuthRequired = (baseAlias == ALIAS_BIOMETRIC_VAULT),
                isInvalidated = invalidatedAliases[baseAlias] == true,
                createdAtMs = createdAt
            )
        }

        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(activeAlias)) {
                // Create on demand only for non-biometric keys or inspect cleanly
                if (baseAlias == ALIAS_E2EE_IDENTITY) {
                    getOrCreateDeviceIdentityKeyPair(context, baseAlias)
                } else if (baseAlias == ALIAS_AUDIT_HMAC) {
                    getOrCreateHmacKey(context, baseAlias)
                } else if (baseAlias != ALIAS_BIOMETRIC_VAULT) {
                    getOrCreateAesKey(context, baseAlias, gen)
                }
            }

            if (baseAlias == ALIAS_E2EE_IDENTITY) {
                val priv = ks.getKey(activeAlias, null) as? PrivateKey
                if (priv == null) {
                    return KeystoreKeyReport(
                        baseAlias, activeAlias, purposeDescriptions[baseAlias] ?: "", gen,
                        KeySecurityLevel.UNKNOWN, false, false, createdAt
                    )
                }
                val factory = KeyFactory.getInstance(priv.algorithm, ANDROID_KEYSTORE)
                val info = factory.getKeySpec(priv, KeyInfo::class.java)
                return KeystoreKeyReport(
                    baseAlias = baseAlias,
                    activeAlias = activeAlias,
                    purpose = purposeDescriptions[baseAlias] ?: "",
                    generation = gen,
                    securityLevel = mapKeyInfoLevel(info),
                    isUserAuthRequired = info.isUserAuthenticationRequired,
                    isInvalidated = invalidatedAliases[baseAlias] == true,
                    createdAtMs = createdAt
                )
            }

            val secret = ks.getKey(activeAlias, null) as? SecretKey
            if (secret == null) {
                return KeystoreKeyReport(
                    baseAlias, activeAlias, purposeDescriptions[baseAlias] ?: "", gen,
                    KeySecurityLevel.UNKNOWN, baseAlias == ALIAS_BIOMETRIC_VAULT,
                    invalidatedAliases[baseAlias] == true, createdAt
                )
            }
            val factory = SecretKeyFactory.getInstance(secret.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(secret, KeyInfo::class.java) as KeyInfo
            KeystoreKeyReport(
                baseAlias = baseAlias,
                activeAlias = activeAlias,
                purpose = purposeDescriptions[baseAlias] ?: "",
                generation = gen,
                securityLevel = mapKeyInfoLevel(info),
                isUserAuthRequired = info.isUserAuthenticationRequired,
                isInvalidated = invalidatedAliases[baseAlias] == true,
                createdAtMs = createdAt
            )
        } catch (_: Exception) {
            KeystoreKeyReport(
                baseAlias = baseAlias,
                activeAlias = activeAlias,
                purpose = purposeDescriptions[baseAlias] ?: "",
                generation = gen,
                securityLevel = KeySecurityLevel.UNKNOWN,
                isUserAuthRequired = (baseAlias == ALIAS_BIOMETRIC_VAULT),
                isInvalidated = invalidatedAliases[baseAlias] == true,
                createdAtMs = createdAt
            )
        }
    }

    fun inspectAllKeys(context: Context?): List<KeystoreKeyReport> {
        return listOf(
            ALIAS_MASTER_SECRETS,
            ALIAS_DB_KEK,
            ALIAS_FILE_KEK,
            ALIAS_PREFS_KEK,
            ALIAS_BIOMETRIC_VAULT,
            ALIAS_E2EE_IDENTITY,
            ALIAS_AUDIT_HMAC
        ).map { inspectKeySecurity(context, it) }
    }

    private fun mapKeyInfoLevel(info: KeyInfo): KeySecurityLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.HARDWARE_STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.HARDWARE_TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
                else -> KeySecurityLevel.UNKNOWN
            }
        }
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) {
            KeySecurityLevel.HARDWARE_TEE
        } else {
            KeySecurityLevel.SOFTWARE
        }
    }

    fun deleteAlias(alias: String) {
        jvmTestAesKeys.remove(alias)
        jvmTestEcKeyPairs.remove(alias)
        if (!isAndroidKeyStoreAvailable()) return
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
            }
        }
    }

    /**
     * Cryptographic Key Erasure: Destroys every AYRA Keystore alias (and all historical generations)
     * plus key-generation metadata so encrypted data becomes permanently unrecoverable.
     */
    fun destroyAllAyraKeys(context: Context?) {
        val allBaseAliases = listOf(
            ALIAS_MASTER_SECRETS,
            ALIAS_DB_KEK,
            ALIAS_FILE_KEK,
            ALIAS_PREFS_KEK,
            ALIAS_AUDIT_HMAC,
            ALIAS_BIOMETRIC_VAULT,
            ALIAS_E2EE_IDENTITY,
            "ayra_ai_master_aes_key",
            "ayra_encrypted_prefs_master_key"
        )
        if (isAndroidKeyStoreAvailable()) {
            runCatching {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                ks.aliases().toList().filter { it.startsWith("ayra_") }.forEach { alias ->
                    runCatching { ks.deleteEntry(alias) }
                }
            }
        }
        allBaseAliases.forEach { base ->
            val maxGen = getKeyGeneration(context, base)
            for (g in 1..maxGen) {
                val a = if (g == 1) base else "${base}_gen_$g"
                deleteAlias(a)
            }
        }
        jvmTestAesKeys.clear()
        jvmTestEcKeyPairs.clear()
        keyGenerations.clear()
        invalidatedAliases.clear()
        if (context != null) {
            runCatching {
                context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    internal fun encodeB64(bytes: ByteArray): String {
        return runCatching {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull() ?: java.util.Base64.getEncoder().encodeToString(bytes)
    }

    internal fun decodeB64(text: String): ByteArray {
        return runCatching {
            Base64.decode(text, Base64.NO_WRAP)
        }.getOrNull() ?: java.util.Base64.getDecoder().decode(text)
    }
}
