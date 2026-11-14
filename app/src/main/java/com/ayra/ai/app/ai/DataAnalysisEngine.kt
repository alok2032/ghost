package com.ayra.ai.app.ai

import java.util.Locale
import kotlin.math.sqrt

/**
 * Deterministic statistical profiler for CSV/TSV datasets and spreadsheet exports.
 */
object DataAnalysisEngine {

    data class ColumnStats(
        val name: String,
        val nonEmptyCount: Int,
        val missingCount: Int,
        val isNumeric: Boolean,
        val min: Double? = null,
        val max: Double? = null,
        val mean: Double? = null,
        val median: Double? = null,
        val stdDev: Double? = null,
        val topCategories: List<Pair<String, Int>> = emptyList()
    )

    data class DatasetSummary(
        val fileName: String,
        val delimiter: Char,
        val rowCount: Int,
        val columnCount: Int,
        val columns: List<ColumnStats>
    )

    fun parseCsvLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    cells.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        cells.add(sb.toString().trim())
        return cells
    }

    fun splitCsvRecords(rawText: String): List<String> {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val records = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            when {
                ch == '"' -> {
                    sb.append(ch)
                    if (inQuotes && i + 1 < normalized.length && normalized[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == '\n' && !inQuotes -> {
                    val rec = sb.toString()
                    if (rec.isNotBlank()) records.add(rec)
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        val last = sb.toString()
        if (last.isNotBlank()) records.add(last)
        return records
    }

    private fun isMissingSentinel(raw: String): Boolean {
        if (raw.isEmpty()) return true
        return when (raw.lowercase(Locale.ROOT)) {
            "na", "n/a", "null", "none", "nan", "-" -> true
            else -> false
        }
    }

    fun analyzeTabularText(rawText: String, fileName: String = "dataset.csv"): DatasetSummary {
        val lines = splitCsvRecords(rawText)

        val delimiter = if (fileName.endsWith(".tsv", ignoreCase = true) ||
            (lines.firstOrNull()?.count { it == '\t' } ?: 0) > (lines.firstOrNull()?.count { it == ',' } ?: 0)
        ) '\t' else ','

        if (lines.isEmpty()) {
            return DatasetSummary(fileName, delimiter, 0, 0, emptyList())
        }

        val headers = parseCsvLine(lines.first(), delimiter)
            .mapIndexed { idx, h -> h.ifBlank { "col_${idx + 1}" } }
        val dataRows = lines.drop(1).map { parseCsvLine(it, delimiter) }
        val colCount = headers.size

        val stats = (0 until colCount).map { colIdx ->
            val colName = headers[colIdx]
            val values = dataRows.map { row -> row.getOrNull(colIdx)?.trim().orEmpty() }
            val nonMissing = values.filterNot(::isMissingSentinel)
            val missing = values.size - nonMissing.size
            val nums = nonMissing.mapNotNull { it.replace(",", "").toDoubleOrNull() }

            if (nonMissing.isNotEmpty() && nums.size == nonMissing.size) {
                val sorted = nums.sorted()
                val mean = nums.average()
                val mid = sorted.size / 2
                val median = if (sorted.size % 2 == 0) {
                    (sorted[mid - 1] + sorted[mid]) / 2.0
                } else {
                    sorted[mid]
                }
                val variance = if (nums.size > 1) {
                    nums.sumOf { (it - mean) * (it - mean) } / nums.size
                } else 0.0
                ColumnStats(
                    name = colName,
                    nonEmptyCount = nonMissing.size,
                    missingCount = missing,
                    isNumeric = true,
                    min = sorted.first(),
                    max = sorted.last(),
                    mean = mean,
                    median = median,
                    stdDev = sqrt(variance)
                )
            } else {
                val freq = nonMissing.groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key to it.value }
                ColumnStats(
                    name = colName,
                    nonEmptyCount = nonMissing.size,
                    missingCount = missing,
                    isNumeric = false,
                    topCategories = freq
                )
            }
        }

        return DatasetSummary(
            fileName = fileName,
            delimiter = delimiter,
            rowCount = dataRows.size,
            columnCount = colCount,
            columns = stats
        )
    }

    fun summarizeTabularText(rawText: String, fileName: String = "dataset.csv"): String {
        val summary = analyzeTabularText(rawText, fileName)
        if (summary.rowCount == 0 && summary.columnCount == 0) {
            return "[Dataset Profile: $fileName — Empty table]"
        }
        return buildString {
            append("### Dataset Statistical Profile: `${summary.fileName}`\n")
            append("- **Rows:** ${summary.rowCount} • **Columns:** ${summary.columnCount}\n\n")
            summary.columns.take(25).forEach { col ->
                if (col.isNumeric) {
                    append(
                        String.format(
                            Locale.US,
                            "- **%s** *(Numeric, n=%d, missing=%d)*: min=%.3f, max=%.3f, mean=%.3f, median=%.3f, std=%.3f\n",
                            col.name,
                            col.nonEmptyCount,
                            col.missingCount,
                            col.min ?: 0.0,
                            col.max ?: 0.0,
                            col.mean ?: 0.0,
                            col.median ?: 0.0,
                            col.stdDev ?: 0.0
                        )
                    )
                } else {
                    val cats = if (col.topCategories.isEmpty()) "none" else {
                        col.topCategories.joinToString(", ") { "${it.first} (${it.second})" }
                    }
                    append("- **${col.name}** *(Text/Category, n=${col.nonEmptyCount}, missing=${col.missingCount})*: top=[$cats]\n")
                }
            }
        }.trim()
    }
}
