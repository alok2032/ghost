package com.ayra.ai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import com.ayra.ai.app.ai.DataAnalysisEngine
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.security.EncryptedVaultStorage
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Encrypted & Hardened device storage manager for Ayra AI.
 * - All saved attachments, chat exports, PDFs, Markdown, Word exports, and media artifacts
 *   are stored via [EncryptedVaultStorage] (per-file 256-bit DEK AES-256-GCM + Keystore KEK).
 * - Decryption for Share/Open actions materializes temporary cache copies solely inside
 *   `cacheDir/exports` via [prepareShareableFile] and purges temporary files automatically.
 */
object StorageHelper {

    fun sanitizeFileName(rawName: String): String {
        val base = rawName.substringAfterLast("/").substringAfterLast("\\")
        val sanitized = base.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        return if (sanitized.trim('_').isBlank()) "attachment_${System.currentTimeMillis()}.dat" else sanitized
    }

    /**
     * Encrypts and stores a picked file from [uri] inside the AYRA Encrypted File Vault.
     */
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

    /**
     * Resolves an attachment URI/path into a decrypted [InputStream] when stored as `.ayravlt`,
     * or falls back safely to ContentResolver/File stream.
     */
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

    /**
     * Returns in-memory decrypted bytes for encrypted `.ayravlt` images so Coil renders
     * them securely without writing plaintext copies to internal storage.
     */
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

    /**
     * Materializes a decrypted temporary copy in `cacheDir/exports/` strictly for external Android
     * `FileProvider` share/view intents, keeping persistent vault artifacts encrypted on disk.
     */
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

    fun extractTextFromDocument(context: Context, uri: Uri, fileName: String?): String {
        val name = fileName ?: getFileName(context, uri) ?: "document"
        val lower = name.lowercase(Locale.ROOT)

        if (lower.endsWith(".pdf")) {
            return extractTextFromPdf(context, uri)
        }

        val maxChars = 50_000
        return try {
            val extracted = when {
                lower.endsWith(".docx") -> context.contentResolver.openInputStream(uri)?.use { extractDocxText(it) }.orEmpty()
                lower.endsWith(".xlsx") -> context.contentResolver.openInputStream(uri)?.use { extractXlsxText(it, name) }.orEmpty()
                lower.endsWith(".pptx") -> context.contentResolver.openInputStream(uri)?.use { extractPptxText(it) }.orEmpty()
                else -> {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
                        val stats = DataAnalysisEngine.summarizeTabularText(raw, name)
                        "$stats\n\n### Raw Table Preview\n$raw"
                    } else {
                        raw
                    }
                }
            }

            if (extracted.isBlank()) {
                "Document `$name` yielded no readable text content."
            } else if (extracted.length > maxChars) {
                extracted.take(maxChars) + "\n\n[Notice: Document text truncated at 50,000 characters for optimal AI model context processing.]"
            } else {
                extracted
            }
        } catch (e: Exception) {
            "Unable to read file contents: ${e.message}"
        }
    }

    internal fun extractDocxText(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(StringReader(xml))
                    val sb = StringBuilder()
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                val tag = parser.name?.substringAfter(":")
                                if (tag == "t") {
                                    sb.append(parser.nextText())
                                } else if (tag == "tab") {
                                    sb.append("\t")
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                val tag = parser.name?.substringAfter(":")
                                if (tag == "p" || tag == "tr") {
                                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
                                }
                            }
                        }
                        event = parser.next()
                    }
                    return sb.toString().trim()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ""
    }

    internal fun extractPptxText(input: InputStream): String {
        val slides = linkedMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name.lowercase(Locale.ROOT)
                if (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(StringReader(xml))
                    val slideSb = StringBuilder()
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(":") == "t") {
                            val txt = parser.nextText().trim()
                            if (txt.isNotEmpty()) slideSb.append(txt).append(" ")
                        } else if (event == XmlPullParser.END_TAG && parser.name?.substringAfter(":") == "p") {
                            if (slideSb.isNotEmpty() && !slideSb.endsWith("\n")) slideSb.append("\n")
                        }
                        event = parser.next()
                    }
                    if (slideSb.isNotBlank()) slides[entry.name] = slideSb.toString().trim()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return slides.entries
            .sortedBy { it.key }
            .mapIndexed { idx, entry -> "## Slide ${idx + 1}\n${entry.value}" }
            .joinToString("\n\n")
    }

    internal fun extractXlsxText(input: InputStream, fileName: String = "workbook.xlsx"): String {
        val sharedStrings = mutableListOf<String>()
        val sheetXmls = linkedMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val lowerName = entry.name.lowercase(Locale.ROOT)
                when {
                    lowerName == "xl/sharedstrings.xml" -> {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val parser = XmlPullParserFactory.newInstance().newPullParser()
                        parser.setInput(StringReader(xml))
                        var currentItem = StringBuilder()
                        var insideSi = false
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            when (event) {
                                XmlPullParser.START_TAG -> {
                                    val tag = parser.name?.substringAfter(":")
                                    if (tag == "si") {
                                        insideSi = true
                                        currentItem = StringBuilder()
                                    } else if (insideSi && tag == "t") {
                                        currentItem.append(parser.nextText())
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (parser.name?.substringAfter(":") == "si") {
                                        sharedStrings.add(currentItem.toString())
                                        insideSi = false
                                    }
                                }
                            }
                            event = parser.next()
                        }
                    }
                    lowerName.startsWith("xl/worksheets/sheet") && lowerName.endsWith(".xml") -> {
                        sheetXmls[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val sb = StringBuilder()
        sheetXmls.entries.sortedBy { it.key }.forEachIndexed { idx, (_, xml) ->
            val rows = mutableListOf<List<String>>()
            var currentRow = mutableListOf<String>()
            var cellType = ""
            var cellValue = ""
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name?.substringAfter(":")) {
                            "row" -> currentRow = mutableListOf()
                            "c" -> {
                                cellType = parser.getAttributeValue(null, "t").orEmpty()
                                cellValue = ""
                            }
                            "v", "t" -> cellValue = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name?.substringAfter(":")) {
                            "c" -> {
                                val resolved = if (cellType == "s") {
                                    val ssIdx = cellValue.toIntOrNull()
                                    if (ssIdx != null && ssIdx in sharedStrings.indices) sharedStrings[ssIdx] else cellValue
                                } else cellValue
                                currentRow.add(resolved.replace(",", " "))
                            }
                            "row" -> if (currentRow.isNotEmpty()) rows.add(currentRow)
                        }
                    }
                }
                event = parser.next()
            }
            if (rows.isNotEmpty()) {
                val csvTable = rows.joinToString("\n") { it.joinToString(",") }
                sb.append("### Sheet ${idx + 1}\n")
                sb.append(DataAnalysisEngine.summarizeTabularText(csvTable, "${fileName}_sheet${idx + 1}.csv"))
                sb.append("\n\n").append(rows.take(150).joinToString("\n") { it.joinToString(" | ") }).append("\n\n")
            }
        }
        return sb.toString().trim()
    }

    internal fun extractPdfTextTokensFromBytes(bytes: ByteArray): String {
        val sources = mutableListOf<String>()
        val rawLatin = String(bytes, Charsets.ISO_8859_1)
        sources.add(rawLatin)

        var searchIdx = 0
        while (true) {
            val streamTag = rawLatin.indexOf("stream", searchIdx)
            if (streamTag == -1) break
            var dataStart = streamTag + 6
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            val endTag = rawLatin.indexOf("endstream", dataStart)
            if (endTag == -1) break
            val headerLookback = rawLatin.substring((streamTag - 160).coerceAtLeast(0), streamTag)
            if (headerLookback.contains("FlateDecode")) {
                var dataEnd = endTag
                if (dataEnd > dataStart && bytes[dataEnd - 1] == '\n'.code.toByte()) dataEnd--
                if (dataEnd > dataStart && bytes[dataEnd - 1] == '\r'.code.toByte()) dataEnd--
                if (dataEnd > dataStart) {
                    val slice = bytes.copyOfRange(dataStart, dataEnd)
                    runCatching {
                        val inflater = Inflater()
                        inflater.setInput(slice)
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        var safety = 0
                        while (!inflater.finished() && safety < 256) {
                            val count = inflater.inflate(buf)
                            if (count <= 0) break
                            out.write(buf, 0, count)
                            safety++
                        }
                        inflater.end()
                        if (out.size() > 0) {
                            sources.add(String(out.toByteArray(), Charsets.ISO_8859_1))
                        }
                    }
                }
            }
            searchIdx = endTag + 9
        }

        val sb = StringBuilder()
        val tjRegex = Pattern.compile("\\(([^()\\\\]*)\\)\\s*(?:Tj|')")
        val arrayRegex = Pattern.compile("\\[([^\\]]*)\\]\\s*TJ")
        val innerRegex = Pattern.compile("\\(([^()\\\\]*)\\)")

        sources.forEach { src ->
            val matcher = tjRegex.matcher(src)
            while (matcher.find()) {
                val token = matcher.group(1)?.trim()
                if (!token.isNullOrBlank()) sb.append(token).append(" ")
            }
            val arrayMatcher = arrayRegex.matcher(src)
            while (arrayMatcher.find()) {
                val inner = arrayMatcher.group(1) ?: ""
                val innerMatcher = innerRegex.matcher(inner)
                while (innerMatcher.find()) {
                    val part = innerMatcher.group(1)?.trim()
                    if (!part.isNullOrBlank()) sb.append(part).append(" ")
                }
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun extractTextFromPdf(context: Context, uri: Uri): String {
        var tempFile: File? = null
        return try {
            tempFile = File(context.cacheDir, "pdf_inspect_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }

            val bytes = tempFile.readBytes()
            val extracted = extractPdfTextTokensFromBytes(bytes)

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            renderer.close()
            pfd.close()

            val maxChars = 50_000
            val truncatedText = if (extracted.length > maxChars) {
                extracted.take(maxChars) + "\n\n[Notice: PDF text truncated at 50,000 characters for optimal AI model context processing.]"
            } else {
                extracted
            }

            if (truncatedText.isNotBlank() && truncatedText.length > 15) {
                "[PDF Document - $pageCount Page(s)]\n\n$truncatedText"
            } else {
                "[PDF Document - $pageCount Page(s)]\nNo embedded plain-text stream found (likely a scanned image or outline font PDF — first page preview will be sent to Vision AI when available)."
            }
        } catch (e: Exception) {
            "[PDF Document]\nError extracting PDF text: ${e.message}"
        } finally {
            AndroidHardeningHelper.secureWipeFile(tempFile)
        }
    }

    fun renderPdfFirstPageToBase64(context: Context, uri: Uri): String? {
        var tempFile: File? = null
        return try {
            tempFile = File(context.cacheDir, "pdf_render_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = (page.width * 1.5).toInt().coerceAtMost(1600)
                val height = (page.height * 1.5).toInt().coerceAtMost(2200)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                bitmap.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                renderer.close()
                pfd.close()
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            AndroidHardeningHelper.secureWipeFile(tempFile)
        }
    }

    fun convertImageFormat(sourceFile: File, targetFormatName: String, context: Context): File? {
        return try {
            val rawBytes = if (EncryptedVaultStorage.isVaultFile(sourceFile)) {
                EncryptedVaultStorage.decryptVaultFileBytes(context, sourceFile)
            } else {
                sourceFile.readBytes()
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            val compressFormat = when (targetFormatName.uppercase()) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WEBP" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.JPEG
            }
            val ext = targetFormatName.lowercase()
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
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
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
            originalName = "Ayra_Chat_History_${sdf.format(Date())}.txt",
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
                originalName = "Ayra_${sanitized}_${System.currentTimeMillis()}.pdf",
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
            val input = openReadableAttachmentStream(context, imageUri.toString()) ?: return null
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

    // --- Saved Files & Encrypted Vault Artifacts Explorer ---

    data class WorkspaceArtifact(
        val name: String,
        val folder: String,
        val file: File,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val isEncrypted: Boolean = false
    )

    private val ARTIFACT_FOLDERS = listOf(
        "generated_pdfs",
        "generated_docs",
        "generated_images",
        "generated_audio",
        "generated_3d",
        "project_zips",
        "chat_exports",
        "saved_attachments",
        "code_projects"
    )

    fun listWorkspaceArtifacts(context: Context): List<WorkspaceArtifact> {
        val result = mutableListOf<WorkspaceArtifact>()

        // 1. Encrypted Vault Artifacts
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

        // 2. Remaining internal workspace directories (e.g., code_projects)
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

    fun deleteWorkspaceArtifact(file: File): Boolean {
        return AndroidHardeningHelper.secureWipeFile(file)
    }

    // --- Key-Free Local Workspace JSON Backup & Restore ---

    data class WorkspaceBackupPayload(
        val sessions: List<ChatSession>,
        val messages: List<ChatMessage>,
        val plugins: List<InstalledGitHubPlugin>
    )

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

    fun importSafeWorkspaceBackupJson(context: Context, jsonString: String): WorkspaceBackupPayload {
        val root = JSONObject(jsonString)
        val prefs = root.optJSONObject("preferences")
        if (prefs != null) {
            if (prefs.has("personaMode")) AyraPreferences.savePersona(context, prefs.optString("personaMode", "AI_ASSISTANT"))
            if (prefs.has("routingMode")) AyraPreferences.setRoutingMode(context, prefs.optString("routingMode", "auto"))
            if (prefs.has("preferredLanguage")) AyraPreferences.setPreferredLanguage(context, prefs.optString("preferredLanguage", "Auto"))
            if (prefs.has("themeMode")) AyraPreferences.setThemeMode(context, prefs.optString("themeMode", "dark"))
            if (prefs.has("searchGroundingEnabled")) AyraPreferences.setSearchGroundingEnabled(context, prefs.optBoolean("searchGroundingEnabled", true))
            if (prefs.has("freeAiPoolEnabled")) AyraPreferences.setFreeAiPoolEnabled(context, prefs.optBoolean("freeAiPoolEnabled", false))
            if (prefs.has("memoryEnabled")) AyraPreferences.setMemoryEnabled(context, prefs.optBoolean("memoryEnabled", true))
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

        return WorkspaceBackupPayload(sessionsList, messagesList, pluginsList)
    }
}
