package com.ayra.ai.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed encrypted key vault for Ayra AI.
 * Delegates all cryptographic operations to [AyraKeyManager] ([AyraKeyManager.ALIAS_MASTER_SECRETS])
 * with AES-256-GCM + per-key AAD authentication, purpose separation, and key rotation support.
 *
 * Adheres strictly to Fail-Closed policy:
 * - Never stores plaintext or unencrypted Base64 fallbacks.
 * - Automatically migrates legacy v1 ciphertexts encrypted under `ayra_ai_master_aes_key` (no AAD)
 *   to v2 AAD-bound envelopes under [AyraKeyManager.ALIAS_MASTER_SECRETS] and deletes legacy entries.
 */
object SecureKeyStore {

    private const val LEGACY_ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val LEGACY_MASTER_KEY_ALIAS = "ayra_ai_master_aes_key"
    private const val PREFS_FILE = "ayra_secure_vault"
    private const val GCM_TAG_LENGTH = 128

    // Standard Provider Key Constants
    const val KEY_GEMINI = "gemini_api_key"
    const val KEY_OPENAI = "openai_api_key"
    const val KEY_ANTHROPIC = "anthropic_api_key"
    const val KEY_DEEPSEEK = "deepseek_api_key"
    const val KEY_XAI = "xai_api_key"
    const val KEY_MISTRAL = "mistral_api_key"
    const val KEY_OPENROUTER = "openrouter_api_key"
    const val KEY_PERPLEXITY = "perplexity_api_key"
    const val KEY_NVIDIA = "nvidia_api_key"
    const val KEY_NVIDIA_MINIMAX = "nvidia_minimax_m31_api_key"
    const val KEY_NVIDIA_GLM = "nvidia_glm_53_api_key"
    const val KEY_NVIDIA_MUSE = "nvidia_muse_30b_api_key"
    const val KEY_NVIDIA_GEMMA = "nvidia_gemma4_31b_api_key"
    const val KEY_CUSTOM_ENDPOINT_URL = "custom_endpoint_url"
    const val KEY_CUSTOM_ENDPOINT_KEY = "custom_endpoint_key"
    const val KEY_TAVILY_SEARCH = "tavily_search_api_key"
    const val KEY_FAL_REPLICATE = "fal_replicate_api_key"
    const val KEY_ELEVENLABS = "elevenlabs_api_key"
    const val KEY_GROQ = "groq_api_key"
    const val KEY_HUGGINGFACE = "huggingface_api_key"
    const val KEY_GITHUB_MODELS = "github_models_api_key"
    const val KEY_CEREBRAS = "cerebras_api_key"
    const val KEY_SAMBANOVA = "sambanova_api_key"
    const val KEY_COMFYUI_URL = "comfyui_server_url"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private fun aadForKey(keyName: String): String = "secure_vault_v2|$keyName"

    fun putSecret(context: Context, keyName: String, value: String): Boolean {
        val cleanValue = value.trim()
        if (cleanValue.isEmpty()) {
            removeSecret(context, keyName)
            return true
        }

        val prefs = getPrefs(context)
        return try {
            val serializedEnvelope = AyraKeyManager.encryptStringWithAad(
                context = context,
                baseAlias = AyraKeyManager.ALIAS_MASTER_SECRETS,
                plaintext = cleanValue,
                aadContext = aadForKey(keyName)
            )
            prefs.edit()
                .putString("${keyName}_env_v2", serializedEnvelope)
                .remove("${keyName}_iv")
                .remove("${keyName}_data")
                .remove("${keyName}_raw")
                .apply()
            true
        } catch (e: Exception) {
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.KEYSTORE,
                severity = SecurityAuditSeverity.CRITICAL,
                message = "Failed encrypting secret '$keyName': ${e.javaClass.simpleName}"
            )
            false
        }
    }

    fun getSecret(context: Context, keyName: String): String {
        val prefs = getPrefs(context)

        // 1. Preferred v2 AAD-bound envelope format
        val envV2 = prefs.getString("${keyName}_env_v2", null)
        if (!envV2.isNullOrBlank()) {
            return try {
                val decrypted = AyraKeyManager.decryptStringWithAad(
                    context = context,
                    baseAlias = AyraKeyManager.ALIAS_MASTER_SECRETS,
                    serializedEnvelope = envV2,
                    aadContext = aadForKey(keyName)
                ).trim()

                // Re-encrypt cleanly if key generation rotated
                val activeGen = AyraKeyManager.getKeyGeneration(context, AyraKeyManager.ALIAS_MASTER_SECRETS)
                val envelopeGen = runCatching { JSONObject(envV2).optInt("gen", 1) }.getOrDefault(1)
                if (envelopeGen < activeGen && decrypted.isNotEmpty()) {
                    putSecret(context, keyName, decrypted)
                }
                decrypted
            } catch (e: Exception) {
                SecurityAuditLogger.log(
                    context = context,
                    category = SecurityAuditCategory.KEYSTORE,
                    severity = SecurityAuditSeverity.CRITICAL,
                    message = "Decryption failed for '$keyName': ${e.javaClass.simpleName}"
                )
                ""
            }
        }

        // 2. Legacy v1 migration path (only decrypts if legacy AndroidKeyStore alias exists)
        val encodedIv = prefs.getString("${keyName}_iv", null)
        val encodedCipher = prefs.getString("${keyName}_data", null)
        if (!encodedIv.isNullOrBlank() && !encodedCipher.isNullOrBlank()) {
            val migrated = decryptLegacySecret(encodedIv, encodedCipher)?.trim().orEmpty()
            if (migrated.isNotEmpty()) {
                putSecret(context, keyName, migrated)
                return migrated
            }
        }

        // Ensure no stray plaintext raw entry ever persists
        if (prefs.contains("${keyName}_raw")) {
            prefs.edit().remove("${keyName}_raw").apply()
        }
        return ""
    }

    private fun decryptLegacySecret(encodedIv: String, encodedCipher: String): String? {
        if (!AyraKeyManager.isAndroidKeyStoreAvailable()) return null
        return try {
            val ks = KeyStore.getInstance(LEGACY_ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(LEGACY_MASTER_KEY_ALIAS)) return null
            val legacyKey = ks.getKey(LEGACY_MASTER_KEY_ALIAS, null) as? SecretKey ?: return null
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val cipherText = Base64.decode(encodedCipher, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun hasSecret(context: Context, keyName: String): Boolean {
        return getSecret(context, keyName).isNotBlank()
    }

    fun removeSecret(context: Context, keyName: String) {
        getPrefs(context).edit()
            .remove("${keyName}_env_v2")
            .remove("${keyName}_iv")
            .remove("${keyName}_data")
            .remove("${keyName}_raw")
            .apply()
    }

    fun clearAllSecrets(context: Context) {
        getPrefs(context).edit().clear().commit()
    }

    /**
     * In-memory/AAD-bound AES-GCM helper for unit tests and ephemeral envelopes.
     */
    fun encrypt(alias: String, plainText: String): String {
        return AyraKeyManager.encryptStringWithAad(
            context = null,
            baseAlias = AyraKeyManager.ALIAS_MASTER_SECRETS,
            plaintext = plainText,
            aadContext = "direct|$alias"
        )
    }

    fun decrypt(alias: String, cipherPayload: String): String {
        return AyraKeyManager.decryptStringWithAad(
            context = null,
            baseAlias = AyraKeyManager.ALIAS_MASTER_SECRETS,
            serializedEnvelope = cipherPayload,
            aadContext = "direct|$alias"
        )
    }
}
