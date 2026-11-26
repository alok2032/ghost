package com.ayra.ai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.ayra.ai.app.ai.DataAnalysisEngine
import com.ayra.ai.app.security.AndroidHardeningHelper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Document & Archive Extraction Engine for PDF, DOCX, XLSX, PPTX, CSV, and plain-text files.
 */
object DocumentParsers {
    private const val MAX_PDF_BYTES = 25 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 10 * 1024 * 1024

    private fun readBoundedStream(input: InputStream, maxBytes: Int, label: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("$label exceeds safety size cap ($maxBytes bytes).")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun extractTextFromDocument(context: Context, uri: Uri, fileName: String?): String {
        val name = fileName ?: StorageHelper.getFileName(context, uri) ?: "document"
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
                    val xml = readBoundedStream(zip, MAX_ZIP_ENTRY_BYTES, "DOCX entry").toString(Charsets.UTF_8)
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
                    val xml = readBoundedStream(zip, MAX_ZIP_ENTRY_BYTES, "PPTX slide entry").toString(Charsets.UTF_8)
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
                        val xml = readBoundedStream(zip, MAX_ZIP_ENTRY_BYTES, "XLSX sharedStrings").toString(Charsets.UTF_8)
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
                        sheetXmls[entry.name] = readBoundedStream(zip, MAX_ZIP_ENTRY_BYTES, "XLSX sheet entry").toString(Charsets.UTF_8)
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

    internal fun extractTextFromPdf(context: Context, uri: Uri): String {
        var tempFile: File? = null
        return try {
            tempFile = File(context.cacheDir, "pdf_inspect_${System.currentTimeMillis()}.pdf")
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                readBoundedStream(input, MAX_PDF_BYTES, "PDF document")
            } ?: ByteArray(0)
            FileOutputStream(tempFile).use { output -> output.write(bytes) }

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
}
