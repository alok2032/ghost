package com.ayra.ai.app.cloud

import android.content.Context
import android.util.Log
import com.ayra.ai.app.R
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.InstalledGitHubPlugin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Cloud Firestore repository for Ayra AI.
 * Strictly uses the custom provisioned Firestore database ID from R.string.firestore_database_id,
 * gates all queries/writes behind FirebaseAuth.currentUser, and enforces FieldValue.serverTimestamp().
 */
class AyraCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {

    fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in before accessing cloud data.")

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid

    suspend fun upsertUserProfile(
        displayName: String,
        email: String,
        photoUrl: String,
        preferredPersona: String,
        preferredModelTier: String
    ) {
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
        } catch (e: Exception) {
            handleFirestoreError(e, "upsertUserProfile")
            throw e
        }
    }

    suspend fun syncMessageToCloud(message: ChatMessage, messageKey: String? = null) {
        val uid = currentUserIdOrNull() ?: return
        val docId = sanitizeDocId(messageKey ?: "msg_${message.timestamp}_${message.id}")
        val docRef = firestore.collection("users").document(uid)
            .collection("messages").document(docId)
        val normalizedRole = if (message.role == "user") "user" else "assistant"
        try {
            val existingSnap = docRef.get().awaitResult()
            if (existingSnap.exists()) {
                val updates = mapOf(
                    "content" to message.content.take(90_000),
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to (message.craftPayload ?: "").take(90_000),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).awaitResult()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "localMessageId" to message.id.coerceAtLeast(0L),
                    "role" to normalizedRole,
                    "content" to message.content.take(90_000),
                    "modelUsed" to (message.actionCommand ?: "").take(120),
                    "craftType" to (message.craftType ?: "").take(40),
                    "craftPayload" to (message.craftPayload ?: "").take(90_000),
                    "timestampMs" to message.timestamp.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).awaitResult()
            }
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
                    val content = doc.getString("content") ?: return@mapNotNull null
                    val role = doc.getString("role") ?: "assistant"
                    val timestampMs = doc.getLong("timestampMs") ?: System.currentTimeMillis()
                    val craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() }
                    val craftPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() }
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
                val content = doc.getString("content") ?: return@mapNotNull null
                ChatMessage(
                    role = doc.getString("role") ?: "assistant",
                    content = content,
                    timestamp = doc.getLong("timestampMs") ?: System.currentTimeMillis(),
                    craftType = doc.getString("craftType")?.takeIf { it.isNotBlank() },
                    craftPayload = doc.getString("craftPayload")?.takeIf { it.isNotBlank() },
                    actionCommand = doc.getString("modelUsed")?.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            handleFirestoreError(e, "fetchCloudMessagesOnce")
            emptyList()
        }
    }

    suspend fun clearCloudMessages() {
        val uid = currentUserIdOrNull() ?: return
        try {
            val snap = firestore.collection("users").document(uid)
                .collection("messages")
                .get()
                .awaitResult()
            val docs = snap.documents
            for (chunk in docs.chunked(400)) {
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().awaitResult()
            }
        } catch (e: Exception) {
            handleFirestoreError(e, "clearCloudMessages")
            throw e
        }
    }

    suspend fun syncPluginToCloud(plugin: InstalledGitHubPlugin) {
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
        val uid = currentUserIdOrNull() ?: return
        val safeType = if (creationType in ALLOWED_CREATION_TYPES) creationType else "IMAGE"
        val docRef = firestore.collection("users").document(uid)
            .collection("creations").document()
        try {
            val payload = mapOf(
                "userId" to uid,
                "creationType" to safeType,
                "title" to title.ifBlank { "Ayra Creation" }.take(200),
                "prompt" to prompt.ifBlank { "Creation prompt" }.take(2000),
                "summary" to summary.take(5000),
                "modelUsed" to modelUsed.take(120),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).awaitResult()
        } catch (e: Exception) {
            handleFirestoreError(e, "logCreation")
            throw e
        }
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
            val dbId = context.applicationContext.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            return AyraCloudRepository(firestore = firestore, auth = Firebase.auth)
        }

        fun pluginDocId(repoFullName: String): String =
            sanitizeDocId(repoFullName.replace("/", "__"))

        private fun sanitizeDocId(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "item" }.take(120)
    }
}
