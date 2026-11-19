package com.ayra.ai.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.security.EncryptedVaultStorage
import java.io.File
import java.io.InputStream

/**
 * Single Source of Truth for internal/cache export subdirectories referenced by FileProvider (`file_paths.xml`).
 */
object ExportPaths {
    const val GENERATED_PDFS = "generated_pdfs"
    const val GENERATED_DOCS = "generated_docs"
    const val GENERATED_IMAGES = "generated_images"
    const val GENERATED_AUDIO = "generated_audio"
    const val GENERATED_3D = "generated_3d"
    const val PROJECT_ZIPS = "project_zips"
    const val CHAT_EXPORTS = "chat_exports"
    const val SAVED_ATTACHMENTS = "saved_attachments"
    const val CODE_PROJECTS = "code_projects"
    const val INTERNAL_DOCUMENTS = "documents"
    const val FILES_EXPORTS = "exports"
    const val CACHE_EXPORTS = "exports"
}

/**
 * Encrypted & Hardened device storage facade for Ayra AI.
 * - All saved attachments, chat exports, PDFs, Markdown, Word exports, and media artifacts
 *   are stored via [EncryptedVaultStorage] (per-file 256-bit DEK AES-256-GCM + Keystore KEK).
 * - Document extraction delegates to [DocumentParsers]; exports/backups delegate to [DocumentExporters].
 */
object StorageHelper {

    data class WorkspaceArtifact(
        val name: String,
        val folder: String,
        val file: File,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isEncrypted: Boolean = false
    )

    data class WorkspaceBackupPayload(
        val sessions: List<ChatSession>,
        val messages: List<ChatMessage>,
        val plugins: List<InstalledGitHubPlugin>
    )

    private val ARTIFACT_FOLDERS = listOf(
        ExportPaths.GENERATED_PDFS,
        ExportPaths.GENERATED_DOCS,
        ExportPaths.GENERATED_IMAGES,
        ExportPaths.GENERATED_AUDIO,
        ExportPaths.GENERATED_3D,
        ExportPaths.PROJECT_ZIPS,
        ExportPaths.CHAT_EXPORTS,
        ExportPaths.SAVED_ATTACHMENTS,
        ExportPaths.CODE_PROJECTS,
        ExportPaths.FILES_EXPORTS
    )

    fun sanitizeFileName(rawName: String): String {
        val base = rawName.substringAfterLast("/").substringAfterLast("\\")
        val sanitized = base.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        return if (sanitized.trim('_').isBlank()) "attachment_${System.currentTimeMillis()}.dat" else sanitized
    }

    fun saveMediaToInternalStorage(context: Context, uri: Uri, prefix: String = "media"): File? {
        return try {
            val rawName = getFileName(context, uri) ?: "${prefix}_${System.currentTimeMillis()}.dat"
            val sanitized = sanitizeFileName(rawName)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val vaultFile = EncryptedVaultStorage.storeEncryptedBytes(
                context = context,
                folder = "saved_attachments",
                originalName = "${System.currentTimeMillis()}_$sanitized",
                mimeType = mime,
                plaintextBytes = bytes
            )
            bytes.fill(0)
            vaultFile
        } catch (_: Exception) {
            null
        }
    }

    fun openReadableAttachmentStream(context: Context, uriOrPath: String): InputStream? {
        return runCatching {
            val uri = Uri.parse(uriOrPath)
            val filePath = when (uri.scheme) {
                "file", null -> uri.path ?: uriOrPath
                else -> null
            }
            if (filePath != null) {
                val f = File(filePath)
                if (EncryptedVaultStorage.isVaultFile(f)) {
                    return EncryptedVaultStorage.openDecryptedStream(context, f)
                }
                if (f.exists() && f.isFile) {
                    return f.inputStream()
                }
            }
            context.contentResolver.openInputStream(uri)
        }.getOrNull()
    }

    fun resolveCoilImageModel(context: Context, uriOrPath: String?): Any? {
        if (uriOrPath.isNullOrBlank()) return null
        val uri = Uri.parse(uriOrPath)
        val path = when (uri.scheme) {
            "file", null -> uri.path ?: uriOrPath
            else -> null
        }
        if (path != null) {
            val f = File(path)
            if (EncryptedVaultStorage.isVaultFile(f)) {
                return runCatching { EncryptedVaultStorage.decryptVaultFileBytes(context, f) }.getOrNull()
            }
        }
        return uriOrPath
    }

    fun prepareShareableFile(context: Context, file: File): File {
        return if (EncryptedVaultStorage.isVaultFile(file)) {
            EncryptedVaultStorage.materializeTempDecryptedFile(context, file)
        } else {
            file
        }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        val raw = name ?: uri.lastPathSegment
        return raw?.let { sanitizeFileName(it) }
    }

    fun listWorkspaceArtifacts(context: Context): List<WorkspaceArtifact> {
        val result = mutableListOf<WorkspaceArtifact>()

        EncryptedVaultStorage.listVaultArtifacts(context).forEach { entry ->
            result.add(
                WorkspaceArtifact(
                    name = "${entry.originalName} 🔒",
                    folder = "${entry.folder} (Vault)",
                    file = entry.vaultFile,
                    sizeBytes = entry.encryptedSizeBytes,
                    lastModifiedMs = entry.createdMs,
                    isEncrypted = true
                )
            )
        }

        ARTIFACT_FOLDERS.forEach { folder ->
            val dir = File(context.filesDir, folder)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        result.add(
                            WorkspaceArtifact(
                                name = f.name,
                                folder = folder,
                                file = f,
                                sizeBytes = f.length(),
                                lastModifiedMs = f.lastModified(),
                                isEncrypted = EncryptedVaultStorage.isVaultFile(f)
                            )
                        )
                    } else if (f.isDirectory && folder == "code_projects") {
                        val size = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        result.add(
                            WorkspaceArtifact(
                                name = "${f.name} (Project Folder)",
                                folder = folder,
                                file = f,
                                sizeBytes = size,
                                lastModifiedMs = f.lastModified(),
                                isEncrypted = false
                            )
                        )
                    }
                }
            }
        }
        return result.sortedByDescending { it.lastModifiedMs }
    }

    fun deleteWorkspaceArtifact(file: File): Boolean = AndroidHardeningHelper.secureWipeFile(file)

    // --- Delegated Parsers & Exporters (backward-compatible facade) ---

    fun extractTextFromDocument(context: Context, uri: Uri, fileName: String?): String =
        DocumentParsers.extractTextFromDocument(context, uri, fileName)

    internal fun extractDocxText(input: InputStream): String =
        DocumentParsers.extractDocxText(input)

    internal fun extractPptxText(input: InputStream): String =
        DocumentParsers.extractPptxText(input)

    internal fun extractXlsxText(input: InputStream, fileName: String = "workbook.xlsx"): String =
        DocumentParsers.extractXlsxText(input, fileName)

    internal fun extractPdfTextTokensFromBytes(bytes: ByteArray): String =
        DocumentParsers.extractPdfTextTokensFromBytes(bytes)

    fun renderPdfFirstPageToBase64(context: Context, uri: Uri): String? =
        DocumentParsers.renderPdfFirstPageToBase64(context, uri)

    fun convertImageFormat(sourceFile: File, targetFormatName: String, context: Context): File? =
        DocumentExporters.convertImageFormat(sourceFile, targetFormatName, context)

    fun exportChatHistory(context: Context, messages: List<ChatMessage>): File =
        DocumentExporters.exportChatHistory(context, messages)

    fun createRealPdf(context: Context, title: String, content: String): File? =
        DocumentExporters.createRealPdf(context, title, content)

    fun convertImageToPdf(context: Context, imageUri: Uri, title: String = "Exported Image Document"): File? =
        DocumentExporters.convertImageToPdf(context, imageUri, title)

    fun exportMarkdownDocument(context: Context, title: String, content: String): File =
        DocumentExporters.exportMarkdownDocument(context, title, content)

    fun exportWordDocDocument(context: Context, title: String, content: String): File =
        DocumentExporters.exportWordDocDocument(context, title, content)

    fun exportRealDocxDocument(context: Context, title: String, content: String): File =
        DocumentExporters.exportRealDocxDocument(context, title, content)

    fun exportCsvSpreadsheet(context: Context, title: String, content: String): File =
        DocumentExporters.exportCsvSpreadsheet(context, title, content)

    fun exportSafeWorkspaceBackupJson(
        context: Context,
        sessions: List<ChatSession>,
        messages: List<ChatMessage>,
        plugins: List<InstalledGitHubPlugin>
    ): String = DocumentExporters.exportSafeWorkspaceBackupJson(context, sessions, messages, plugins)

    fun importSafeWorkspaceBackupJson(context: Context, jsonString: String): WorkspaceBackupPayload =
        DocumentExporters.importSafeWorkspaceBackupJson(context, jsonString)
}
