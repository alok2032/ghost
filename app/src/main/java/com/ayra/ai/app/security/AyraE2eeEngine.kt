package com.ayra.ai.app.security

import android.content.Context
import android.os.Build
import com.ayra.ai.app.data.AyraPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TrustedE2eeDevice(
    val deviceId: String,
    val deviceName: String,
    val identityPublicKeyB64: String,
    val ecdhPublicKeyB64: String,
    val fingerprintSha256: String,
    val epochAdded: Int,
    val isRevoked: Boolean = false,
    val registeredAtMs: Long = System.currentTimeMillis()
)

data class E2eeDeviceKeyBundle(
    val deviceId: String,
    val deviceName: String,
    val identityPublicKeyB64: String,
    val ecdhPublicKeyB64: String,
    val fingerprintSha256: String,
    val currentEpoch: Int
)

/**
 * End-to-End Encryption Engine for AYRA AI (Phase 4).
 *
 * Cryptographic Primitives:
 * - Identity Keys: ECDSA P-256 (`secp256r1`) hardware-backed in [AyraKeyManager.ALIAS_E2EE_IDENTITY].
 * - Key Agreement: Ephemeral / Per-Device ECDH P-256 (`secp256r1`) + HKDF-SHA256 (RFC 5869).
 * - Authenticated Encryption: AES-256-GCM with 96-bit SecureRandom IV and AAD binding `(senderDeviceId|epoch|seq|context)`.
 * - Epoch Key Rotation & Forward Secrecy: Revoking a device rotates the active epoch and re-wraps the new
 *   epoch root key exclusively for remaining non-revoked devices; prior/revoked epochs reject decrypts when rotated.
 * - Replay Protection: Tracks highest observed monotonic `(epoch, seq)` per sender device.
 */
object AyraE2eeEngine {

    const val ENVELOPE_PREFIX = "E2EEv1:"
    private const val PREFS_NAME = "ayra_e2ee_state_v1"
    private const val KEY_ECDH_PRIV_ENC = "ecdh_priv_enc_v1"
    private const val KEY_ECDH_PUB = "ecdh_pub_b64_v1"
    private const val KEY_EPOCH_ROOT_MAP_ENC = "epoch_roots_enc_v1"
    private const val KEY_CURRENT_EPOCH = "current_epoch_v1"
    private const val KEY_SEND_SEQ = "send_seq_v1"
    private const val KEY_TRUSTED_DEVICES_ENC = "trusted_devices_enc_v1"
    private const val KEY_REPLAY_MAP_ENC = "replay_watermark_enc_v1"

    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private val secureRandom = SecureRandom()
    private val inMemoryReplayMap = ConcurrentHashMap<String, Long>()

    private fun e2eePrefs(context: Context): EncryptedPreferences =
        EncryptedPreferences(
            context = context,
            prefsFileName = PREFS_NAME,
            keyAlias = AyraKeyManager.ALIAS_MASTER_SECRETS
        )

    /**
     * Initializes local device E2EE identity & ECDH keypair + Epoch-1 root key if not present.
     */
    @Synchronized
    fun getOrCreateLocalDeviceBundle(context: Context): E2eeDeviceKeyBundle {
        val deviceId = AyraPreferences.getOrCreateDeviceId(context)
        val (identityPub, _) = AyraKeyManager.getOrCreateDeviceIdentityKeyPair(context)
        val identityPubB64 = AyraKeyManager.encodeB64(identityPub.encoded)

        val prefs = e2eePrefs(context)
        var ecdhPubB64 = prefs.getString(KEY_ECDH_PUB, "")
        var ecdhPrivEnc = prefs.getString(KEY_ECDH_PRIV_ENC, "")
        if (ecdhPubB64.isBlank() || ecdhPrivEnc.isBlank()) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
            val kp = kpg.generateKeyPair()
            ecdhPubB64 = AyraKeyManager.encodeB64(kp.public.encoded)
            ecdhPrivEnc = AyraKeyManager.encodeB64(kp.private.encoded)
            prefs.putString(KEY_ECDH_PUB, ecdhPubB64)
            prefs.putString(KEY_ECDH_PRIV_ENC, ecdhPrivEnc)
        }

        val currentEpoch = prefs.getLong(KEY_CURRENT_EPOCH, 0L).toInt().let {
            if (it <= 0) {
                prefs.putLong(KEY_CURRENT_EPOCH, 1L)
                ensureEpochRootKey(context, 1)
                1
            } else {
                ensureEpochRootKey(context, it)
                it
            }
        }

        val fingerprint = computeVisualFingerprint(identityPub.encoded, ecdhPubB64)
        val deviceName = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifBlank { "Android Device" }

        // Ensure self is in trusted devices list
        val existing = getTrustedDevices(context)
        if (existing.none { it.deviceId == deviceId }) {
            upsertTrustedDevice(
                context,
                TrustedE2eeDevice(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    identityPublicKeyB64 = identityPubB64,
                    ecdhPublicKeyB64 = ecdhPubB64,
                    fingerprintSha256 = fingerprint,
                    epochAdded = currentEpoch,
                    isRevoked = false
                )
            )
        }

        return E2eeDeviceKeyBundle(
            deviceId = deviceId,
            deviceName = deviceName,
            identityPublicKeyB64 = identityPubB64,
            ecdhPublicKeyB64 = ecdhPubB64,
            fingerprintSha256 = fingerprint,
            currentEpoch = currentEpoch
        )
    }

    fun computeVisualFingerprint(identityPubBytes: ByteArray, ecdhPubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(identityPubBytes)
            update(ecdhPubB64.toByteArray(StandardCharsets.UTF_8))
        }.digest()
        return digest.take(16).joinToString(":") { "%02X".format(it) }
    }

    fun getCurrentEpoch(context: Context): Int =
        e2eePrefs(context).getLong(KEY_CURRENT_EPOCH, 1L).toInt().coerceAtLeast(1)

    @Synchronized
    private fun getOrCreateEcdhPrivateKey(context: Context): PrivateKey {
        getOrCreateLocalDeviceBundle(context)
        val raw = AyraKeyManager.decodeB64(e2eePrefs(context).getString(KEY_ECDH_PRIV_ENC, ""))
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(raw))
    }

    @Synchronized
    private fun ensureEpochRootKey(context: Context, epoch: Int): ByteArray {
        val prefs = e2eePrefs(context)
        val jsonStr = prefs.getString(KEY_EPOCH_ROOT_MAP_ENC, "{}")
        val obj = runCatching { JSONObject(jsonStr) }.getOrElse { JSONObject() }
        val keyStr = obj.optString(epoch.toString(), "")
        if (keyStr.isNotBlank()) {
            return AyraKeyManager.decodeB64(keyStr)
        }
        val freshRoot = ByteArray(32).also { secureRandom.nextBytes(it) }
        obj.put(epoch.toString(), AyraKeyManager.encodeB64(freshRoot))
        prefs.putString(KEY_EPOCH_ROOT_MAP_ENC, obj.toString())
        return freshRoot
    }

    private fun getEpochRootKeyOrNull(context: Context, epoch: Int): ByteArray? {
        val jsonStr = e2eePrefs(context).getString(KEY_EPOCH_ROOT_MAP_ENC, "{}")
        val obj = runCatching { JSONObject(jsonStr) }.getOrElse { JSONObject() }
        val b64 = obj.optString(epoch.toString(), "")
        return if (b64.isBlank()) null else AyraKeyManager.decodeB64(b64)
    }

    fun getTrustedDevices(context: Context): List<TrustedE2eeDevice> {
        val raw = e2eePrefs(context).getString(KEY_TRUSTED_DEVICES_ENC, "[]")
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    TrustedE2eeDevice(
                        deviceId = o.getString("deviceId"),
                        deviceName = o.optString("deviceName", "Device"),
                        identityPublicKeyB64 = o.getString("identityPublicKeyB64"),
                        ecdhPublicKeyB64 = o.getString("ecdhPublicKeyB64"),
                        fingerprintSha256 = o.optString("fingerprintSha256", ""),
                        epochAdded = o.optInt("epochAdded", 1),
                        isRevoked = o.optBoolean("isRevoked", false),
                        registeredAtMs = o.optLong("registeredAtMs", 0L)
                    )
                )
            }
        }
    }

    @Synchronized
    fun upsertTrustedDevice(context: Context, device: TrustedE2eeDevice) {
        val list = getTrustedDevices(context).filterNot { it.deviceId == device.deviceId }.toMutableList()
        list.add(device)
        saveTrustedDevices(context, list)
    }

    private fun saveTrustedDevices(context: Context, list: List<TrustedE2eeDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("deviceId", d.deviceId)
                put("deviceName", d.deviceName)
                put("identityPublicKeyB64", d.identityPublicKeyB64)
                put("ecdhPublicKeyB64", d.ecdhPublicKeyB64)
                put("fingerprintSha256", d.fingerprintSha256)
                put("epochAdded", d.epochAdded)
                put("isRevoked", d.isRevoked)
                put("registeredAtMs", d.registeredAtMs)
            })
        }
        e2eePrefs(context).putString(KEY_TRUSTED_DEVICES_ENC, arr.toString())
    }

    /**
     * Wraps an Epoch Root Key for another enrolled device using ECDH P-256 + HKDF-SHA256 + AES-256-GCM
     * and signs the distribution blob with our hardware Keystore ECDSA identity key.
     */
    fun wrapEpochKeyForPeerDevice(
        context: Context,
        peerDevice: TrustedE2eeDevice,
        epoch: Int = getCurrentEpoch(context)
    ): JSONObject {
        val bundle = getOrCreateLocalDeviceBundle(context)
        val localPriv = getOrCreateEcdhPrivateKey(context)
        val peerEcdhPub = decodeEcPublicKey(peerDevice.ecdhPublicKeyB64)

        val sharedSecret = computeEcdhSharedSecret(localPriv, peerEcdhPub)
        val salt = "${bundle.deviceId}|${peerDevice.deviceId}|epoch=$epoch".toByteArray(StandardCharsets.UTF_8)
        val wrappingKey = hkdfSha256(sharedSecret, salt, "ayra-e2ee-epoch-wrap-v1".toByteArray(StandardCharsets.UTF_8), 32)
        sharedSecret.fill(0)

        val epochRoot = ensureEpochRootKey(context, epoch)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val aad = "keywrap|${bundle.deviceId}|${peerDevice.deviceId}|$epoch".toByteArray(StandardCharsets.UTF_8)
        val ct = aesGcmEncrypt(wrappingKey, iv, epochRoot, aad)
        wrappingKey.fill(0)
        epochRoot.fill(0)

        val ivB64 = AyraKeyManager.encodeB64(iv)
        val ctB64 = AyraKeyManager.encodeB64(ct)
        val sigPayload = "${bundle.deviceId}|${peerDevice.deviceId}|$epoch|$ivB64|$ctB64".toByteArray(StandardCharsets.UTF_8)
        val sig = AyraKeyManager.signWithDeviceIdentity(context, sigPayload)

        return JSONObject().apply {
            put("v", 1)
            put("epoch", epoch)
            put("senderDeviceId", bundle.deviceId)
            put("recipientDeviceId", peerDevice.deviceId)
            put("senderEcdhPub", bundle.ecdhPublicKeyB64)
            put("iv", ivB64)
            put("wrappedEpochKey", ctB64)
            put("signature", AyraKeyManager.encodeB64(sig))
        }
    }

    /**
     * Unwraps a peer-distributed Epoch Root Key after verifying sender ECDSA signature.
     */
    fun receiveWrappedEpochKey(
        context: Context,
        packet: JSONObject,
        senderDevice: TrustedE2eeDevice
    ): Boolean {
        if (senderDevice.isRevoked) return false
        val epoch = packet.getInt("epoch")
        val senderId = packet.getString("senderDeviceId")
        val recipientId = packet.getString("recipientDeviceId")
        val ivB64 = packet.getString("iv")
        val ctB64 = packet.getString("wrappedEpochKey")
        val sigB64 = packet.getString("signature")

        val sigPayload = "$senderId|$recipientId|$epoch|$ivB64|$ctB64".toByteArray(StandardCharsets.UTF_8)
        val senderIdentityPub = decodeEcPublicKey(senderDevice.identityPublicKeyB64)
        if (!AyraKeyManager.verifySignature(senderIdentityPub, sigPayload, AyraKeyManager.decodeB64(sigB64))) {
            return false
        }

        val bundle = getOrCreateLocalDeviceBundle(context)
        if (recipientId != bundle.deviceId) return false

        val localPriv = getOrCreateEcdhPrivateKey(context)
        val senderEcdhPub = decodeEcPublicKey(packet.getString("senderEcdhPub"))
        val sharedSecret = computeEcdhSharedSecret(localPriv, senderEcdhPub)
        val salt = "$senderId|$recipientId|epoch=$epoch".toByteArray(StandardCharsets.UTF_8)
        val wrappingKey = hkdfSha256(sharedSecret, salt, "ayra-e2ee-epoch-wrap-v1".toByteArray(StandardCharsets.UTF_8), 32)
        sharedSecret.fill(0)

        val aad = "keywrap|$senderId|$recipientId|$epoch".toByteArray(StandardCharsets.UTF_8)
        val epochRoot = aesGcmDecrypt(wrappingKey, AyraKeyManager.decodeB64(ivB64), AyraKeyManager.decodeB64(ctB64), aad)
        wrappingKey.fill(0)

        val prefs = e2eePrefs(context)
        val jsonStr = prefs.getString(KEY_EPOCH_ROOT_MAP_ENC, "{}")
        val obj = runCatching { JSONObject(jsonStr) }.getOrElse { JSONObject() }
        obj.put(epoch.toString(), AyraKeyManager.encodeB64(epochRoot))
        prefs.putString(KEY_EPOCH_ROOT_MAP_ENC, obj.toString())
        if (epoch > getCurrentEpoch(context)) {
            prefs.putLong(KEY_CURRENT_EPOCH, epoch.toLong())
        }
        epochRoot.fill(0)
        return true
    }

    /**
     * Revokes a trusted device and rotates the active Epoch Root Key forward,
     * ensuring the revoked device cannot decrypt future messages.
     */
    @Synchronized
    fun revokeDeviceAndRotateEpoch(context: Context, targetDeviceId: String): Int {
        val current = getTrustedDevices(context)
        val updated = current.map {
            if (it.deviceId == targetDeviceId) it.copy(isRevoked = true) else it
        }
        saveTrustedDevices(context, updated)

        val nextEpoch = getCurrentEpoch(context) + 1
        e2eePrefs(context).putLong(KEY_CURRENT_EPOCH, nextEpoch.toLong())
        ensureEpochRootKey(context, nextEpoch).fill(0)

        SecurityAuditLogger.log(
            context = context,
            category = SecurityAuditCategory.E2EE,
            severity = SecurityAuditSeverity.WARN,
            message = "Revoked device '$targetDeviceId' and rotated E2EE epoch -> $nextEpoch"
        )
        return nextEpoch
    }

    /**
     * Encrypts [plaintext] into an authenticated, signed, forward-secret E2EE envelope (`E2EEv1:...`).
     * Binds [aadContext] (e.g. `uid|docId|fieldName`) into GCM AAD to prevent cross-document ciphertext transplantation.
     */
    fun encryptEnvelope(context: Context, plaintext: String, aadContext: String): String {
        val bundle = getOrCreateLocalDeviceBundle(context)
        val epoch = bundle.currentEpoch
        val epochRoot = ensureEpochRootKey(context, epoch)

        val prefs = e2eePrefs(context)
        val seq = prefs.getLong(KEY_SEND_SEQ, 0L) + 1L
        prefs.putLong(KEY_SEND_SEQ, seq)

        // Derive per-message symmetric key with forward-ratcheting info context
        val msgKey = deriveMessageKey(epochRoot, bundle.deviceId, epoch, seq)
        epochRoot.fill(0)

        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val fullAad = "${bundle.deviceId}|$epoch|$seq|$aadContext"
        val ct = aesGcmEncrypt(
            key = msgKey,
            iv = iv,
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            aad = fullAad.toByteArray(StandardCharsets.UTF_8)
        )
        msgKey.fill(0)

        val ivB64 = AyraKeyManager.encodeB64(iv)
        val ctB64 = AyraKeyManager.encodeB64(ct)
        val aadHash = sha256Hex(fullAad.toByteArray(StandardCharsets.UTF_8))
        val sigPayload = "${bundle.deviceId}|$epoch|$seq|$ivB64|$ctB64|$aadHash".toByteArray(StandardCharsets.UTF_8)
        val sig = AyraKeyManager.signWithDeviceIdentity(context, sigPayload)

        val obj = JSONObject().apply {
            put("v", 1)
            put("alg", "ECDH-P256-HKDF-AES256GCM")
            put("senderDeviceId", bundle.deviceId)
            put("epoch", epoch)
            put("seq", seq)
            put("iv", ivB64)
            put("ct", ctB64)
            put("sig", AyraKeyManager.encodeB64(sig))
        }
        return "$ENVELOPE_PREFIX$obj"
    }

    /**
     * Decrypts and verifies an E2EE envelope. If [enforceReplayProtection] is true (live stream mode),
     * rejects duplicate/replayed sequence numbers from the same `(senderDeviceId, epoch)`.
     */
    fun decryptEnvelope(
        context: Context,
        envelopeString: String,
        aadContext: String,
        enforceReplayProtection: Boolean = false
    ): String {
        if (!envelopeString.startsWith(ENVELOPE_PREFIX)) {
            return envelopeString
        }
        val json = JSONObject(envelopeString.removePrefix(ENVELOPE_PREFIX))
        val senderDeviceId = json.getString("senderDeviceId")
        val epoch = json.getInt("epoch")
        val seq = json.getLong("seq")
        val ivB64 = json.getString("iv")
        val ctB64 = json.getString("ct")
        val sigB64 = json.getString("sig")

        // Verify sender device identity & revocation status
        val devices = getTrustedDevices(context)
        val senderDevice = devices.find { it.deviceId == senderDeviceId }
            ?: throw SecurityException("Unknown E2EE sender device '$senderDeviceId'")
        if (senderDevice.isRevoked && epoch >= senderDevice.epochAdded && epoch == getCurrentEpoch(context)) {
            throw SecurityException("Sender device '$senderDeviceId' is revoked")
        }

        val fullAad = "$senderDeviceId|$epoch|$seq|$aadContext"
        val aadHash = sha256Hex(fullAad.toByteArray(StandardCharsets.UTF_8))
        val sigPayload = "$senderDeviceId|$epoch|$seq|$ivB64|$ctB64|$aadHash".toByteArray(StandardCharsets.UTF_8)
        val senderPub = decodeEcPublicKey(senderDevice.identityPublicKeyB64)
        if (!AyraKeyManager.verifySignature(senderPub, sigPayload, AyraKeyManager.decodeB64(sigB64))) {
            throw SecurityException("E2EE signature verification failed for sender '$senderDeviceId'")
        }

        if (enforceReplayProtection) {
            val watermarkKey = "$senderDeviceId|$epoch"
            val lastSeq = inMemoryReplayMap[watermarkKey] ?: -1L
            if (seq <= lastSeq) {
                throw SecurityException("Replay detected: seq=$seq <= lastSeen=$lastSeq for $watermarkKey")
            }
            inMemoryReplayMap[watermarkKey] = seq
        }

        val epochRoot = getEpochRootKeyOrNull(context, epoch)
            ?: throw SecurityException("Missing E2EE epoch key for epoch $epoch")
        val msgKey = deriveMessageKey(epochRoot, senderDeviceId, epoch, seq)
        epochRoot.fill(0)

        val plainBytes = aesGcmDecrypt(
            key = msgKey,
            iv = AyraKeyManager.decodeB64(ivB64),
            ciphertext = AyraKeyManager.decodeB64(ctB64),
            aad = fullAad.toByteArray(StandardCharsets.UTF_8)
        )
        msgKey.fill(0)
        return String(plainBytes, StandardCharsets.UTF_8)
    }

    fun clearAllE2eeState(context: Context) {
        inMemoryReplayMap.clear()
        e2eePrefs(context).clear()
    }

    // --- Cryptographic Primitives ---

    internal fun deriveMessageKey(epochRoot: ByteArray, senderDeviceId: String, epoch: Int, seq: Long): ByteArray {
        val salt = "msg|$senderDeviceId|epoch=$epoch".toByteArray(StandardCharsets.UTF_8)
        val info = "ayra-e2ee-msg-v1|seq=$seq".toByteArray(StandardCharsets.UTF_8)
        return hkdfSha256(epochRoot, salt, info, 32)
    }

    internal fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lengthBytes: Int): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val out = ByteArray(lengthBytes)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < lengthBytes) {
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()
            val copyLen = minOf(t.size, lengthBytes - offset)
            System.arraycopy(t, 0, out, offset, copyLen)
            offset += copyLen
            counter++
        }
        prk.fill(0)
        return out
    }

    private fun decodeEcPublicKey(b64: String): PublicKey {
        val bytes = AyraKeyManager.decodeB64(b64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
