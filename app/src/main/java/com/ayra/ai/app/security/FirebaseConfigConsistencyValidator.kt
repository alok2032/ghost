package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.R
import com.google.firebase.FirebaseApp

data class FirebaseConfigAuditResult(
    val valid: Boolean,
    val projectId: String,
    val firestoreDatabaseId: String,
    val issues: List<String>
)

/**
 * Validates runtime Firebase configuration single-source-of-truth consistency across
 * Google Services JSON ([FirebaseApp] / XML resources) without relying on brittle ID blocklists.
 */
object FirebaseConfigConsistencyValidator {

    @Volatile
    var lastAuditResult: FirebaseConfigAuditResult? = null
        private set

    fun validateValues(
        projectId: String,
        firestoreDatabaseId: String,
        gcmSenderId: String,
        defaultWebClientId: String,
        recaptchaSiteKey: String
    ): FirebaseConfigAuditResult {
        val issues = mutableListOf<String>()
        val cleanProjectId = projectId.trim()
        val cleanDbId = firestoreDatabaseId.trim()
        val cleanSender = gcmSenderId.trim()
        val cleanWebClient = defaultWebClientId.trim()
        val cleanRecaptcha = recaptchaSiteKey.trim()

        if (cleanProjectId.isEmpty() ||
            cleanProjectId.contains("REPLACE_ME", ignoreCase = true) ||
            cleanProjectId.equals("remixed-project-id", ignoreCase = true)
        ) {
            issues.add("Firebase project_id is missing or placeholder.")
        }
        if (cleanDbId.isEmpty() || !cleanDbId.startsWith("ai-studio-")) {
            issues.add("firestore_database_id '$cleanDbId' is missing or invalid.")
        }
        if (cleanSender.isNotEmpty() && cleanWebClient.isNotEmpty() && !cleanWebClient.startsWith("$cleanSender-")) {
            issues.add("default_web_client_id project number prefix does not match gcm_defaultSenderId ($cleanSender).")
        }
        if (cleanRecaptcha.isEmpty() || cleanRecaptcha.contains("REPLACE_ME", ignoreCase = true)) {
            issues.add("recaptcha_site_key is missing or placeholder.")
        }

        return FirebaseConfigAuditResult(
            valid = issues.isEmpty(),
            projectId = cleanProjectId,
            firestoreDatabaseId = cleanDbId,
            issues = issues
        )
    }

    fun validate(context: Context): FirebaseConfigAuditResult {
        val appOptions = runCatching { FirebaseApp.getInstance().options }.getOrNull()
        val projectId = appOptions?.projectId
            ?: runCatching { context.getString(R.string.project_id) }.getOrDefault("")
        val dbId = runCatching { context.getString(R.string.firestore_database_id) }.getOrDefault("")
        val senderId = appOptions?.gcmSenderId
            ?: runCatching { context.getString(R.string.gcm_defaultSenderId) }.getOrDefault("")
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrDefault("")
        val recaptcha = runCatching { context.getString(R.string.recaptcha_site_key) }.getOrDefault("")
        return validateValues(projectId, dbId, senderId, webClientId, recaptcha).also {
            lastAuditResult = it
        }
    }
}
