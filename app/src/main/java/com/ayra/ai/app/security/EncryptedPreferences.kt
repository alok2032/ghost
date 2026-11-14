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
 * EncryptedPreferences manager using [AyraKeyManager] AES-256-GCM with AAD binding
 * to securely store AI service credentials, sensitive preferences, and usage metrics.
 *
 * Security guarantees:
 * 1. Purpose-separated Keystore key ([AyraKeyManager.ALIAS_PREFS_KEK] or [AyraKeyManager.ALIAS_MASTER_SECRETS]).
 * 2. 128-bit GCM authentication tag + AAD binding key name and schema version.
 * 3. Fails closed on Android devices — never writes or reads unencrypted fallbacks.
 * 4. Transparently migrates legacy ciphertexts encrypted under `ayra_encrypted_prefs_master_key`.
 */
class EncryptedPreferences(
    private val context: Context,
    private val prefsFileName: String = PREFS_FILE_NAME,
    private val keyAlias: String = AyraKeyManager.ALIAS_MASTER_SECRETS
) {

    companion object {
        private const val LEGACY_ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_FILE_NAME = "ayra_encrypted_preferences"
        private const val LEGACY_MASTER_KEY_ALIAS = "ayra_encrypted_prefs_master_key"
        private const val GCM_TAG_LENGTH = 128

        // Canonical Key Constants for all AI Providers
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_NVIDIA = "nvidia_api_key"
        const val KEY_NVIDIA_MINIMAX = "nvidia_minimax_m31_api_key"
        const val KEY_NVIDIA_GLM = "nvidia_glm_53_api_key"
        const val KEY_NVIDIA_MUSE = "nvidia_muse_30b_api_key"
        const val KEY_NVIDIA_GEMMA = "nvidia_gemma4_31b_api_key"
        const val KEY_OPENAI = "openai_api_key"
        const val KEY_ANTHROPIC = "anthropic_api_key"
        const val KEY_DEEPSEEK = "deepseek_api_key"
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_XAI = "xai_api_key"
        const val KEY_MISTRAL = "mistral_api_key"
        const val KEY_PERPLEXITY = "perplexity_api_key"
        const val KEY_LOCAL_OLLAMA_URL = "local_ollama_url"
        const val KEY_CUSTOM_ENDPOINT_URL = "custom_endpoint_url"
        const val KEY_CUSTOM_ENDPOINT_KEY = "custom_endpoint_key"
        const val KEY_TAVILY = "tavily_api_key"
        const val KEY_ELEVENLABS = "elevenlabs_api_key"
        const val KEY_GROQ = "groq_api_key"
        const val KEY_HUGGINGFACE = "huggingface_api_key"
        const val KEY_GITHUB_MODELS = "github_models_api_key"
        const val KEY_CEREBRAS = "cerebras_api_key"
        const val KEY_SAMBANOVA = "sambanova_api_key"
        const val KEY_COMFYUI_URL = "comfyui_server_url"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
    }

    private fun aadFor(key: String): String = "$prefsFileName|$keyAlias|$key|v2"

    fun putString(key: String, value: String): Boolean {
        val cleanValue = value.trim()
        if (cleanValue.isEmpty()) {
            remove(key)
            return true
        }
        return try {
            val env = AyraKeyManager.encryptStringWithAad(
                context = context,
                baseAlias = keyAlias,
                plaintext = cleanValue,
                aadContext = aadFor(key)
            )
            sharedPreferences.edit()
                .putString("${key}_env_v2", env)
                .remove("${key}_iv")
                .remove("${key}_data")
                .apply()
            true
        } catch (e: Exception) {
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.KEYSTORE,
                severity = SecurityAuditSeverity.CRITICAL,
                message = "EncryptedPreferences write failed for '$key': ${e.javaClass.simpleName}"
            )
            false
        }
    }

    fun getString(key: String, defaultValue: String = ""): String {
        val envV2 = sharedPreferences.getString("${key}_env_v2", null)
        if (!envV2.isNullOrBlank()) {
            return try {
                val decrypted = AyraKeyManager.decryptStringWithAad(
                    context = context,
                    baseAlias = keyAlias,
                    serializedEnvelope = envV2,
                    aadContext = aadFor(key)
                ).trim()

                val activeGen = AyraKeyManager.getKeyGeneration(context, keyAlias)
                val envelopeGen = runCatching { JSONObject(envV2).optInt("gen", 1) }.getOrDefault(1)
                if (envelopeGen < activeGen && decrypted.isNotEmpty()) {
                    putString(key, decrypted)
                }
                decrypted
            } catch (_: Exception) {
                defaultValue
            }
        }

        // Migrate legacy format if present
        val encodedIv = sharedPreferences.getString("${key}_iv", null)
        val encodedData = sharedPreferences.getString("${key}_data", null)
        if (!encodedIv.isNullOrBlank() && !encodedData.isNullOrBlank()) {
            val migrated = decryptLegacy(encodedIv, encodedData)?.trim()
            if (!migrated.isNullOrEmpty()) {
                putString(key, migrated)
                return migrated
            }
        }
        return defaultValue
    }

    fun putBoolean(key: String, value: Boolean): Boolean =
        putString(key, if (value) "1" else "0")

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val v = getString(key, "")
        return when (v) {
            "1", "true" -> true
            "0", "false" -> false
            else -> defaultValue
        }
    }

    fun putLong(key: String, value: Long): Boolean =
        putString(key, value.toString())

    fun getLong(key: String, defaultValue: Long): Long =
        getString(key, "").toLongOrNull() ?: defaultValue

    fun contains(key: String): Boolean {
        return getString(key).isNotBlank()
    }

    fun remove(key: String) {
        sharedPreferences.edit()
            .remove("${key}_env_v2")
            .remove("${key}_iv")
            .remove("${key}_data")
            .apply()
    }

    fun clear() {
        sharedPreferences.edit().clear().commit()
    }

    private fun decryptLegacy(encodedIv: String, encodedData: String): String? {
        if (!AyraKeyManager.isAndroidKeyStoreAvailable()) return null
        return try {
            val ks = KeyStore.getInstance(LEGACY_ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(LEGACY_MASTER_KEY_ALIAS)) return null
            val key = ks.getKey(LEGACY_MASTER_KEY_ALIAS, null) as? SecretKey ?: return null
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ct = Base64.decode(encodedData, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
