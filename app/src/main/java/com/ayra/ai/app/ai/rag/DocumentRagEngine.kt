package com.ayra.ai.app.ai.rag

import com.ayra.ai.app.data.DocumentChunkEntity
import com.ayra.ai.app.data.KnowledgeDocumentDao
import com.ayra.ai.app.data.KnowledgeDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.ln

data class RagSearchHit(
    val documentId: Long,
    val documentTitle: String,
    val chunkIndex: Int,
    val excerpt: String,
    val score: Double
) {
    val citationTag: String get() = "[Doc: $documentTitle §${chunkIndex + 1}]"
}

/**
 * Persistent Multi-Document Retrieval-Augmented Generation (RAG) Engine backed by Room.
 * Splits uploaded PDFs/docs into overlapping chunks, persists them in SQLite,
 * and ranks relevant sections using BM25/TF-IDF lexical scoring with explicit source citations.
 */
class DocumentRagEngine @JvmOverloads constructor(
    private val dao: KnowledgeDocumentDao,
    private val context: android.content.Context? = null
) {

    fun observeDocuments(): Flow<List<KnowledgeDocumentEntity>> = dao.observeAllDocuments()

    suspend fun ingestDocument(
        title: String,
        mimeType: String,
        rawText: String,
        sourceUri: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "Untitled Document" }
        val normalizedText = rawText.trim()
        val chunks = chunkText(normalizedText)

        val docId = dao.insertDocument(
            KnowledgeDocumentEntity(
                title = cleanTitle,
                mimeType = mimeType,
                charCount = normalizedText.length,
                chunkCount = chunks.size,
                sourceUri = sourceUri
            )
        )

        val entities = chunks.mapIndexed { idx, chunk ->
            DocumentChunkEntity(
                documentId = docId,
                documentTitle = cleanTitle,
                chunkIndex = idx,
                content = chunk,
                tokenEstimate = tokenize(chunk).size
            )
        }
        if (entities.isNotEmpty()) {
            dao.insertChunks(entities)
        }
        docId
    }

    suspend fun deleteDocument(documentId: Long) = withContext(Dispatchers.IO) {
        dao.deleteDocumentById(documentId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAllDocuments()
    }

    suspend fun searchRelevantChunks(
        query: String,
        topK: Int = 4,
        documentIdFilter: Long? = null
    ): List<RagSearchHit> = withContext(Dispatchers.IO) {
        val chunks = if (documentIdFilter != null) {
            dao.getChunksForDocument(documentIdFilter)
        } else {
            dao.getAllChunksSync()
        }
        if (chunks.isEmpty()) return@withContext emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) {
            return@withContext chunks.take(topK).map {
                RagSearchHit(it.documentId, it.documentTitle, it.chunkIndex, it.content, 1.0)
            }
        }

        val totalDocs = chunks.size.toDouble()
        val avgLen = chunks.map { tokenize(it.content).size }.average().coerceAtLeast(1.0)

        // Document frequency per query term
        val dfMap = mutableMapOf<String, Int>()
        val chunkTokensList = chunks.map { tokenize(it.content) }
        for (term in queryTerms.distinct()) {
            dfMap[term] = chunkTokensList.count { tokens -> term in tokens }
        }

        val k1 = 1.5
        val b = 0.75

        val scored = chunks.mapIndexed { idx, chunk ->
            val docTokens = chunkTokensList[idx]
            val docLen = docTokens.size.toDouble().coerceAtLeast(1.0)
            val freqMap = docTokens.groupingBy { it }.eachCount()

            var bm25 = 0.0
            for (term in queryTerms) {
                val tf = (freqMap[term] ?: 0).toDouble()
                if (tf > 0.0) {
                    val df = (dfMap[term] ?: 0).toDouble()
                    val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
                    val numer = tf * (k1 + 1.0)
                    val denom = tf + k1 * (1.0 - b + b * (docLen / avgLen))
                    bm25 += idf * (numer / denom)
                }
            }

            // Phrase / substring boost
            val lowerContent = chunk.content.lowercase()
            val lowerQuery = query.trim().lowercase()
            if (lowerQuery.length >= 4 && lowerContent.contains(lowerQuery)) {
                bm25 += 3.0
            }

            RagSearchHit(
                documentId = chunk.documentId,
                documentTitle = chunk.documentTitle,
                chunkIndex = chunk.chunkIndex,
                excerpt = chunk.content,
                score = bm25
            )
        }

        val positive = scored.filter { it.score > 0.0 }.sortedByDescending { it.score }
        if (positive.isNotEmpty()) {
            positive.take(topK)
        } else {
            // Fallback to leading chunks if query used synonyms not lexically present
            chunks.take(topK.coerceAtMost(2)).map {
                RagSearchHit(it.documentId, it.documentTitle, it.chunkIndex, it.content, 0.05)
            }
        }
    }

    suspend fun buildCitedContext(query: String, topK: Int = 4): Pair<String, List<RagSearchHit>> {
        val hits = searchRelevantChunks(query, topK)
        if (hits.isEmpty()) return "" to emptyList()
        val formatted = buildString {
            append("[KNOWLEDGE BASE RAG CONTEXT — CITE USING TAGS BELOW]\n")
            hits.forEach { hit ->
                val guarded = com.ayra.ai.app.security.AiToolSecurityGuard.inspectAndWrapUntrustedData(
                    context = context,
                    sourceLabel = hit.citationTag,
                    rawContent = hit.excerpt
                )
                append(hit.citationTag).append("\n")
                append(guarded.wrappedUntrustedContent).append("\n\n")
            }
        }
        return formatted to hits
    }

    companion object {
        private val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "in", "on", "at", "to", "for",
            "of", "and", "or", "with", "this", "that", "it", "by", "from", "as", "what",
            "how", "why", "who", "when", "where", "can", "you", "me", "my", "about"
        )

        fun chunkText(text: String, chunkSizeChars: Int = 900, overlapChars: Int = 150): List<String> {
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()
            if (clean.length <= chunkSizeChars) return listOf(clean)

            val result = mutableListOf<String>()
            var start = 0
            while (start < clean.length) {
                var end = (start + chunkSizeChars).coerceAtMost(clean.length)
                if (end < clean.length) {
                    val lastBreak = clean.lastIndexOf('\n', end).takeIf { it > start + chunkSizeChars / 2 }
                        ?: clean.lastIndexOf(' ', end).takeIf { it > start + chunkSizeChars / 2 }
                    if (lastBreak != null) end = lastBreak
                }
                val slice = clean.substring(start, end).trim()
                if (slice.isNotEmpty()) result.add(slice)
                if (end >= clean.length) break
                start = (end - overlapChars).coerceAtLeast(start + 1)
            }
            return result
        }

        fun tokenize(text: String): List<String> {
            return text.lowercase()
                .split(Regex("[^\\p{L}\\p{N}_]+"))
                .filter { it.length >= 2 && it !in stopWords }
        }
    }
}
