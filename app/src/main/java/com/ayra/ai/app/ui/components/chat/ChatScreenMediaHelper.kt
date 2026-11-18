package com.ayra.ai.app.ui.components.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.ayra.ai.app.ai.FirebaseAiEngine
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import java.io.File

internal data class ProcessedDocumentAttachment(
    val name: String,
    val extractedText: String,
    val firstPageBase64: String?
)

internal object ChatScreenMediaHelper {

    fun permissionRationaleFor(permission: String): Triple<String, String, String> = when (permission) {
        Manifest.permission.RECORD_AUDIO -> Triple(
            "Microphone Access for Voice AI",
            "Ayra AI uses the microphone only when you tap voice input or enable hands-free wake word, to transcribe speech locally or with your chosen AI provider.",
            "Allow Microphone"
        )
        Manifest.permission.CAMERA -> Triple(
            "Camera Access for Visual Capture",
            "Ayra AI needs the camera only when you choose 'Take Photo with Camera' so you can capture an image for vision Q&A.",
            "Allow Camera"
        )
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION -> Triple(
            "Location Access for GPS Queries",
            "Ayra AI reads your approximate or precise device location only when you request coordinates, local directions, or weather.",
            "Allow Location"
        )
        Manifest.permission.READ_CONTACTS -> Triple(
            "Contacts Access for Name Lookup",
            "Ayra AI only reads Contacts when you ask to call or message someone by name so it can look up their phone number before opening your dialer or SMS app.",
            "Allow Contacts"
        )
        else -> Triple(
            "Permission Required",
            "Ayra AI requests this permission only for the specific feature you tapped.",
            "Continue"
        )
    }

    fun createCameraCaptureUri(context: Context): Result<Uri> = runCatching {
        val exportsDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val photoFile = File(exportsDir, "camera_capture_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", photoFile)
    }

    suspend fun processSelectedDocument(
        context: Context,
        uri: Uri,
        db: ChatDatabase
    ): ProcessedDocumentAttachment {
        return try {
            var name = "Document"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) name = cursor.getString(idx) ?: "Document"
            }
            val extracted = StorageHelper.extractTextFromDocument(context, uri, name)
            val pdfBase64 = if (name.endsWith(".pdf", ignoreCase = true)) {
                StorageHelper.renderPdfFirstPageToBase64(context, uri)
            } else {
                null
            }
            StorageHelper.saveMediaToInternalStorage(context, uri, name)
            if (extracted.isNotBlank()) {
                runCatching {
                    DocumentRagEngine(db.knowledgeDocumentDao(), context).ingestDocument(
                        title = name,
                        mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                        rawText = extracted,
                        sourceUri = uri.toString()
                    )
                }
            }
            ProcessedDocumentAttachment(name = name, extractedText = extracted, firstPageBase64 = pdfBase64)
        } catch (_: Exception) {
            ProcessedDocumentAttachment(
                name = "Uploaded File",
                extractedText = "Document attached for analysis: ${uri.lastPathSegment}",
                firstPageBase64 = null
            )
        }
    }

    suspend fun transcribeRecordedAudio(
        firebaseAiEngine: FirebaseAiEngine,
        externalAiProviders: ExternalAiProviders,
        recordedFile: File
    ): Result<String> {
        var res = firebaseAiEngine.transcribeAudioFile(recordedFile)
        if (res.isFailure) {
            val whisperText = externalAiProviders.transcribeAudioOpenAiCompatible(recordedFile)
            if (whisperText.isNotBlank()) res = Result.success(whisperText)
        }
        runCatching { recordedFile.delete() }
        return res
    }

    fun resolvePendingContact(
        context: Context,
        current: com.ayra.ai.app.automation.ProposedAction?
    ): Pair<com.ayra.ai.app.automation.ProposedAction?, String> = when (current) {
        is com.ayra.ai.app.automation.ProposedAction.DirectCall -> {
            val resolved = com.ayra.ai.app.automation.ContactResolver.resolveContact(context, current.displayName)
            if (resolved != null) {
                current.copy(displayName = resolved.name, phoneNumber = resolved.phoneNumber) to "Contact resolved: ${resolved.name}"
            } else {
                null to "Contacts allowed, but no match found for '${current.displayName}'."
            }
        }
        is com.ayra.ai.app.automation.ProposedAction.SendSms -> {
            val resolved = com.ayra.ai.app.automation.ContactResolver.resolveContact(context, current.recipient)
            if (resolved != null) {
                current.copy(recipient = resolved.name, phoneNumber = resolved.phoneNumber) to "Contact resolved: ${resolved.name}"
            } else {
                null to "Contacts allowed, but no match found for '${current.recipient}'."
            }
        }
        else -> null to "Contacts permission granted."
    }

    fun loadAttachmentBase64(
        context: Context,
        attachedUriStr: String?,
        docBase64Image: String?
    ): String? {
        if (!attachedUriStr.isNullOrBlank()) {
            return runCatching {
                StorageHelper.openReadableAttachmentStream(context, attachedUriStr)?.use { stream ->
                    android.util.Base64.encodeToString(stream.readBytes(), android.util.Base64.NO_WRAP)
                }
            }.getOrNull()
        }
        return docBase64Image?.takeIf { it.isNotBlank() }
    }
}
