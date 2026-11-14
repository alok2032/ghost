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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

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
 * - End-to-End Encrypts synced messages and creations via [AyraE2eeEngine] when `appContext != null`
 *   and `AyraPreferences.isCloudE2eeSyncEnabled(appContext)` is enabled, so Firestore stores ciphertext only.
 * - Manages `/devices`, `/sessions`, `/backups`, and `/securityEvents` with explicit ownership & revocation.
 */
class AyraCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val appContext: Context? = null
) {

    fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in before accessing cloud data.")

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid

    private fun isCloudSyncSuppressed(): Boolean {
        val ctx = appContext ?: return false
        return AyraPreferences.isLocalOnlyAiModeEnabled(ctx)
    }

    private fun encryptFieldIfEnabled(uid: String, aadSuffix: String, value: String): String {
        val ctx = appContext ?: return value
        if (value.isBlank() || !AyraPreferences.isCloudE2eeSyncEnabled(ctx)) return value
        return AyraE2eeEngine.encryptEnvelope(ctx, value, "$uid|$aadSuffix")
    }

    private fun decryptFieldIfEnvelope(uid: String, aadSuffix: String, storedValue: String): String {
        if (!storedValue.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX)) return storedValue
        val ctx = appContext ?: return "[Encrypted E2EE Content — Unlock on Trusted Device]"
        return runCatching {
            AyraE2eeEngine.decryptEnvelope(ctx, storedValue, "$uid|$aadSuffix", enforceReplayProtection = false)
        }.getOrElse {
            "[E2EE Decryption Blocked: ${it.message ?: "Key or device verification failed"}]"
        }
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

    /**
     * Registers current Android device E2EE public key bundle under `/users/{uid}/devices/{deviceId}`
     * and records active login session under `/users/{uid}/sessions/{sessionId}`.
     */
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
                sessionRef.set(
                    mapOf(
                        "userId" to resolvedUid,
                        "sessionId" to sessionDocId,
                        "deviceId" to bundle.deviceId,
                        "deviceName" to bundle.deviceName.take(120),
                        "status" to "ACTIVE",
                        "createdAtMs" to System.currentTimeMillis(),
                        "lastActiveMs" to System.currentTimeMillis(),
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
        val docId = sanitizeDocId(messageKey ?: "msg_${message.timestamp}_${message.id}")
        val docRef = firestore.collection("users").document(uid)
            .collection("messages").document(docId)
        val normalizedRole = if (message.role == "user") "user" else "assistant"

        val encryptedContent = encryptFieldIfEnabled(uid, "$docId|content", message.content)
        val encryptedCraftPayload = if (!message.craftPayload.isNullOrBlank()) {
            encryptFieldIfEnabled(uid, "$docId|craftPayload", message.craftPayload)
        } else ""
        val isE2ee = encryptedContent.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX)

        try {
            val existingSnap = docRef.get().awaitResult()
            if (existingSnap.exists()) {
                val updates = mapOf(
                    "content" to encryptedContent.take(90_000),
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to encryptedCraftPayload.take(90_000),
                    "e2ee" to isE2ee,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).awaitResult()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "localMessageId" to message.id.coerceAtLeast(0L),
                    "role" to normalizedRole,
                    "content" to encryptedContent.take(90_000),
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to encryptedCraftPayload.take(90_000),
                    "e2ee" to isE2ee,
                    "timestampMs" to message.timestamp.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).awaitResult()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, "syncMessageToCloud")
            throw e
        }
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
                    val content = decryptFieldIfEnvelope(uid, "${doc.id}|content", rawContent)
                    val role = doc.getString("role") ?: "assistant"
                    val timestampMs = doc.getLong("timestampMs") ?: System.currentTimeMillis()
                    val craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() }
                    val rawPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() }
                    val craftPayload = rawPayload?.let { decryptFieldIfEnvelope(uid, "${doc.id}|craftPayload", it) }
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
                val content = decryptFieldIfEnvelope(uid, "${doc.id}|content", rawContent)
                val rawPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() }
                ChatMessage(
                    role = doc.getString("role") ?: "assistant",
                    content = content,
                    timestamp = doc.getLong("timestampMs") ?: System.currentTimeMillis(),
                    craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() },
                    craftPayload = rawPayload?.let { decryptFieldIfEnvelope(uid, "${doc.id}|craftPayload", it) },
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
            deleteCollectionDocuments(firestore.collection("users").document(uid).collection("messages"))
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
        val safeType = if (creationType in ALLOWED_CREATION_TYPES) creationType else "IMAGE"
        val docRef = firestore.collection("users").document(uid)
            .collection("creations").document()
        val encPrompt = encryptFieldIfEnabled(uid, "${docRef.id}|prompt", prompt)
        val encSummary = encryptFieldIfEnabled(uid, "${docRef.id}|summary", summary)
        try {
            val payload = mapOf(
                "userId" to uid,
                "creationType" to safeType,
                "title" to title.ifBlank { "Ayra Creation" }.take(200),
                "prompt" to encPrompt.ifBlank { "Creation prompt" }.take(20000),
                "summary" to encSummary.take(50000),
                "modelUsed" to modelUsed.take(120),
                "e2ee" to encPrompt.startsWith(AyraE2eeEngine.ENVELOPE_PREFIX),
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

    // --- Cloud Trusted Devices, Active Sessions, Backups & Security Events (Phases 4, 8, 9, 10, 12) ---

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
        val payload = mapOf(
            "userId" to uid,
            "backupId" to safeId,
            "format" to format.take(60),
            "encryptedPayloadJson" to encryptedJson.take(800_000),
            "payloadSha256" to payloadSha256.take(128),
            "createdAtMs" to createdAtMs,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection("users").document(uid)
            .collection("backups").document(safeId)
            .set(payload)
            .awaitResult()
    }

    suspend fun listCloudBackups(): List<CloudBackupRecord> {
        val uid = currentUserIdOrNull() ?: return emptyList()
        return try {
            val snap = firestore.collection("users").document(uid)
                .collection("backups").get().awaitResult()
            snap.documents.mapNotNull { d ->
                val id = d.getString("backupId") ?: return@mapNotNull null
                CloudBackupRecord(
                    backupId = id,
                    format = d.getString("format") ?: "",
                    encryptedPayloadJson = d.getString("encryptedPayloadJson") ?: "",
                    payloadSha256 = d.getString("payloadSha256") ?: "",
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
        firestore.collection("users").document(uid)
            .collection("backups").document(sanitizeDocId(backupId))
            .delete()
            .awaitResult()
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

    /**
     * Permanently deletes ALL cloud Firestore documents owned by the user across
     * messages, creations, plugins, devices, sessions, backups, securityEvents, and root profile.
     */
    suspend fun purgeAllUserCloudData(): Int {
        val uid = currentUserIdOrNull() ?: return 0
        val userDoc = firestore.collection("users").document(uid)
        var totalDeleted = 0
        val subCollections = listOf(
            "messages",
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

    companion object {
        private const val TAG = "AyraCloudRepository"
        private val ALLOWED_PLUGIN_TYPES = setOf("SKILL_PACK", "OPENAPI_PLUGIN", "MCP_SERVER", "WEB_TOOL", "CODE_PLUGIN")
        private val ALLOWED_CREATION_TYPES = setOf("IMAGE", "VIDEO", "SONG", "3D", "PDF")

        fun create(context: Context): AyraCloudRepository {
            val appCtx = context.applicationContext
            val dbId = appCtx.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            return AyraCloudRepository(firestore = firestore, auth = Firebase.auth, appContext = appCtx)
        }

        fun pluginDocId(repoFullName: String): String =
            sanitizeDocId(repoFullName.replace("/", "__"))

        private fun sanitizeDocId(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "item" }.take(120)
    }
}
