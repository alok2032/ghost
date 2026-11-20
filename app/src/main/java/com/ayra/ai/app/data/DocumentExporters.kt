package com.ayra.ai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.ayra.ai.app.security.EncryptedVaultStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Document, PDF, Word, Markdown, Image, and Safe Workspace Backup exporters.
 */
object DocumentExporters {

    fun convertImageFormat(sourceFile: File, targetFormatName: String, context: Context): File? {
        return try {
            val rawBytes = if (EncryptedVaultStorage.isVaultFile(sourceFile)) {
                EncryptedVaultStorage.decryptVaultFileBytes(context, sourceFile)
            } else {
                sourceFile.readBytes()
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            val compressFormat = when (targetFormatName.uppercase(Locale.US)) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WEBP" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.JPEG
            }
            val ext = targetFormatName.lowercase(Locale.US)
            val baos = ByteArrayOutputStream()
            bitmap.compress(compressFormat, 95, baos)
            bitmap.recycle()
            val outBytes = baos.toByteArray()
            val vaultFile = EncryptedVaultStorage.storeEncryptedBytes(
                context = context,
                folder = "generated_images",
                originalName = "converted_${System.currentTimeMillis()}.$ext",
                mimeType = "image/$ext",
                plaintextBytes = outBytes
            )
            outBytes.fill(0)
            vaultFile
        } catch (_: Exception) {
            null
        }
    }

    fun exportChatHistory(context: Context, messages: List<ChatMessage>): File {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timeFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        val text = buildString {
            append("=====================================================\n")
            append("Ayra AI • Chat & Prompt History Export\n")
            append("Exported on: ${timeFormatter.format(Date())}\n")
            append("Total Messages: ${messages.size}\n")
            append("=====================================================\n\n")

            messages.forEach { msg ->
                val sender = if (msg.role == "user") "YOU" else "AYRA AI"
                val time = timeFormatter.format(Date(msg.timestamp))
                append("[$time] $sender:\n")
                if (!msg.attachedImagePath.isNullOrBlank()) {
                    append("📎 Attachment: ${msg.attachedImagePath}\n")
                }
                append("${msg.content}\n")
                if (!msg.craftType.isNullOrBlank()) {
                    append("🎨 Generative Craft: ${msg.craftType}\n")
                }
                append("-----------------------------------------------------\n\n")
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = EncryptedVaultStorage.storeEncryptedBytes(
            context = context,
            folder = "chat_exports",
            originalName = "Ayra_AI_Chat_History_${sdf.format(Date())}.txt",
            mimeType = "text/plain",
            plaintextBytes = bytes
        )
        bytes.fill(0)
        return file
    }

    fun createRealPdf(context: Context, title: String, content: String): File? {
        return try {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val pageWidth = 595
            val pageHeight = 842

            val paint = android.graphics.Paint().apply { isAntiAlias = true }
            val textPaint = android.text.TextPaint().apply {
                isAntiAlias = true
                textSize = 11.5f
                color = android.graphics.Color.DKGRAY
            }
            val titlePaint = android.text.TextPaint().apply {
                isAntiAlias = true
                textSize = 17f
                isFakeBoldText = true
                color = android.graphics.Color.BLACK
            }
            val headerPaint = android.text.TextPaint().apply {
                isAntiAlias = true
                textSize = 13f
                isFakeBoldText = true
                color = android.graphics.Color.rgb(0, 102, 204)
            }

            val margin = 40f
            val bottomMargin = 48f
            val maxTextWidth = (pageWidth - 2 * margin).toInt()

            data class LayoutBlock(val layout: android.text.StaticLayout, val spacingAfter: Float)

            fun buildLayout(rawText: String, tp: android.text.TextPaint): android.text.StaticLayout {
                return android.text.StaticLayout.Builder
                    .obtain(rawText, 0, rawText.length, tp, maxTextWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.15f)
                    .setIncludePad(false)
                    .build()
            }

            val blocks = mutableListOf<LayoutBlock>()
            for (paragraph in content.split("\n")) {
                val trimmed = paragraph.trimEnd()
                when {
                    trimmed.isBlank() -> blocks.add(LayoutBlock(buildLayout(" ", textPaint), 6f))
                    trimmed.startsWith("#") -> {
                        val heading = trimmed.replace(Regex("^#+\\s*"), "")
                        if (heading.isNotBlank()) blocks.add(LayoutBlock(buildLayout(heading, headerPaint), 8f))
                    }
                    else -> {
                        val cleanParagraph = trimmed.replace("**", "")
                        blocks.add(LayoutBlock(buildLayout(cleanParagraph, textPaint), 5f))
                    }
                }
            }

            var blockIdx = 0
            var pageNum = 1

            while (blockIdx < blocks.size || pageNum == 1) {
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                paint.color = android.graphics.Color.rgb(240, 244, 250)
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), 45f, paint)

                paint.color = android.graphics.Color.rgb(0, 102, 204)
                paint.textSize = 10f
                paint.isFakeBoldText = true
                canvas.drawText("AYRA AI • ENCRYPTED DOCUMENT STUDIO", margin, 28f, paint)

                val pageNumStr = "Page $pageNum"
                canvas.drawText(pageNumStr, pageWidth - margin - paint.measureText(pageNumStr), 28f, paint)

                var y = 65f
                if (pageNum == 1) {
                    val titleLayout = buildLayout(title, titlePaint)
                    canvas.save()
                    canvas.translate(margin, y)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    y += titleLayout.height + 10f
                    paint.color = android.graphics.Color.rgb(0, 150, 255)
                    paint.strokeWidth = 2f
                    canvas.drawLine(margin, y - 4f, pageWidth - margin, y - 4f, paint)
                    y += 12f
                }

                while (blockIdx < blocks.size) {
                    val block = blocks[blockIdx]
                    if (y + block.layout.height > pageHeight - bottomMargin && y > 90f) break
                    canvas.save()
                    canvas.translate(margin, y)
                    block.layout.draw(canvas)
                    canvas.restore()
                    y += block.layout.height + block.spacingAfter
                    blockIdx++
                }

                pdfDocument.finishPage(page)
                pageNum++
                if (blockIdx >= blocks.size) break
            }

            val baos = ByteArrayOutputStream()
            pdfDocument.writeTo(baos)
            pdfDocument.close()
            val pdfBytes = baos.toByteArray()

            val sanitized = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(24).ifBlank { "doc" }
            val vaultFile = EncryptedVaultStorage.storeEncryptedBytes(
                context = context,
                folder = "generated_pdfs",
                originalName = "Ayra_AI_${sanitized}_${System.currentTimeMillis()}.pdf",
                mimeType = "application/pdf",
                plaintextBytes = pdfBytes
            )
            pdfBytes.fill(0)
            vaultFile
        } catch (_: Exception) {
            null
        }
    }

    fun convertImageToPdf(context: Context, imageUri: Uri, title: String = "Exported Image Document"): File? {
        return try {
            val input = StorageHelper.openReadableAttachmentStream(context, imageUri.toString()) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(input)
            input.close()
            if (bitmap == null) return null

            val pdfDocument = android.graphics.pdf.PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val margin = 36f
            val availWidth = pageWidth - 2 * margin
            val availHeight = pageHeight - 100f

            val scale = (availWidth / bitmap.width).coerceAtMost(availHeight / bitmap.height)
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val destRect = android.graphics.RectF(
                margin + (availWidth - scaledWidth) / 2,
                70f + (availHeight - scaledHeight) / 2,
                margin + (availWidth - scaledWidth) / 2 + scaledWidth,
                70f + (availHeight - scaledHeight) / 2 + scaledHeight
            )

            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 16f
                isFakeBoldText = true
                color = android.graphics.Color.BLACK
            }
            canvas.drawText(title, margin, 45f, textPaint)
            canvas.drawBitmap(bitmap, null, destRect, null)

            pdfDocument.finishPage(page)
            bitmap.recycle()

            val baos = ByteArrayOutputStream()
            pdfDocument.writeTo(baos)
            pdfDocument.close()
            val pdfBytes = baos.toByteArray()

            val vaultFile = EncryptedVaultStorage.storeEncryptedBytes(
                context = context,
                folder = "generated_pdfs",
                originalName = "Image_To_PDF_${System.currentTimeMillis()}.pdf",
                mimeType = "application/pdf",
                plaintextBytes = pdfBytes
            )
            pdfBytes.fill(0)
            vaultFile
        } catch (_: Exception) {
            null
        }
    }

    fun exportMarkdownDocument(context: Context, title: String, content: String): File {
        val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "ayra_doc" }
        val body = buildString {
            append("# ").append(title.ifBlank { "Ayra AI Document" }).append("\n\n")
            append("_Generated by Ayra AI Research & Document Studio_\n\n---\n\n")
            append(content)
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val file = EncryptedVaultStorage.storeEncryptedBytes(
            context = context,
            folder = "generated_docs",
            originalName = "${safe}_${System.currentTimeMillis()}.md",
            mimeType = "text/markdown",
            plaintextBytes = bytes
        )
        bytes.fill(0)
        return file
    }

    fun exportWordDocDocument(context: Context, title: String, content: String): File {
        val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "ayra_doc" }

        fun escapeXml(raw: String): String = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val paragraphsXml = content.split("\n").joinToString("\n") { line ->
            val trimmed = line.trimEnd()
            if (trimmed.startsWith("#")) {
                val headingText = escapeXml(trimmed.replace(Regex("^#+\\s*"), ""))
                """<w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>$headingText</w:t></w:r></w:p>"""
            } else {
                """<w:p><w:r><w:t xml:space="preserve">${escapeXml(trimmed)}</w:t></w:r></w:p>"""
            }
        }

        val wordXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <?mso-application progid="Word.Document"?>
            <w:wordDocument xmlns:w="http://schemas.microsoft.com/office/word/2003/wordml">
              <w:body>
                <w:p><w:r><w:rPr><w:b/><w:sz w:val="36"/></w:rPr><w:t>${escapeXml(title.ifBlank { "Ayra AI Document" })}</w:t></w:r></w:p>
                $paragraphsXml
              </w:body>
            </w:wordDocument>
        """.trimIndent()

        val bytes = wordXml.toByteArray(Charsets.UTF_8)
        val file = EncryptedVaultStorage.storeEncryptedBytes(
            context = context,
            folder = "generated_docs",
            originalName = "${safe}_${System.currentTimeMillis()}.doc",
            mimeType = "application/msword",
            plaintextBytes = bytes
        )
        bytes.fill(0)
        return file
    }

    /**
     * Generates a genuine OOXML ZIP `.docx` Word document (`[Content_Types].xml`, `_rels/.rels`, `word/document.xml`)
     * and stores it in the AES-256-GCM encrypted vault.
     */
    fun exportRealDocxDocument(context: Context, title: String, content: String): File {
        val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "ayra_doc" }

        fun escapeXml(raw: String): String = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val paragraphsXml = buildString {
            append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="36"/></w:rPr><w:t>${escapeXml(title.ifBlank { "Ayra AI Document" })}</w:t></w:r></w:p>""")
            content.split("\n").forEach { line ->
                val trimmed = line.trimEnd()
                if (trimmed.startsWith("#")) {
                    val headingText = escapeXml(trimmed.replace(Regex("^#+\\s*"), ""))
                    append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="28"/></w:rPr><w:t>$headingText</w:t></w:r></w:p>""")
                } else {
                    append("""<w:p><w:r><w:t xml:space="preserve">${escapeXml(trimmed)}</w:t></w:r></w:p>""")
                }
            }
        }

        val contentTypesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()

        val relsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()

        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $paragraphsXml
                <w:sectPr/>
              </w:body>
            </w:document>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            fun putEntry(name: String, xml: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            putEntry("[Content_Types].xml", contentTypesXml)
            putEntry("_rels/.rels", relsXml)
            putEntry("word/document.xml", documentXml)
        }

        val bytes = baos.toByteArray()
        val file = EncryptedVaultStorage.storeEncryptedBytes(
            context = context,
            folder = "generated_docs",
            originalName = "${safe}_${System.currentTimeMillis()}.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            plaintextBytes = bytes
        )
        bytes.fill(0)
        return file
    }

    /**
     * Exports structured tables or lines as an RFC-4180 CSV spreadsheet with formula-injection neutralization.
     */
    fun exportCsvSpreadsheet(context: Context, title: String, content: String): File {
        val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "ayra_table" }

        fun sanitizeCsvCell(raw: String): String {
            var trimmed = raw.trim()
            if (trimmed.isNotEmpty() && trimmed[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
                trimmed = "'$trimmed"
            }
            val escaped = trimmed.replace("\"", "\"\"")
            return "\"$escaped\""
        }

        val csvBody = buildString {
            val lines = content.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val mdTableRows = lines.filter { it.startsWith("|") && it.endsWith("|") && !it.replace("|", "").replace("-", "").replace(":", "").isBlank() }
            if (mdTableRows.isNotEmpty()) {
                mdTableRows.forEach { row ->
                    val cells = row.trim('|').split("|").map { sanitizeCsvCell(it) }
                    append(cells.joinToString(",")).append("\n")
                }
            } else {
                append("\"Line\",\"Content\"\n")
                lines.forEachIndexed { idx, line ->
                    append("${idx + 1},${sanitizeCsvCell(line)}\n")
                }
            }
        }

        val bytes = csvBody.toByteArray(Charsets.UTF_8)
        val file = EncryptedVaultStorage.storeEncryptedBytes(
            context = context,
            folder = "generated_docs",
            originalName = "${safe}_${System.currentTimeMillis()}.csv",
            mimeType = "text/csv",
            plaintextBytes = bytes
        )
        bytes.fill(0)
        return file
    }

    fun exportSafeWorkspaceBackupJson(
        context: Context,
        sessions: List<ChatSession>,
        messages: List<ChatMessage>,
        plugins: List<InstalledGitHubPlugin>
    ): String {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("app", "Ayra AI")
            put("exportedAtMs", System.currentTimeMillis())
            put("containsSecrets", false)

            put("preferences", JSONObject().apply {
                put("personaMode", AyraPreferences.getSavedPersona(context))
                put("routingMode", AyraPreferences.getRoutingMode(context))
                put("preferredLanguage", AyraPreferences.getPreferredLanguage(context))
                put("themeMode", AyraPreferences.getThemeMode(context))
                put("searchGroundingEnabled", AyraPreferences.isSearchGroundingEnabled(context))
                put("freeAiPoolEnabled", AyraPreferences.isFreeAiPoolEnabled(context))
                put("memoryEnabled", AyraPreferences.isMemoryEnabled(context))
                put("customInstructions", AyraPreferences.getUserCustomInstructions(context))
                put("rememberedFacts", JSONArray(AyraPreferences.getRememberedFacts(context).toList()))
            })

            put("sessions", JSONArray().apply {
                sessions.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("personaMode", s.personaMode)
                        put("modelTier", s.modelTier)
                        put("isPinned", s.isPinned)
                        put("createdAt", s.createdAt)
                        put("updatedAt", s.updatedAt)
                        put("messageCount", s.messageCount)
                        put("lastMessagePreview", s.lastMessagePreview)
                    })
                }
            })

            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("sessionId", m.sessionId)
                        put("role", m.role)
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                        put("craftType", m.craftType ?: "")
                        put("craftPayload", m.craftPayload ?: "")
                        put("actionCommand", m.actionCommand ?: "")
                    })
                }
            })

            put("plugins", JSONArray().apply {
                plugins.forEach { p ->
                    put(JSONObject().apply {
                        put("repoFullName", p.repoFullName)
                        put("repoUrl", p.repoUrl)
                        put("name", p.name)
                        put("owner", p.owner)
                        put("description", p.description)
                        put("defaultBranch", p.defaultBranch)
                        put("stars", p.stars)
                        put("primaryLanguage", p.primaryLanguage)
                        put("pluginType", p.pluginType)
                        put("topicsJson", p.topicsJson)
                        put("capabilitiesSummary", p.capabilitiesSummary)
                        put("skillPromptContext", p.skillPromptContext)
                        put("openApiSpecJson", p.openApiSpecJson)
                        put("commandsJson", p.commandsJson)
                        put("fileTreeJson", p.fileTreeJson)
                        put("isEnabled", p.isEnabled)
                        put("installedAt", p.installedAt)
                        put("lastSyncedAt", p.lastSyncedAt)
                    })
                }
            })
        }
        return root.toString(2)
    }

    fun importSafeWorkspaceBackupJson(context: Context, jsonString: String): StorageHelper.WorkspaceBackupPayload {
        val root = JSONObject(jsonString)
        val prefs = root.optJSONObject("preferences")
        if (prefs != null) {
            if (prefs.has("personaMode")) AyraPreferences.savePersona(context, prefs.optString("personaMode", "AI_ASSISTANT"))
            if (prefs.has("routingMode")) AyraPreferences.setRoutingMode(context, prefs.optString("routingMode", "auto"))
            if (prefs.has("preferredLanguage")) AyraPreferences.setPreferredLanguage(context, prefs.optString("preferredLanguage", "Auto"))
            if (prefs.has("themeMode")) AyraPreferences.setThemeMode(context, prefs.optString("themeMode", "dark"))
            if (prefs.has("searchGroundingEnabled")) AyraPreferences.setSearchGroundingEnabled(context, prefs.optBoolean("searchGroundingEnabled", true))
            if (prefs.has("freeAiPoolEnabled")) AyraPreferences.setFreeAiPoolEnabled(context, prefs.optBoolean("freeAiPoolEnabled", false))
            if (prefs.has("memoryEnabled")) AyraPreferences.setMemoryEnabled(context, prefs.optBoolean("memoryEnabled", false))
            if (prefs.has("customInstructions")) AyraPreferences.setUserCustomInstructions(context, prefs.optString("customInstructions", ""))
            val factsArr = prefs.optJSONArray("rememberedFacts")
            if (factsArr != null) {
                for (i in 0 until factsArr.length()) {
                    val fact = factsArr.optString(i).trim()
                    if (fact.isNotBlank()) AyraPreferences.addRememberedFact(context, fact)
                }
            }
        }

        val sessionsList = mutableListOf<ChatSession>()
        val sArr = root.optJSONArray("sessions")
        if (sArr != null) {
            for (i in 0 until sArr.length()) {
                val o = sArr.optJSONObject(i) ?: continue
                sessionsList.add(
                    ChatSession(
                        id = o.optLong("id", 0L),
                        title = o.optString("title", "Restored Session"),
                        personaMode = o.optString("personaMode", "AI_ASSISTANT"),
                        modelTier = o.optString("modelTier", "auto"),
                        isPinned = o.optBoolean("isPinned", false),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        messageCount = o.optInt("messageCount", 0),
                        lastMessagePreview = o.optString("lastMessagePreview", "")
                    )
                )
            }
        }

        val messagesList = mutableListOf<ChatMessage>()
        val mArr = root.optJSONArray("messages")
        if (mArr != null) {
            for (i in 0 until mArr.length()) {
                val o = mArr.optJSONObject(i) ?: continue
                messagesList.add(
                    ChatMessage(
                        sessionId = o.optLong("sessionId", 1L),
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        craftType = o.optString("craftType", "").ifBlank { null },
                        craftPayload = o.optString("craftPayload", "").ifBlank { null },
                        actionCommand = o.optString("actionCommand", "").ifBlank { null }
                    )
                )
            }
        }

        val pluginsList = mutableListOf<InstalledGitHubPlugin>()
        val pArr = root.optJSONArray("plugins")
        if (pArr != null) {
            for (i in 0 until pArr.length()) {
                val o = pArr.optJSONObject(i) ?: continue
                val repoFullName = o.optString("repoFullName", "").trim()
                if (repoFullName.isBlank()) continue
                pluginsList.add(
                    InstalledGitHubPlugin(
                        repoFullName = repoFullName,
                        repoUrl = o.optString("repoUrl", "https://github.com/$repoFullName"),
                        name = o.optString("name", repoFullName.substringAfter("/")),
                        owner = o.optString("owner", repoFullName.substringBefore("/")),
                        description = o.optString("description", ""),
                        defaultBranch = o.optString("defaultBranch", "main"),
                        stars = o.optInt("stars", 0),
                        primaryLanguage = o.optString("primaryLanguage", "Unknown"),
                        pluginType = o.optString("pluginType", "CODE_PLUGIN"),
                        topicsJson = o.optString("topicsJson", "[]"),
                        capabilitiesSummary = o.optString("capabilitiesSummary", ""),
                        skillPromptContext = o.optString("skillPromptContext", ""),
                        openApiSpecJson = o.optString("openApiSpecJson", "[]"),
                        commandsJson = o.optString("commandsJson", "[]"),
                        fileTreeJson = o.optString("fileTreeJson", "[]"),
                        isEnabled = o.optBoolean("isEnabled", true),
                        installedAt = o.optLong("installedAt", System.currentTimeMillis()),
                        lastSyncedAt = o.optLong("lastSyncedAt", System.currentTimeMillis())
                    )
                )
            }
        }

        return StorageHelper.WorkspaceBackupPayload(sessionsList, messagesList, pluginsList)
    }
}
