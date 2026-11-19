package com.ayra.ai.app.ai.rag

import android.content.Context
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.LayerVectorCount
import com.ayra.ai.app.data.VectorKnowledgeChunkEntity
import com.ayra.ai.app.data.VectorKnowledgeDao
import com.ayra.ai.app.data.VectorKnowledgeSourceEntity
import com.ayra.ai.app.security.AiToolSecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

data class VectorSearchHit(
    val sourceId: Long,
    val sourceKey: String,
    val title: String,
    val layer: String,
    val providerName: String,
    val sourceUrl: String,
    val originType: String,
    val excerpt: String,
    val cosineSimilarity: Double,
    val bm25Score: Double,
    val hybridScore: Double,
    val fetchedAt: Long
) {
    val similarityPercent: Int get() = (hybridScore * 100.0).toInt().coerceIn(0, 100)
    val citationTag: String get() = "[VectorDB:$layer | $title | ${similarityPercent}%]"
}

data class VectorSyncReport(
    val syncedSources: Int,
    val totalChunksInDb: Int,
    val failedFeeds: List<String> = emptyList(),
    val blockedByLocalOnlyMode: Boolean = false
)

/**
 * Local Room Vector Database Repository for curated and live-cached Dark Web & Deep Web knowledge.
 * Stores 256-dim L2-normalized Float32 vector embeddings as SQLite BLOBs and executes fast
 * hybrid retrieval (Dense Cosine Similarity + Sparse BM25 + Technical Keyword Boost) offline.
 */
class VectorKnowledgeRepository(
    private val dao: VectorKnowledgeDao,
    private val context: Context? = null,
    private val embeddingProvider: EmbeddingProvider = LocalVectorEmbedder
) {

    companion object {
        private val seedMutex = Mutex()
        private const val LIVE_TELEMETRY_TTL_MS = 24L * 60L * 60L * 1000L
        private const val SCHOLARLY_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private val CVE_REGEX = Regex("CVE-\\d{4}-\\d{4,7}", RegexOption.IGNORE_CASE)

        private fun sha256Short(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun formatTimestamp(epochMillis: Long): String {
            if (epochMillis <= 0L) return "Evergreen"
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))
        }
    }

    fun observeSources(): Flow<List<VectorKnowledgeSourceEntity>> = dao.observeAllSources()
    fun observeSourceCount(): Flow<Int> = dao.observeSourceCount()
    fun observeChunkCount(): Flow<Int> = dao.observeChunkCount()
    fun observeLayerChunkCounts(): Flow<List<LayerVectorCount>> = dao.observeLayerChunkCounts()

    /**
     * Idempotently seeds the verified curated Dark Web & Deep Web corpus into Room SQLite
     * without overwriting any previously cached live entries.
     */
    suspend fun ensureSeeded(): Int = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            var seeded = 0
            for (entry in CuratedDarkDeepKnowledgeCorpus.entries) {
                val existing = dao.getSourceByKey(entry.sourceKey)
                if (existing == null || existing.chunkCount == 0) {
                    indexTextSourceInternal(
                        sourceKey = entry.sourceKey,
                        title = entry.title,
                        layer = entry.layer,
                        providerName = entry.providerName,
                        sourceUrl = entry.sourceUrl,
                        originType = "CURATED_OFFLINE",
                        language = "en",
                        fullText = entry.body,
                        keywordsCsv = entry.keywordsCsv,
                        ttlMillis = 0L
                    )
                    seeded++
                }
            }
            reindexMismatchedEmbeddingsIfNeeded()
            seeded
        }
    }

    /**
     * Re-indexes any stored vector chunks whose embedding modelId or versionFingerprint differs
     * from the active [embeddingProvider], preventing vector space corruption after model upgrades.
     */
    suspend fun reindexMismatchedEmbeddingsIfNeeded(): Int = withContext(Dispatchers.IO) {
        val mismatchedSourceIds = dao.getSourceIdsWithMismatchedEmbeddings(
            expectedModelId = embeddingProvider.modelId,
            expectedFingerprint = embeddingProvider.versionFingerprint
        )
        if (mismatchedSourceIds.isEmpty()) return@withContext 0

        val curatedByKey = CuratedDarkDeepKnowledgeCorpus.entries.associateBy { it.sourceKey }
        var reindexedCount = 0
        mismatchedSourceIds.chunked(200).forEach { idBatch ->
            val sources = dao.getSourcesByIds(idBatch)
            for (source in sources) {
                val curated = if (source.originType == "CURATED_OFFLINE") curatedByKey[source.sourceKey] else null
                if (curated != null) {
                    indexTextSourceInternal(
                        sourceKey = curated.sourceKey,
                        title = curated.title,
                        layer = curated.layer,
                        providerName = curated.providerName,
                        sourceUrl = curated.sourceUrl,
                        originType = "CURATED_OFFLINE",
                        language = "en",
                        fullText = curated.body,
                        keywordsCsv = curated.keywordsCsv,
                        ttlMillis = 0L
                    )
                    reindexedCount++
                } else {
                    val oldChunks = dao.getChunksForSourceOnce(source.id)
                    if (oldChunks.isNotEmpty()) {
                        val updatedChunks = oldChunks.map { chunk ->
                            val vec = embeddingProvider.embed(
                                text = chunk.content,
                                title = chunk.title,
                                keywordsCsv = chunk.keywordsCsv
                            )
                            chunk.copy(
                                id = 0L,
                                embeddingDims = embeddingProvider.dimensions,
                                embeddingBlob = LocalVectorEmbedder.toByteArray(vec),
                                embeddingModelId = embeddingProvider.modelId,
                                embeddingFingerprint = embeddingProvider.versionFingerprint
                            )
                        }
                        dao.upsertSourceWithChunks(source, updatedChunks)
                        reindexedCount++
                    }
                }
            }
        }
        reindexedCount
    }

    suspend fun indexTextSource(
        sourceKey: String,
        title: String,
        layer: String,
        providerName: String,
        sourceUrl: String,
        originType: String,
        language: String = "en",
        fullText: String,
        keywordsCsv: String = "",
        ttlMillis: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        indexTextSourceInternal(
            sourceKey = sourceKey,
            title = title,
            layer = layer,
            providerName = providerName,
            sourceUrl = sourceUrl,
            originType = originType,
            language = language,
            fullText = fullText,
            keywordsCsv = keywordsCsv,
            ttlMillis = ttlMillis
        )
    }

    private suspend fun indexTextSourceInternal(
        sourceKey: String,
        title: String,
        layer: String,
        providerName: String,
        sourceUrl: String,
        originType: String,
        language: String,
        fullText: String,
        keywordsCsv: String,
        ttlMillis: Long
    ): Long {
        val cleanText = fullText.trim()
        if (cleanText.length < 20) return -1L

        val now = System.currentTimeMillis()
        val expiresAt = if (ttlMillis > 0L) now + ttlMillis else 0L
        val chunks = DocumentRagEngine.chunkText(cleanText, chunkSizeChars = 650, overlapChars = 110)
        if (chunks.isEmpty()) return -1L

        val sourceEntity = VectorKnowledgeSourceEntity(
            sourceKey = sourceKey,
            title = title.trim().ifBlank { "Knowledge Source" },
            layer = layer,
            providerName = providerName,
            sourceUrl = sourceUrl,
            originType = originType,
            language = language,
            summaryText = cleanText.take(280),
            chunkCount = chunks.size,
            fetchedAt = now,
            expiresAt = expiresAt
        )

        val chunkEntities = chunks.mapIndexed { idx, chunkContent ->
            val vec = embeddingProvider.embed(
                text = chunkContent,
                title = title,
                keywordsCsv = keywordsCsv
            )
            val blob = LocalVectorEmbedder.toByteArray(vec)
            VectorKnowledgeChunkEntity(
                sourceId = 0L,
                sourceKey = sourceKey,
                layer = layer,
                providerName = providerName,
                title = title,
                sourceUrl = sourceUrl,
                originType = originType,
                language = language,
                chunkIndex = idx,
                content = chunkContent,
                keywordsCsv = keywordsCsv,
                tokenCount = LocalVectorEmbedder.tokenize(chunkContent).size,
                embeddingDims = embeddingProvider.dimensions,
                embeddingBlob = blob,
                fetchedAt = now,
                embeddingModelId = embeddingProvider.modelId,
                embeddingFingerprint = embeddingProvider.versionFingerprint
            )
        }

        return dao.upsertSourceWithChunks(sourceEntity, chunkEntities)
    }

    /**
     * Persists every real live layer retrieved from [AutonomousKnowledgeEngine.queryMultiLayerWikiStructured]
     * into the local Room Vector DB for fast offline retrieval.
     */
    suspend fun cacheWikiLayerResult(
        result: AutonomousKnowledgeEngine.WikiLayerResult,
        language: String = "en"
    ): Int = withContext(Dispatchers.IO) {
        if (!result.isFromLiveNetwork) return@withContext 0
        ensureSeeded()

        val topicSlug = result.topic.trim().lowercase(Locale.ROOT)
        val hash = sha256Short(topicSlug)
        var indexed = 0

        fun firstCitationUrl(layerPrefix: String, fallback: String): String {
            return result.citations.firstOrNull {
                it.layer.contains(layerPrefix, ignoreCase = true)
            }?.url?.takeIf { it.isNotBlank() } ?: fallback
        }

        val liveSurface = listOf(result.surfaceWiki, result.wikidataEntities, result.ddgInstant)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        if (liveSurface.isNotBlank()) {
            val id = indexTextSourceInternal(
                sourceKey = "live:surface:$language:$hash",
                title = "Surface Wiki: ${result.topic}",
                layer = "SURFACE_WIKI",
                providerName = "Wikipedia / Wikidata / DDG",
                sourceUrl = firstCitationUrl("Surface", "https://en.wikipedia.org/"),
                originType = "LIVE_CACHED",
                language = language,
                fullText = liveSurface,
                keywordsCsv = result.topic,
                ttlMillis = SCHOLARLY_CACHE_TTL_MS
            )
            if (id > 0L) indexed++
        }

        val deepCombined = listOf(result.deepAcademic, result.pubmedArticles)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        if (deepCombined.isNotBlank()) {
            val id = indexTextSourceInternal(
                sourceKey = "live:deep:$language:$hash",
                title = "Deep Web Academic: ${result.topic}",
                layer = "DEEP_WEB",
                providerName = "OpenAlex / arXiv / CrossRef / PubMed",
                sourceUrl = firstCitationUrl("Deep Web", "https://openalex.org/"),
                originType = "LIVE_CACHED",
                language = language,
                fullText = deepCombined,
                keywordsCsv = "${result.topic},academic,deep web,research",
                ttlMillis = SCHOLARLY_CACHE_TTL_MS
            )
            if (id > 0L) indexed++
        }

        if (result.cveIntel.isNotBlank()) {
            val cveId = CVE_REGEX.find(result.cveIntel)?.value?.uppercase(Locale.ROOT)
            val key = if (cveId != null) "live:cve:$cveId" else "live:cve:feed:$hash"
            val id = indexTextSourceInternal(
                sourceKey = key,
                title = if (cveId != null) "CVE Threat Advisory: $cveId" else "CVE Advisories (${result.topic})",
                layer = "DEEP_WEB",
                providerName = "CIRCL / NIST NVD CVE",
                sourceUrl = if (cveId != null) "https://nvd.nist.gov/vuln/detail/$cveId" else "https://nvd.nist.gov/vuln",
                originType = "LIVE_CACHED",
                language = "en",
                fullText = result.cveIntel,
                keywordsCsv = listOfNotNull(cveId, "cve", "vulnerability", "cvss", result.topic).joinToString(","),
                ttlMillis = LIVE_TELEMETRY_TTL_MS
            )
            if (id > 0L) indexed++
        }

        if (result.liveTorNetwork.isNotBlank()) {
            val id = indexTextSourceInternal(
                sourceKey = "live:dark_web:onionoo:latest",
                title = "Live Tor Project Onionoo Relay Consensus",
                layer = "DARK_WEB",
                providerName = "Tor Project Onionoo",
                sourceUrl = "https://metrics.torproject.org/rs.html",
                originType = "LIVE_CACHED",
                language = "en",
                fullText = result.liveTorNetwork,
                keywordsCsv = "tor,onion,onionoo,relay,guard,dark web,consensus",
                ttlMillis = LIVE_TELEMETRY_TTL_MS
            )
            if (id > 0L) indexed++
        }

        indexed
    }

    /**
     * Hybrid Vector + BM25 offline search over Room SQLite vector BLOBs.
     * Supports layer filtering ("DARK_WEB", "DEEP_WEB", "SURFACE_WIKI") and origin filtering.
     */
    suspend fun search(
        query: String,
        layerFilter: String? = null,
        originFilter: String? = null,
        topK: Int = 6,
        minScore: Double = 0.14
    ): List<VectorSearchHit> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        ensureSeeded()

        val rawChunks = if (!layerFilter.isNullOrBlank()) {
            dao.getChunksByLayerOnce(layerFilter)
        } else {
            dao.getAllChunksOnce()
        }
        val chunks = if (!originFilter.isNullOrBlank()) {
            rawChunks.filter { it.originType == originFilter }
        } else {
            rawChunks
        }
        if (chunks.isEmpty()) return@withContext emptyList()

        val queryVec = embeddingProvider.embed(cleanQuery)
        val queryTokens = LocalVectorEmbedder.tokenize(cleanQuery)
        val lowerQuery = cleanQuery.lowercase(Locale.ROOT)
        val cveInQuery = CVE_REGEX.find(cleanQuery)?.value?.lowercase(Locale.ROOT)

        val totalDocs = chunks.size.toDouble()
        val chunkTokenLists = chunks.map {
            LocalVectorEmbedder.tokenize("${it.title} ${it.keywordsCsv.replace(",", " ")} ${it.content}")
        }
        val avgLen = chunkTokenLists.map { it.size }.average().coerceAtLeast(1.0)
        val dfMap = mutableMapOf<String, Int>()
        for (term in queryTokens.distinct()) {
            dfMap[term] = chunkTokenLists.count { term in it }
        }

        val k1 = 1.5
        val b = 0.75
        val rawBm25Scores = DoubleArray(chunks.size)
        var maxBm25 = 0.0

        for (idx in chunks.indices) {
            val docTokens = chunkTokenLists[idx]
            val docLen = docTokens.size.toDouble().coerceAtLeast(1.0)
            val freqMap = docTokens.groupingBy { it }.eachCount()
            var bm25 = 0.0
            for (term in queryTokens) {
                val tf = (freqMap[term] ?: 0).toDouble()
                if (tf > 0.0) {
                    val df = (dfMap[term] ?: 0).toDouble()
                    val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
                    val numer = tf * (k1 + 1.0)
                    val denom = tf + k1 * (1.0 - b + b * (docLen / avgLen))
                    bm25 += idf * (numer / denom)
                }
            }
            rawBm25Scores[idx] = bm25
            if (bm25 > maxBm25) maxBm25 = bm25
        }

        val scoredHits = chunks.mapIndexed { idx, chunk ->
            val cosine = LocalVectorEmbedder.cosineSimilarity(queryVec, chunk.embeddingBlob)
                .toDouble().coerceIn(0.0, 1.0)
            val normBm25 = if (maxBm25 > 0.0) (rawBm25Scores[idx] / maxBm25).coerceIn(0.0, 1.0) else 0.0

            val haystackLower = "${chunk.title} ${chunk.keywordsCsv} ${chunk.content}".lowercase(Locale.ROOT)
            var boost = 0.0
            if (cveInQuery != null && haystackLower.contains(cveInQuery)) {
                boost += 1.0
            } else {
                val matchedKeywords = chunk.keywordsCsv
                    .split(',')
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .count { kw -> kw.length >= 2 && lowerQuery.contains(kw) }
                if (matchedKeywords > 0) {
                    boost += (matchedKeywords * 0.45).coerceAtMost(0.95)
                }
                if (lowerQuery.length >= 4 && haystackLower.contains(lowerQuery)) {
                    boost = (boost + 0.5).coerceAtMost(1.0)
                }
            }

            // Hybrid fusion: Dense vector cosine + sparse BM25 + technical keyword/ID boost
            val hybrid = (0.58 * cosine + 0.30 * normBm25 + 0.12 * boost).coerceIn(0.0, 1.0)

            VectorSearchHit(
                sourceId = chunk.sourceId,
                sourceKey = chunk.sourceKey,
                title = chunk.title,
                layer = chunk.layer,
                providerName = chunk.providerName,
                sourceUrl = chunk.sourceUrl,
                originType = chunk.originType,
                excerpt = chunk.content,
                cosineSimilarity = cosine,
                bm25Score = rawBm25Scores[idx],
                hybridScore = hybrid,
                fetchedAt = chunk.fetchedAt
            )
        }

        // Deduplicate multiple chunks from the same source while keeping the best-matching chunk per source first,
        // allowing secondary chunks only if distinct sources don't fill topK.
        val filtered = scoredHits.filter { it.hybridScore >= minScore }.sortedByDescending { it.hybridScore }
        val primaryBySource = filtered.distinctBy { it.sourceKey }.take(topK)
        if (primaryBySource.size >= topK) {
            primaryBySource
        } else {
            val remaining = filtered.filter { it !in primaryBySource }.take(topK - primaryBySource.size)
            (primaryBySource + remaining).sortedByDescending { it.hybridScore }
        }
    }

    /**
     * Converts local offline vector search hits into a structured [AutonomousKnowledgeEngine.WikiLayerResult]
     * with honest provenance labels and citations.
     */
    suspend fun searchAsWikiLayerResult(
        query: String,
        layerFilter: String? = null,
        topK: Int = 6
    ): AutonomousKnowledgeEngine.WikiLayerResult = withContext(Dispatchers.IO) {
        val hits = search(query = query, layerFilter = layerFilter, topK = topK)
        if (hits.isEmpty()) return@withContext AutonomousKnowledgeEngine.WikiLayerResult(topic = query)

        fun formatHit(hit: VectorSearchHit): String {
            val originBadge = if (hit.originType == "LIVE_CACHED") {
                "Cached Live Fetch • ${formatTimestamp(hit.fetchedAt)}"
            } else {
                "Curated Offline Corpus"
            }
            return buildString {
                append("**${hit.title}** (${hit.providerName} • Match ${hit.similarityPercent}% • $originBadge)\n")
                append(hit.excerpt)
            }
        }

        val surfaceHits = hits.filter { it.layer == "SURFACE_WIKI" }
        val deepHits = hits.filter { it.layer == "DEEP_WEB" }
        val darkHits = hits.filter { it.layer == "DARK_WEB" }

        val citations = hits.distinctBy { it.sourceUrl }.map { hit ->
            val layerLabel = when (hit.layer) {
                "DARK_WEB" -> "Dark Web Vector DB"
                "DEEP_WEB" -> "Deep Web Vector DB"
                else -> "Surface Vector DB"
            }
            AutonomousKnowledgeEngine.WikiCitation(
                title = "${hit.title} (${hit.similarityPercent}% match)",
                url = hit.sourceUrl,
                layer = layerLabel
            )
        }

        AutonomousKnowledgeEngine.WikiLayerResult(
            topic = query,
            surfaceWiki = surfaceHits.joinToString("\n\n", transform = ::formatHit),
            deepAcademic = deepHits.joinToString("\n\n", transform = ::formatHit),
            darkWebTech = darkHits.joinToString("\n\n", transform = ::formatHit),
            citations = citations,
            isFromLiveNetwork = false
        )
    }

    /**
     * Builds prompt-injection-guarded context string from the Room Vector DB for Chat / Deep Research grounding.
     */
    suspend fun buildCitedVectorContext(
        query: String,
        layerFilter: String? = null,
        topK: Int = 4,
        minScore: Double = 0.34
    ): Pair<String, List<VectorSearchHit>> {
        val hits = search(query = query, layerFilter = layerFilter, topK = topK, minScore = minScore)
        if (hits.isEmpty()) return "" to emptyList()

        val formatted = buildString {
            append("[OFFLINE VECTOR DB CONTEXT (DARK/DEEP WEB CACHE) — CITE VERIFIED SOURCES BELOW]\n")
            hits.forEach { hit ->
                val guarded = AiToolSecurityGuard.inspectAndWrapUntrustedData(
                    context = context,
                    sourceLabel = hit.citationTag,
                    rawContent = "${hit.title} (${hit.providerName} | ${hit.sourceUrl}):\n${hit.excerpt}"
                )
                append(hit.citationTag).append(" — Source: ").append(hit.sourceUrl).append("\n")
                append(guarded.wrappedUntrustedContent).append("\n\n")
            }
        }
        return formatted to hits
    }

    /**
     * Fetches fresh, genuine data from public Dark Web telemetry (Tor Onionoo) and Deep Web feeds
     * (CIRCL CVE, OpenAlex, arXiv, PubMed) and indexes everything into Room SQLite for offline search.
     */
    suspend fun syncLiveCuratedFeeds(
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient,
        cveBaseUrl: String? = null,
        onionooBaseUrl: String? = null,
        openAlexBaseUrl: String? = null,
        arxivBaseUrl: String? = null
    ): VectorSyncReport = withContext(Dispatchers.IO) {
        ensureSeeded()
        val hasOverrides = cveBaseUrl != null || onionooBaseUrl != null || openAlexBaseUrl != null || arxivBaseUrl != null
        if (!hasOverrides && context != null && AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
            val total = dao.countChunksOnce()
            return@withContext VectorSyncReport(
                syncedSources = 0,
                totalChunksInDb = total,
                failedFeeds = listOf("Blocked by Local-Only AI Mode"),
                blockedByLocalOnlyMode = true
            )
        }

        val failed = mutableListOf<String>()
        var synced = 0

        supervisorScope {
            val onionooDef = async(Dispatchers.IO) {
                AutonomousKnowledgeEngine.fetchTorOnionooTelemetry("tor", httpClient, onionooBaseUrl)
            }
            val cveDef = async(Dispatchers.IO) {
                AutonomousKnowledgeEngine.fetchCveIntelligence(null, "latest", httpClient, cveBaseUrl)
            }
            val openAlexCryptoDef = async(Dispatchers.IO) {
                AutonomousKnowledgeEngine.fetchOpenAlexResearch("onion routing cryptography", httpClient, openAlexBaseUrl)
            }
            val arxivAiDef = async(Dispatchers.IO) {
                AutonomousKnowledgeEngine.fetchArxivPreprints("transformer security reasoning", httpClient, arxivBaseUrl)
            }

            val (onionText, onionCit) = onionooDef.await()
            if (onionText.isNotBlank()) {
                indexTextSourceInternal(
                    sourceKey = "live:dark_web:onionoo:latest",
                    title = "Tor Project Onionoo Live Relay Directory",
                    layer = "DARK_WEB",
                    providerName = "Tor Project Onionoo",
                    sourceUrl = onionCit?.url ?: "https://metrics.torproject.org/rs.html",
                    originType = "LIVE_CACHED",
                    language = "en",
                    fullText = onionText,
                    keywordsCsv = "tor,onionoo,relay,guard,exit,dark web,consensus",
                    ttlMillis = LIVE_TELEMETRY_TTL_MS
                )
                synced++
            } else {
                failed.add("Tor Onionoo directory")
            }

            val (cveText, cveCits) = cveDef.await()
            if (cveText.isNotBlank()) {
                indexTextSourceInternal(
                    sourceKey = "live:deep_web:circl_cve:latest",
                    title = "CIRCL / NVD Latest Public CVE Advisories",
                    layer = "DEEP_WEB",
                    providerName = "CIRCL / NVD CVE",
                    sourceUrl = cveCits.firstOrNull()?.url ?: "https://cve.circl.lu/",
                    originType = "LIVE_CACHED",
                    language = "en",
                    fullText = cveText,
                    keywordsCsv = "cve,vulnerability,cvss,circl,nvd,security,advisory",
                    ttlMillis = LIVE_TELEMETRY_TTL_MS
                )
                synced++
            } else {
                failed.add("CIRCL CVE feed")
            }

            val oaText = openAlexCryptoDef.await()
            if (oaText.isNotBlank()) {
                indexTextSourceInternal(
                    sourceKey = "live:deep_web:openalex:onion_crypto",
                    title = "OpenAlex Scholarly Index: Onion Routing & Cryptography",
                    layer = "DEEP_WEB",
                    providerName = "OpenAlex",
                    sourceUrl = "https://openalex.org/works?search=onion+routing+cryptography",
                    originType = "LIVE_CACHED",
                    language = "en",
                    fullText = oaText,
                    keywordsCsv = "openalex,onion routing,cryptography,research,academic,doi",
                    ttlMillis = SCHOLARLY_CACHE_TTL_MS
                )
                synced++
            } else {
                failed.add("OpenAlex scholarly feed")
            }

            val axText = arxivAiDef.await()
            if (axText.isNotBlank()) {
                indexTextSourceInternal(
                    sourceKey = "live:deep_web:arxiv:transformer_security",
                    title = "arXiv Preprints: Transformer Security & Reasoning",
                    layer = "DEEP_WEB",
                    providerName = "arXiv",
                    sourceUrl = "https://arxiv.org/search/?query=transformer+security+reasoning&searchtype=all",
                    originType = "LIVE_CACHED",
                    language = "en",
                    fullText = axText,
                    keywordsCsv = "arxiv,preprint,transformer,security,reasoning,ai,paper",
                    ttlMillis = SCHOLARLY_CACHE_TTL_MS
                )
                synced++
            } else {
                failed.add("arXiv preprint feed")
            }
        }

        VectorSyncReport(
            syncedSources = synced,
            totalChunksInDb = dao.countChunksOnce(),
            failedFeeds = failed
        )
    }

    suspend fun clearLiveCachedOnly() = withContext(Dispatchers.IO) {
        dao.deleteLiveCachedSources()
        ensureSeeded()
    }
}
