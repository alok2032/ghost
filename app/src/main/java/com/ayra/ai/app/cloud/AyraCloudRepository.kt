package com.ayra.ai.app.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import com.ayra.ai.app.R
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.InstalledGitHubPlugin
import com.ayra.ai.app.security.AyraE2eeEngine
import com.ayra.ai.app.security.SecurityAuditCategory
import com.ayra.ai.app.security.SecurityAuditLogger
import com.ayra.ai.app.security.SecurityAuditSeverity
import com.ayra.ai.app.security.TrustedE2eeDevice
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class CloudSyncMode {
    E2EE_ENCRYPTED,
    STANDARD_UNENCRYPTED
}

data class PayloadChunk(
    val fieldName: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: String,
    val chunkSha256: String
)

data class AuthenticatedChunkManifest(
    val markerValue: String,
    val fullPayloadSha256: String,
    val chunks: List<PayloadChunk>
)

data class CloudSessionInfo(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val status: String,
    val createdAtMs: Long,
    val lastActiveMs: Long
)

data class CloudBackupRecord(
    val backupId: String,
    val format: String,
    val encryptedPayloadJson: String,
    val payloadSha256: String,
    val createdAtMs: Long
)

/**
 * Zero-Trust Cloud Firestore repository for Ayra AI.
 *
 * Security Guarantees:
 * - Gated behind Firebase Authentication (`uid`).
 * - Honors Local-Only Privacy Mode (`AyraPreferences.isLocalOnlyAiModeEnabled`): refuses cloud writes when active.
 * - Explicitly distinguishes [CloudSyncMode.E2EE_ENCRYPTED] vs [CloudSyncMode.STANDARD_UNENCRYPTED];
 *   when E2EE is active and [appContext] is null (or decryption fails), FAILS CLOSED rather than silently
 *   falling back to plaintext storage.
 * - Never truncates ciphertext envelopes (`take(...)` on ciphertexts is eliminated): oversized payloads
 *   are stored via authenticated, ordered, SHA-256 verified subcollection chunks (`chunks/{chunkId}`).
 */
class AyraCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val appContext: Context? = null,
    private val syncMode: CloudSyncMode = CloudSyncMode.E2EE_ENCRYPTED
) {

    fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in before accessing cloud data.")

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid

    private fun isCloudSyncSuppressed(): Boolean {
        val ctx = appContext ?: return false
        return AyraPreferences.isLocalOnlyAiModeEnabled(ctx)
    }

    fun resolveEffectiveSyncMode(): CloudSyncMode {
        if (syncMode == CloudSyncMode.STANDARD_UNENCRYPTED) {
            return CloudSyncMode.STANDARD_UNENCRYPTED
        }
        val ctx = appContext
            ?: throw SecurityException(
                "E2EE cloud sync is enabled but Android Context is unavailable; refusing unencrypted cloud fallback."
            )
        return if (AyraPreferences.isCloudE2eeSyncEnabled(ctx)) {
            CloudSyncMode.E2EE_ENCRYPTED
        } else {
            CloudSyncMode.STANDARD_UNENCRYPTED
        }
    }

    private fun encryptFieldForSync(uid: String, aadSuffix: String, value: String): String {
        val mode = resolveEffectiveSyncMode()
        if (mode == CloudSyncMode.STANDARD_UNENCRYPTED || value.isBlank()) {
            return value
        }
        val ctx = appContext
            ?: throw SecurityException("Missing Context during E2EE encryption.")
        val encrypted = AyraE2eeEngine.encryptEnvelope(ctx, value, "$uid|$aadSuffix")
        if (!encrypted.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX)) {
            throw SecurityException("E2EE envelope invariant violated.")
        }
        return encrypted
    }

    private fun decryptFieldStrict(
        uid: String,
        aadSuffix: String,
        storedValue: String,
        expectE2ee: Boolean
    ): String {
        val isEnvelope = storedValue.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX)
        if (!isEnvelope) {
            if (expectE2ee) {
                throw SecurityException("Expected E2EE ciphertext envelope for $aadSuffix, found unencrypted payload.")
            }
            return storedValue
        }
        val ctx = appContext
            ?: throw SecurityException("Cannot decrypt E2EE payload without device keystore Context.")
        return AyraE2eeEngine.decryptEnvelope(
            context = ctx,
            envelopeString = storedValue,
            aadContext = "$uid|$aadSuffix",
            enforceReplayProtection = false,
            requireEncrypted = true
        )
    }

    suspend fun upsertUserProfile(
        displayName: String,
        email: String,
        photoUrl: String,
        preferredPersona: String,
        preferredModelTier: String
    ) {
        if (isCloudSyncSuppressed()) return
        val uid = requireUserId()
        val docRef = firestore.collection("users").document(uid)
        try {
            val snap = docRef.get().awaitResult()
            if (snap.exists()) {
                val updates = mapOf(
                    "displayName" to displayName.take(120),
                    "email" to email.take(200),
                    "photoUrl" to photoUrl.take(500),
                    "preferredPersona" to preferredPersona.take(40),
                    "preferredModelTier" to preferredModelTier.take(60),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).awaitResult()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName.take(120),
                    "email" to email.take(200),
                    "photoUrl" to photoUrl.take(500),
                    "preferredPersona" to preferredPersona.take(40),
                    "preferredModelTier" to preferredModelTier.take(60),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).awaitResult()
            }
            registerCurrentDeviceAndSession(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "upsertUserProfile")
            throw e
        }
    }

    suspend fun registerCurrentDeviceAndSession(uid: String? = currentUserIdOrNull()) {
        val resolvedUid = uid ?: return
        val ctx = appContext ?: return
        if (isCloudSyncSuppressed()) return
        val bundle = AyraE2eeEngine.getOrCreateLocalDeviceBundle(ctx)
        val deviceRef = firestore.collection("users").document(resolvedUid)
            .collection("devices").document(bundle.deviceId)

        try {
            val existing = deviceRef.get().awaitResult()
            if (existing.exists()) {
                deviceRef.update(
                    mapOf(
                        "deviceName" to bundle.deviceName.take(120),
                        "identityPublicKeyB64" to bundle.identityPublicKeyB64,
                        "ecdhPublicKeyB64" to bundle.ecdhPublicKeyB64,
                        "fingerprintSha256" to bundle.fingerprintSha256,
                        "epochAdded" to bundle.currentEpoch,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            } else {
                deviceRef.set(
                    mapOf(
                        "userId" to resolvedUid,
                        "deviceId" to bundle.deviceId,
                        "deviceName" to bundle.deviceName.take(120),
                        "identityPublicKeyB64" to bundle.identityPublicKeyB64,
                        "ecdhPublicKeyB64" to bundle.ecdhPublicKeyB64,
                        "fingerprintSha256" to bundle.fingerprintSha256,
                        "epochAdded" to bundle.currentEpoch,
                        "isRevoked" to false,
                        "platform" to "Android ${Build.VERSION.RELEASE}",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }

            val sessionDocId = sanitizeDocId("sess_${bundle.deviceId}")
            val sessionRef = firestore.collection("users").document(resolvedUid)
                .collection("sessions").document(sessionDocId)
            val sessExists = sessionRef.get().awaitResult().exists()
            if (sessExists) {
                sessionRef.update(
                    mapOf(
                        "deviceName" to bundle.deviceName.take(120),
                        "status" to "ACTIVE",
                        "lastActiveMs" to System.currentTimeMillis(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            } else {
                val nowMs = System.currentTimeMillis()
                sessionRef.set(
                    mapOf(
                        "userId" to resolvedUid,
                        "sessionId" to sessionDocId,
                        "deviceId" to bundle.deviceId,
                        "deviceName" to bundle.deviceName.take(120),
                        "status" to "ACTIVE",
                        "createdAtMs" to nowMs,
                        "lastActiveMs" to nowMs,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "registerCurrentDeviceAndSession")
        }
    }

    suspend fun syncMessageToCloud(message: ChatMessage, messageKey: String? = null) {
        if (isCloudSyncSuppressed()) return
        val uid = currentUserIdOrNull() ?: return
        val effectiveMode = resolveEffectiveSyncMode()
        val docId = sanitizeDocId(messageKey ?: "msg_${message.timestamp}_${message.id}")
        val docRef = firestore.collection("users").document(uid)
            .collection("messages").document(docId)
        val normalizedRole = if (message.role == "user") "user" else "assistant"

        val encodedContent = encryptFieldForSync(uid, "$docId|content", message.content)
        val encodedCraftPayload = if (!message.craftPayload.isNullOrBlank()) {
            encryptFieldForSync(uid, "$docId|craftPayload", message.craftPayload)
        } else ""
        val isE2ee = effectiveMode == CloudSyncMode.E2EE_ENCRYPTED && encodedContent.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX)

        if (encodedCraftPayload.length > MAX_INLINE_MESSAGE_CHARS) {
            throw IllegalArgumentException(
                "Encrypted craftPayload (${encodedCraftPayload.length} chars) exceeds maximum inline field limit; refusing silent truncation."
            )
        }

        val chunkManifest = if (encodedContent.length > MAX_INLINE_MESSAGE_CHARS) {
            splitPayloadIntoAuthenticatedChunks(fieldName = "content", payload = encodedContent)
        } else {
            null
        }
        val storedContentValue = chunkManifest?.markerValue ?: encodedContent
        val contentSha = chunkManifest?.fullPayloadSha256 ?: sha256Hex(encodedContent)
        val chunkCount = chunkManifest?.chunks?.size ?: 0

        try {
            val existingSnap = docRef.get().awaitResult()
            val batch = firestore.batch()
            if (existingSnap.exists()) {
                val updates = mapOf(
                    "content" to storedContentValue,
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to encodedCraftPayload,
                    "e2ee" to isE2ee,
                    "chunkCount" to chunkCount,
                    "contentSha256" to contentSha,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.update(docRef, updates)
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "localMessageId" to message.id.coerceAtLeast(0L),
                    "role" to normalizedRole,
                    "content" to storedContentValue,
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to encodedCraftPayload,
                    "e2ee" to isE2ee,
                    "chunkCount" to chunkCount,
                    "contentSha256" to contentSha,
                    "timestampMs" to message.timestamp.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.set(docRef, payload)
            }

            if (chunkManifest != null) {
                deleteCollectionDocuments(docRef.collection("chunks"))
                enqueueChunksToBatch(batch, uid, docRef, chunkManifest.chunks)
            }
            batch.commit().awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "syncMessageToCloud")
            throw e
        }
    }

    private fun enqueueChunksToBatch(
        batch: com.google.firebase.firestore.WriteBatch,
        uid: String,
        parentRef: DocumentReference,
        chunks: List<PayloadChunk>
    ) {
        val runTag = System.currentTimeMillis()
        for (chunk in chunks) {
            val chunkDoc = parentRef.collection("chunks").document("c_${chunk.fieldName}_${chunk.chunkIndex}_$runTag")
            batch.set(
                chunkDoc,
                mapOf(
                    "userId" to uid,
                    "fieldName" to chunk.fieldName,
                    "chunkIndex" to chunk.chunkIndex,
                    "totalChunks" to chunk.totalChunks,
                    "data" to chunk.data,
                    "chunkSha256" to chunk.chunkSha256,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
    }

    private suspend fun resolveStoredFieldPayload(
        parentRef: DocumentReference,
        storedValue: String,
        fieldName: String
    ): String {
        val marker = parseChunkMarker(storedValue) ?: return storedValue
        val snap = parentRef.collection("chunks")
            .whereEqualTo("fieldName", fieldName)
            .get()
            .awaitResult()
        val chunks = snap.documents.mapNotNull { d ->
            val index = d.getLong("chunkIndex")?.toInt() ?: return@mapNotNull null
            val total = d.getLong("totalChunks")?.toInt() ?: return@mapNotNull null
            val data = d.getString("data") ?: return@mapNotNull null
            val sha = d.getString("chunkSha256") ?: return@mapNotNull null
            PayloadChunk(
                fieldName = fieldName,
                chunkIndex = index,
                totalChunks = total,
                data = data,
                chunkSha256 = sha
            )
        }
        return reassembleAndVerifyChunks(
            expectedFullSha256 = marker.expectedSha256,
            expectedChunkCount = marker.expectedChunkCount,
            chunks = chunks
        )
    }

    fun observeCloudMessages(): Flow<List<ChatMessage>> {
        val uid = currentUserIdOrNull() ?: return emptyFlow()
        return firestore.collection("users").document(uid)
            .collection("messages")
            .orderBy("timestampMs", Query.Direction.ASCENDING)
            .limit(150)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    val rawContent = doc.getString("content") ?: return@mapNotNull null
                    val expectE2ee = doc.getBoolean("e2ee") == true
                    val content = runCatching {
                        val assembled = resolveStoredFieldPayload(doc.reference, rawContent, "content")
                        decryptFieldStrict(uid, "${doc.id}|content", assembled, expectE2ee)
                    }.getOrElse { ex ->
                        "[E2EE Decryption Blocked: ${ex.message ?: "Integrity or key verification failed"}]"
                    }
                    val role = doc.getString("role") ?: "assistant"
                    val timestampMs = doc.getLong("timestampMs") ?: System.currentTimeMillis()
                    val craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() }
                    val rawPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() }
                    val craftPayload = rawPayload?.let { payload ->
                        runCatching { decryptFieldStrict(uid, "${doc.id}|craftPayload", payload, expectE2ee) }
                            .getOrElse { "[E2EE Payload Blocked]" }
                    }
                    val modelUsed = doc.getString("modelUsed")?.takeIf { it.isNotBlank() }
                    ChatMessage(
                        role = role,
                        content = content,
                        timestamp = timestampMs,
                        craftType = craftType,
                        craftPayload = craftPayload,
                        actionCommand = modelUsed
                    )
                }
            }
            .catch { error ->
                if (error is CancellationException) throw error
                val fsEx = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                handleFirestoreError(fsEx ?: (error as? Exception ?: Exception(error)), "observeCloudMessages")
                emit(emptyList())
            }
    }

    suspend fun fetchCloudMessagesOnce(limitCount: Long = 100): List<ChatMessage> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("messages")
                .orderBy("timestampMs", Query.Direction.ASCENDING)
                .limit(limitCount)
                .get()
                .awaitResult()
            snap.documents.mapNotNull { doc ->
                val rawContent = doc.getString("content") ?: return@mapNotNull null
                val expectE2ee = doc.getBoolean("e2ee") == true
                val content = runCatching {
                    val assembled = resolveStoredFieldPayload(doc.reference, rawContent, "content")
                    decryptFieldStrict(uid, "${doc.id}|content", assembled, expectE2ee)
                }.getOrElse { ex ->
                    "[E2EE Decryption Blocked: ${ex.message ?: "Integrity or key verification failed"}]"
                }
                val rawPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() }
                val craftPayload = rawPayload?.let { payload ->
                    runCatching { decryptFieldStrict(uid, "${doc.id}|craftPayload", payload, expectE2ee) }
                        .getOrElse { "[E2EE Payload Blocked]" }
                }
                ChatMessage(
                    role = doc.getString("role") ?: "assistant",
                    content = content,
                    timestamp = doc.getLong("timestampMs") ?: System.currentTimeMillis(),
                    craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() },
                    craftPayload = craftPayload,
                    actionCommand = doc.getString("modelUsed")?.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "fetchCloudMessagesOnce")
            emptyList()
        }
    }

    suspend fun clearCloudMessages() {
        val uid = currentUserIdOrNull() ?: return
        try {
            val messagesCol = firestore.collection("users").document(uid).collection("messages")
            val snap = messagesCol.get().awaitResult()
            for (doc in snap.documents) {
                deleteCollectionDocuments(doc.reference.collection("chunks"))
            }
            deleteCollectionDocuments(messagesCol)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "clearCloudMessages")
            throw e
        }
    }

    suspend fun syncPluginToCloud(plugin: InstalledGitHubPlugin) {
        if (isCloudSyncSuppressed()) return
        val uid = currentUserIdOrNull() ?: return
        val docId = pluginDocId(plugin.repoFullName)
        val docRef = firestore.collection("users").document(uid)
            .collection("plugins").document(docId)
        val safePluginType = if (plugin.pluginType in ALLOWED_PLUGIN_TYPES) plugin.pluginType else "CODE_PLUGIN"
        try {
            val existing = docRef.get().awaitResult()
            if (existing.exists()) {
                val updates = mapOf(
                    "repoUrl" to plugin.repoUrl.take(300),
                    "name" to plugin.name.take(120),
                    "owner" to plugin.owner.take(100),
                    "description" to plugin.description.take(1000),
                    "defaultBranch" to plugin.defaultBranch.ifBlank { "main" }.take(80),
                    "stars" to plugin.stars.coerceAtLeast(0),
                    "primaryLanguage" to plugin.primaryLanguage.take(60),
                    "pluginType" to safePluginType,
                    "capabilitiesSummary" to plugin.capabilitiesSummary.take(4000),
                    "skillPromptContext" to plugin.skillPromptContext.take(20000),
                    "openApiSpecJson" to plugin.openApiSpecJson.take(20000),
                    "commandsJson" to plugin.commandsJson.take(10000),
                    "fileTreeJson" to plugin.fileTreeJson.take(15000),
                    "isEnabled" to plugin.isEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).awaitResult()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "repoFullName" to plugin.repoFullName.take(140),
                    "repoUrl" to plugin.repoUrl.take(300),
                    "name" to plugin.name.take(120),
                    "owner" to plugin.owner.take(100),
                    "description" to plugin.description.take(1000),
                    "defaultBranch" to plugin.defaultBranch.ifBlank { "main" }.take(80),
                    "stars" to plugin.stars.coerceAtLeast(0),
                    "primaryLanguage" to plugin.primaryLanguage.take(60),
                    "pluginType" to safePluginType,
                    "capabilitiesSummary" to plugin.capabilitiesSummary.take(4000),
                    "skillPromptContext" to plugin.skillPromptContext.take(20000),
                    "openApiSpecJson" to plugin.openApiSpecJson.take(20000),
                    "commandsJson" to plugin.commandsJson.take(10000),
                    "fileTreeJson" to plugin.fileTreeJson.take(15000),
                    "isEnabled" to plugin.isEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).awaitResult()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "syncPluginToCloud")
            throw e
        }
    }

    suspend fun deletePluginFromCloud(repoFullName: String) {
        val uid = currentUserIdOrNull() ?: return
        val docId = pluginDocId(repoFullName)
        try {
            firestore.collection("users").document(uid)
                .collection("plugins").document(docId)
                .delete()
                .awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "deletePluginFromCloud")
            throw e
        }
    }

    suspend fun fetchCloudPluginsOnce(): List<InstalledGitHubPlugin> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("plugins")
                .get()
                .awaitResult()
            snap.documents.mapNotNull { doc ->
                val repoFullName = doc.getString("repoFullName") ?: return@mapNotNull null
                InstalledGitHubPlugin(
                    repoFullName = repoFullName,
                    repoUrl = doc.getString("repoUrl") ?: "https://github.com/$repoFullName",
                    name = doc.getString("name") ?: repoFullName.substringAfter('/'),
                    owner = doc.getString("owner") ?: repoFullName.substringBefore('/'),
                    description = doc.getString("description") ?: "",
                    defaultBranch = doc.getString("defaultBranch") ?: "main",
                    stars = (doc.getLong("stars") ?: 0L).toInt(),
                    primaryLanguage = doc.getString("primaryLanguage") ?: "Unknown",
                    pluginType = doc.getString("pluginType") ?: "CODE_PLUGIN",
                    capabilitiesSummary = doc.getString("capabilitiesSummary") ?: "",
                    skillPromptContext = doc.getString("skillPromptContext") ?: "",
                    openApiSpecJson = doc.getString("openApiSpecJson") ?: "[]",
                    commandsJson = doc.getString("commandsJson") ?: "[]",
                    fileTreeJson = doc.getString("fileTreeJson") ?: "[]",
                    isEnabled = doc.getBoolean("isEnabled") ?: true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "fetchCloudPluginsOnce")
            emptyList()
        }
    }

    suspend fun logCreation(
        creationType: String,
        title: String,
        prompt: String,
        summary: String,
        modelUsed: String
    ) {
        if (isCloudSyncSuppressed()) return
        val uid = currentUserIdOrNull() ?: return
        val effectiveMode = resolveEffectiveSyncMode()
        val safeType = if (creationType in ALLOWED_CREATION_TYPES) creationType else "IMAGE"
        val docRef = firestore.collection("users").document(uid)
            .collection("creations").document()

        val rawPrompt = prompt.ifBlank { "Creation prompt" }
        val finalPrompt = if (effectiveMode == CloudSyncMode.E2EE_ENCRYPTED) {
            val enc = encryptFieldForSync(uid, "${docRef.id}|prompt", rawPrompt)
            if (enc.length > 20_000) {
                throw IllegalArgumentException("Encrypted creation prompt (${enc.length} chars) exceeds limit; refusing truncation.")
            }
            enc
        } else {
            rawPrompt.take(20_000)
        }

        val finalSummary = if (effectiveMode == CloudSyncMode.E2EE_ENCRYPTED) {
            val enc = encryptFieldForSync(uid, "${docRef.id}|summary", summary)
            if (enc.length > 50_000) {
                throw IllegalArgumentException("Encrypted creation summary (${enc.length} chars) exceeds limit; refusing truncation.")
            }
            enc
        } else {
            summary.take(50_000)
        }

        try {
            val payload = mapOf(
                "userId" to uid,
                "creationType" to safeType,
                "title" to title.ifBlank { "Ayra AI Creation" }.take(200),
                "prompt" to finalPrompt,
                "summary" to finalSummary,
                "modelUsed" to modelUsed.take(120),
                "e2ee" to (effectiveMode == CloudSyncMode.E2EE_ENCRYPTED),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "logCreation")
            throw e
        }
    }

    suspend fun fetchCloudDevices(): List<TrustedE2eeDevice> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("devices").get().awaitResult()
            snap.documents.mapNotNull { doc ->
                val deviceId = doc.getString("deviceId") ?: return@mapNotNull null
                TrustedE2eeDevice(
                    deviceId = deviceId,
                    deviceName = doc.getString("deviceName") ?: "Android Device",
                    identityPublicKeyB64 = doc.getString("identityPublicKeyB64") ?: "",
                    ecdhPublicKeyB64 = doc.getString("ecdhPublicKeyB64") ?: "",
                    fingerprintSha256 = doc.getString("fingerprintSha256") ?: "",
                    epochAdded = (doc.getLong("epochAdded") ?: 1L).toInt(),
                    isRevoked = doc.getBoolean("isRevoked") ?: false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "fetchCloudDevices")
            emptyList()
        }
    }

    suspend fun fetchCloudSessions(): List<CloudSessionInfo> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("sessions").get().awaitResult()
            snap.documents.mapNotNull { doc ->
                val sid = doc.getString("sessionId") ?: return@mapNotNull null
                CloudSessionInfo(
                    sessionId = sid,
                    deviceId = doc.getString("deviceId") ?: "",
                    deviceName = doc.getString("deviceName") ?: "Device",
                    status = doc.getString("status") ?: "ACTIVE",
                    createdAtMs = doc.getLong("createdAtMs") ?: 0L,
                    lastActiveMs = doc.getLong("lastActiveMs") ?: 0L
                )
            }.sortedByDescending { it.lastActiveMs }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "fetchCloudSessions")
            emptyList()
        }
    }

    suspend fun revokeDeviceAndSessionInCloud(deviceId: String) {
        val uid = currentUserIdOrNull() ?: return
        appContext?.let { AyraE2eeEngine.revokeDeviceAndRotateEpoch(it, deviceId) }
        try {
            firestore.collection("users").document(uid)
                .collection("devices").document(deviceId)
                .update(
                    mapOf(
                        "isRevoked" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "revokeDeviceInCloud")
        }
        try {
            val sessId = sanitizeDocId("sess_$deviceId")
            firestore.collection("users").document(uid)
                .collection("sessions").document(sessId)
                .update(
                    mapOf(
                        "status" to "REVOKED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "revokeSessionInCloud")
        }
    }

    suspend fun uploadEncryptedBackup(
        backupId: String,
        format: String,
        encryptedJson: String,
        payloadSha256: String,
        createdAtMs: Long
    ) {
        val uid = requireUserId()
        val safeId = sanitizeDocId(backupId)
        val computedSha = sha256Hex(encryptedJson)
        if (!computedSha.equals(payloadSha256, ignoreCase = true)) {
            throw SecurityException("Encrypted backup SHA-256 mismatch; refusing upload.")
        }

        val docRef = firestore.collection("users").document(uid)
            .collection("backups").document(safeId)

        val manifest = if (encryptedJson.length > MAX_INLINE_BACKUP_CHARS) {
            splitPayloadIntoAuthenticatedChunks(fieldName = "encryptedPayloadJson", payload = encryptedJson)
        } else {
            null
        }
        val storedJsonField = manifest?.markerValue ?: encryptedJson
        val chunkCount = manifest?.chunks?.size ?: 0

        val batch = firestore.batch()
        batch.set(
            docRef,
            mapOf(
                "userId" to uid,
                "backupId" to safeId,
                "format" to format.take(60),
                "encryptedPayloadJson" to storedJsonField,
                "payloadSha256" to computedSha,
                "chunkCount" to chunkCount,
                "createdAtMs" to createdAtMs,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (manifest != null) {
            enqueueChunksToBatch(batch, uid, docRef, manifest.chunks)
        }
        batch.commit().awaitResult()
    }

    suspend fun listCloudBackups(): List<CloudBackupRecord> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("backups").get().awaitResult()
            snap.documents.mapNotNull { d ->
                val id = d.getString("backupId") ?: return@mapNotNull null
                val rawPayload = d.getString("encryptedPayloadJson") ?: return@mapNotNull null
                val expectedSha = d.getString("payloadSha256") ?: ""
                val resolvedPayload = resolveStoredFieldPayload(
                    parentRef = d.reference,
                    storedValue = rawPayload,
                    fieldName = "encryptedPayloadJson"
                )
                if (expectedSha.isNotBlank() && !sha256Hex(resolvedPayload).equals(expectedSha, ignoreCase = true)) {
                    throw SecurityException("Backup '$id' SHA-256 integrity verification failed after reassembly.")
                }
                CloudBackupRecord(
                    backupId = id,
                    format = d.getString("format") ?: "",
                    encryptedPayloadJson = resolvedPayload,
                    payloadSha256 = expectedSha,
                    createdAtMs = d.getLong("createdAtMs") ?: 0L
                )
            }.sortedByDescending { it.createdAtMs }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "listCloudBackups")
            emptyList()
        }
    }

    suspend fun deleteCloudBackup(backupId: String) {
        val uid = currentUserIdOrNull() ?: return
        val docRef = firestore.collection("users").document(uid)
            .collection("backups").document(sanitizeDocId(backupId))
        deleteCollectionDocuments(docRef.collection("chunks"))
        docRef.delete().awaitResult()
    }

    suspend fun uploadSecurityEvent(
        category: String,
        severity: String,
        message: String,
        timestampMs: Long,
        hmacHex: String
    ) {
        if (isCloudSyncSuppressed()) return
        val uid = currentUserIdOrNull() ?: return
        val docRef = firestore.collection("users").document(uid)
            .collection("securityEvents").document()
        try {
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "category" to category.take(40),
                    "severity" to severity.take(20),
                    "message" to SecurityAuditLogger.redactSensitiveText(message).take(500),
                    "hmacHex" to hmacHex.take(128),
                    "timestampMs" to timestampMs,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "uploadSecurityEvent")
        }
    }

    suspend fun purgeAllUserCloudData(): Int {
        val uid = currentUserIdOrNull() ?: return 0
        val userDoc = firestore.collection("users").document(uid)
        var totalDeleted = 0
        clearCloudMessages()
        val backupsSnap = userDoc.collection("backups").get().awaitResult()
        for (bakDoc in backupsSnap.documents) {
            totalDeleted += deleteCollectionDocuments(bakDoc.reference.collection("chunks"))
        }
        val subCollections = listOf(
            "creations",
            "plugins",
            "devices",
            "sessions",
            "backups",
            "securityEvents"
        )
        subCollections.forEach { sub ->
            totalDeleted += deleteCollectionDocuments(userDoc.collection(sub))
        }
        try {
            userDoc.delete().awaitResult()
            totalDeleted += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "purgeAllUserCloudData")
        }
        return totalDeleted
    }

    private suspend fun deleteCollectionDocuments(col: com.google.firebase.firestore.CollectionReference): Int {
        val snap = col.get().awaitResult()
        val docs = snap.documents
        var count = 0
        for (chunk in docs.chunked(400)) {
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().awaitResult()
            count += chunk.size
        }
        return count
    }

    private fun handleFirestoreError(e: Exception, operation: String) {
        if (e is FirebaseFirestoreException) {
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    Log.e(TAG, "Firestore PERMISSION_DENIED in $operation: ${e.message}", e)
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    Log.w(TAG, "Firestore network unavailable in $operation: ${e.message}")
                else ->
                    Log.e(TAG, "Firestore error [${e.code}] in $operation: ${e.message}", e)
            }
        } else {
            Log.e(TAG, "Unexpected cloud error in $operation: ${e.message}", e)
        }
    }

    data class ChunkMarker(val expectedSha256: String, val expectedChunkCount: Int)

    companion object {
        private const val TAG = "AyraCloudRepository"
        const val MAX_INLINE_MESSAGE_CHARS = 85_000
        const val MAX_INLINE_BACKUP_CHARS = 700_000
        const val DEFAULT_CHUNK_CHARS = 65_000
        const val MAX_CHUNKS_PER_PAYLOAD = 50
        const val CHUNK_MARKER_PREFIX = "__AYRA_CHUNKED_V1__:"

        private val ALLOWED_PLUGIN_TYPES = setOf("SKILL_PACK", "OPENAPI_PLUGIN", "MCP_SERVER", "WEB_TOOL", "CODE_PLUGIN")
        private val ALLOWED_CREATION_TYPES = setOf("IMAGE", "VIDEO", "SONG", "3D", "PDF")

        fun create(context: Context): AyraCloudRepository {
            val appCtx = context.applicationContext
            val dbId = appCtx.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            return AyraCloudRepository(
                firestore = firestore,
                auth = Firebase.auth,
                appContext = appCtx,
                syncMode = if (AyraPreferences.isCloudE2eeSyncEnabled(appCtx)) {
                    CloudSyncMode.E2EE_ENCRYPTED
                } else {
                    CloudSyncMode.STANDARD_UNENCRYPTED
                }
            )
        }

        fun splitPayloadIntoAuthenticatedChunks(
            fieldName: String,
            payload: String,
            chunkSize: Int = DEFAULT_CHUNK_CHARS
        ): AuthenticatedChunkManifest {
            require(chunkSize in 16..85_000) { "Chunk size must be between 16 and 85000 chars." }
            require(payload.isNotEmpty()) { "Cannot chunk empty payload." }
            val parts = payload.chunked(chunkSize)
            if (parts.size > MAX_CHUNKS_PER_PAYLOAD) {
                throw IllegalArgumentException("Payload requires ${parts.size} chunks exceeding limit $MAX_CHUNKS_PER_PAYLOAD.")
            }
            val fullSha = sha256Hex(payload)
            val total = parts.size
            val chunks = parts.mapIndexed { idx, data ->
                PayloadChunk(
                    fieldName = fieldName,
                    chunkIndex = idx,
                    totalChunks = total,
                    data = data,
                    chunkSha256 = sha256Hex(data)
                )
            }
            return AuthenticatedChunkManifest(
                markerValue = "$CHUNK_MARKER_PREFIX$fullSha:$total",
                fullPayloadSha256 = fullSha,
                chunks = chunks
            )
        }

        fun parseChunkMarker(storedValue: String): ChunkMarker? {
            if (!storedValue.startsWith(CHUNK_MARKER_PREFIX)) return null
            val rest = storedValue.removePrefix(CHUNK_MARKER_PREFIX)
            val parts = rest.split(":")
            if (parts.size != 2) return null
            val sha = parts[0]
            val count = parts[1].toIntOrNull() ?: return null
            if (!Regex("^[0-9a-fA-F]{64}$").matches(sha) || count !in 1..MAX_CHUNKS_PER_PAYLOAD) {
                return null
            }
            return ChunkMarker(expectedSha256 = sha, expectedChunkCount = count)
        }

        fun reassembleAndVerifyChunks(
            expectedFullSha256: String,
            expectedChunkCount: Int,
            chunks: List<PayloadChunk>
        ): String {
            if (chunks.size != expectedChunkCount) {
                throw SecurityException(
                    "Missing or extra payload chunks: expected $expectedChunkCount, got ${chunks.size}."
                )
            }
            val ordered = chunks.sortedBy { it.chunkIndex }
            val sb = StringBuilder()
            for (expectedIdx in 0 until expectedChunkCount) {
                val chunk = ordered[expectedIdx]
                if (chunk.chunkIndex != expectedIdx || chunk.totalChunks != expectedChunkCount) {
                    throw SecurityException(
                        "Chunk ordering/total mismatch at index $expectedIdx (got idx=${chunk.chunkIndex}, total=${chunk.totalChunks})."
                    )
                }
                if (!sha256Hex(chunk.data).equals(chunk.chunkSha256, ignoreCase = true)) {
                    throw SecurityException("Chunk #$expectedIdx SHA-256 digest mismatch.")
                }
                sb.append(chunk.data)
            }
            val reassembled = sb.toString()
            if (!sha256Hex(reassembled).equals(expectedFullSha256, ignoreCase = true)) {
                throw SecurityException("Reassembled payload SHA-256 mismatch (chunk tampering detected).")
            }
            return reassembled
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun pluginDocId(repoFullName: String): String =
            sanitizeDocId(repoFullName.replace("/", "__"))

        private fun sanitizeDocId(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "item" }.take(120)
    }
}
