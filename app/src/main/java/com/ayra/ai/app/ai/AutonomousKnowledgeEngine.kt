package com.ayra.ai.app.ai

import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time Multi-Layer Wiki & Encyclopedic Grounding Engine:
 * 1. Surface Wiki: Live Wikipedia (bn/en) + DuckDuckGo Instant Answer API.
 * 2. Deep Web Open Academic Wiki: OpenAlex scholarly works + arXiv open research index.
 * 3. Dark Web & Overlay Network Technical Wiki: Educational protocol index via GlobalWebKnowledgeGateway.
 * Plus deterministic arithmetic evaluation.
 */
object AutonomousKnowledgeEngine {

    private const val MAX_CACHE_ENTRIES = 250
    private val knowledgeCache = ConcurrentHashMap<String, String>()
    private val structuredCache = ConcurrentHashMap<String, WikiLayerResult>()

    data class WikiCitation(
        val title: String,
        val url: String,
        val layer: String
    )

    data class WikiLayerResult(
        val topic: String,
        val surfaceWiki: String = "",
        val wikidataEntities: String = "",
        val ddgInstant: String = "",
        val deepAcademic: String = "",
        val pubmedArticles: String = "",
        val cveIntel: String = "",
        val darkWebTech: String = "",
        val liveTorNetwork: String = "",
        val citations: List<WikiCitation> = emptyList(),
        val isFromLiveNetwork: Boolean = false
    ) {
        fun hasAnyContent(): Boolean = listOf(
            surfaceWiki, wikidataEntities, ddgInstant, deepAcademic,
            pubmedArticles, cveIntel, darkWebTech, liveTorNetwork
        ).any { it.isNotBlank() }

        fun toFormattedMarkdown(): String = buildString {
            val surfaceCombined = listOf(surfaceWiki, wikidataEntities).filter { it.isNotBlank() }.joinToString("\n\n")
            if (surfaceCombined.isNotBlank()) {
                append("### [SURFACE WIKI — WIKIPEDIA]\n")
                append(surfaceCombined.take(1400)).append("\n\n")
            }
            if (ddgInstant.isNotBlank()) {
                append("### [LIVE INSTANT KNOWLEDGE — DUCKDUCKGO]\n")
                append(ddgInstant.take(700)).append("\n\n")
            }
            val deepCombined = listOf(deepAcademic, pubmedArticles, cveIntel).filter { it.isNotBlank() }.joinToString("\n\n")
            if (deepCombined.isNotBlank()) {
                append("### [DEEP WEB ACADEMIC WIKI — OPENALEX / ARXIV]\n")
                append(deepCombined.take(1300)).append("\n\n")
            }
            val darkCombined = listOf(darkWebTech, liveTorNetwork).filter { it.isNotBlank() }.joinToString("\n\n")
            if (darkCombined.isNotBlank()) {
                append("### [DARK WEB & OVERLAY NETWORK TECHNICAL WIKI]\n")
                append(darkCombined.take(1500)).append("\n\n")
            }
            if (citations.isNotEmpty()) {
                append("### [VERIFIED LIVE SOURCES]\n")
                citations.distinctBy { it.url }.take(8).forEach { cit ->
                    append("• [${cit.layer}] ${cit.title} — ${cit.url}\n")
                }
            }
        }.trim()
    }

    private val STOP_WORDS_EN_REGEX = Regex("(?i)^(why does|why is|why do|what is|what are|how does|how to|who is|where is|when did|explain|tell me about|meaning of|definition of|/wiki|/deepwiki|/darkwiki|wiki:|deepwiki:|darkwiki:)\\s*")
    private val STOP_WORDS_BN_REGEX = Regex("\\b(কেন|কীভাবে|কিভাবে|কোথায়|কখন|কেমন|কার|কোনটি|কী|কি|বলুন|বলো তো|বলো|সম্পর্কে|জানাও|ব্যাখ্যা করো|দেখা যায়|দেখায়|হয়|হলো|কাকে বলে|অর্থ কি|নাম কি|কারণে)\\b")
    private val PUNCTUATION_REGEX = Regex("[?।!.,:;]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MATH_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX×÷^%]|যোগ|বিয়োগ|গুণ|ভাগ)\\s*(\\d+(?:\\.\\d+)?)$")
    private val CVE_REGEX = Regex("CVE-\\d{4}-\\d{4,7}", RegexOption.IGNORE_CASE)
    private val USER_AGENT = "AyraAiAssistant/3.2 (Linux; Android 14; Mobile; https://ayra.ai/assistant)"

    private fun putCache(key: String, value: String) {
        if (value.isBlank()) return
        if (knowledgeCache.size >= MAX_CACHE_ENTRIES) {
            knowledgeCache.clear()
        }
        knowledgeCache[key] = value
    }

    private fun putStructuredCache(key: String, value: WikiLayerResult) {
        if (!value.hasAnyContent()) return
        if (structuredCache.size >= MAX_CACHE_ENTRIES) {
            structuredCache.clear()
        }
        structuredCache[key] = value
    }

    /**
     * Multi-Layer Parallel Wiki Lookup (Surface Wiki + Deep Web Academic Wiki + Dark Web / Overlay Network Technical Wiki).
     * Never blocks chat: runs in parallel under [supervisorScope] with tight per-source timeouts.
     */
    suspend fun queryMultiLayerWiki(
        prompt: String,
        isBengali: Boolean,
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient,
        wikipediaBaseUrl: String? = null,
        ddgBaseUrl: String? = null,
        openAlexBaseUrl: String? = null,
        arxivBaseUrl: String? = null,
        wikidataBaseUrl: String? = null,
        pubmedBaseUrl: String? = null,
        cveBaseUrl: String? = null,
        onionooBaseUrl: String? = null
    ): String {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.length < 3) return ""

        val topic = extractTopicKeyword(cleanPrompt).ifBlank { cleanPrompt }
        val cacheKey = "multi:${if (isBengali) "bn" else "en"}:${topic.lowercase(Locale.ROOT)}"
        val hasOverrides = wikipediaBaseUrl != null || ddgBaseUrl != null || openAlexBaseUrl != null ||
            arxivBaseUrl != null || wikidataBaseUrl != null || pubmedBaseUrl != null ||
            cveBaseUrl != null || onionooBaseUrl != null
        if (!hasOverrides) {
            knowledgeCache[cacheKey]?.let { return it }
        }

        val structured = queryMultiLayerWikiStructured(
            prompt = cleanPrompt,
            isBengali = isBengali,
            httpClient = httpClient,
            wikipediaBaseUrl = wikipediaBaseUrl,
            ddgBaseUrl = ddgBaseUrl,
            openAlexBaseUrl = openAlexBaseUrl,
            arxivBaseUrl = arxivBaseUrl,
            wikidataBaseUrl = wikidataBaseUrl,
            pubmedBaseUrl = pubmedBaseUrl,
            cveBaseUrl = cveBaseUrl,
            onionooBaseUrl = onionooBaseUrl
        )
        val assembled = structured.toFormattedMarkdown()
        if (assembled.isNotBlank() && !hasOverrides) {
            putCache(cacheKey, assembled)
        }
        return assembled
    }

    /**
     * Structured multi-layer lookup used by both the AI prompt grounder and the live Wiki Explorer UI.
     */
    suspend fun queryMultiLayerWikiStructured(
        prompt: String,
        isBengali: Boolean,
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient,
        wikipediaBaseUrl: String? = null,
        ddgBaseUrl: String? = null,
        openAlexBaseUrl: String? = null,
        arxivBaseUrl: String? = null,
        wikidataBaseUrl: String? = null,
        pubmedBaseUrl: String? = null,
        cveBaseUrl: String? = null,
        onionooBaseUrl: String? = null,
        forceAllLayers: Boolean = false
    ): WikiLayerResult = supervisorScope {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.length < 3) return@supervisorScope WikiLayerResult(cleanPrompt)

        val topic = extractTopicKeyword(cleanPrompt).ifBlank { cleanPrompt }
        val hasOverrides = wikipediaBaseUrl != null || ddgBaseUrl != null || openAlexBaseUrl != null ||
            arxivBaseUrl != null || wikidataBaseUrl != null || pubmedBaseUrl != null ||
            cveBaseUrl != null || onionooBaseUrl != null
        val cacheKey = "struct:${if (isBengali) "bn" else "en"}:$forceAllLayers:${topic.lowercase(Locale.ROOT)}"
        if (!hasOverrides) {
            structuredCache[cacheKey]?.let { return@supervisorScope it }
        }

        val isDarkOrDeep = forceAllLayers || GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(cleanPrompt)
        val wantsDeepAcademic = forceAllLayers || isDarkOrDeep || isAcademicOrTechnicalQuery(cleanPrompt)
        val wantsMedical = forceAllLayers || isBiomedicalQuery(cleanPrompt)
        val cveMatch = CVE_REGEX.find(cleanPrompt)?.value?.uppercase(Locale.ROOT)
        val wantsCve = cveMatch != null || cveBaseUrl != null ||
            cleanPrompt.contains("cve", ignoreCase = true) ||
            cleanPrompt.contains("vulnerability", ignoreCase = true)

        val wikiDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(1800L) {
                queryLiveWebKnowledgeWithUrl(cleanPrompt, isBengali, httpClient, wikipediaBaseUrl)
            }
        }

        val wikidataDeferred = async(Dispatchers.IO) {
            if (!forceAllLayers && wikidataBaseUrl == null && !wantsDeepAcademic) return@async null
            withTimeoutOrNull(1400L) {
                fetchWikidataEntities(topic, isBengali, httpClient, wikidataBaseUrl)
            }
        }

        val ddgDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(1500L) {
                fetchDuckDuckGoInstantAnswer(topic, httpClient, ddgBaseUrl)
            } ?: ""
        }

        val deepAcademicDeferred = async(Dispatchers.IO) {
            if (!wantsDeepAcademic) return@async null
            withTimeoutOrNull(2000L) {
                val oa = fetchOpenAlexResearch(topic, httpClient, openAlexBaseUrl)
                val ax = if (oa.isBlank() || forceAllLayers || cleanPrompt.contains("arxiv", true) || cleanPrompt.contains("paper", true)) {
                    fetchArxivPreprints(topic, httpClient, arxivBaseUrl)
                } else ""
                val cr = if (oa.isBlank() || cleanPrompt.contains("doi", true) || cleanPrompt.contains("journal", true) || cleanPrompt.contains("citation", true)) {
                    fetchCrossRefResearch(topic, httpClient)
                } else ""
                listOf(oa, ax, cr).filter { it.isNotBlank() }.joinToString("\n\n")
            }
        }

        val pubmedDeferred = async(Dispatchers.IO) {
            if (!wantsMedical && pubmedBaseUrl == null) return@async null
            withTimeoutOrNull(1800L) {
                fetchPubMedResearch(topic, httpClient, pubmedBaseUrl)
            }
        }

        val cveDeferred = async(Dispatchers.IO) {
            if (!wantsCve) return@async null
            withTimeoutOrNull(1800L) {
                fetchCveIntelligence(cveMatch, topic, httpClient, cveBaseUrl)
            }
        }

        val darkTechDeferred = async(Dispatchers.IO) {
            if (isDarkOrDeep) {
                GlobalWebKnowledgeGateway.synthesizeGlobalNetworkKnowledge(
                    prompt = cleanPrompt,
                    isBengali = isBengali,
                    isGfMode = false
                )
            } else ""
        }

        val onionooDeferred = async(Dispatchers.IO) {
            if (!isDarkOrDeep && onionooBaseUrl == null) return@async null
            withTimeoutOrNull(1800L) {
                fetchTorOnionooTelemetry(topic, httpClient, onionooBaseUrl)
            }
        }

        val wikiPair = wikiDeferred.await()
        val wikidataRes = wikidataDeferred.await()
        val ddgText = ddgDeferred.await()
        val academicText = deepAcademicDeferred.await().orEmpty()
        val pubmedRes = pubmedDeferred.await()
        val cveRes = cveDeferred.await()
        val darkTechText = darkTechDeferred.await()
        val onionooRes = onionooDeferred.await()

        val citations = mutableListOf<WikiCitation>()
        wikiPair?.second?.takeIf { it.isNotBlank() }?.let { url ->
            citations.add(WikiCitation(title = "Wikipedia: $topic", url = url, layer = "Surface Wiki"))
        }
        wikidataRes?.second?.let { citations.addAll(it) }
        pubmedRes?.second?.let { citations.addAll(it) }
        cveRes?.second?.let { citations.addAll(it) }
        onionooRes?.second?.let { citations.add(it) }
        if (academicText.isNotBlank()) {
            citations.add(WikiCitation("OpenAlex Scholarly Works", "https://openalex.org/works?search=${URLEncoder.encode(topic, "UTF-8")}", "Deep Web Wiki"))
            citations.add(WikiCitation("arXiv Open Preprints", "https://arxiv.org/search/?query=${URLEncoder.encode(topic, "UTF-8")}&searchtype=all", "Deep Web Wiki"))
        }

        val isLive = (wikiPair?.first?.isNotBlank() == true) ||
            (wikidataRes?.first?.isNotBlank() == true) ||
            ddgText.isNotBlank() ||
            academicText.isNotBlank() ||
            (pubmedRes?.first?.isNotBlank() == true) ||
            (cveRes?.first?.isNotBlank() == true) ||
            (onionooRes?.first?.isNotBlank() == true)

        val result = WikiLayerResult(
            topic = topic,
            surfaceWiki = wikiPair?.first.orEmpty(),
            wikidataEntities = wikidataRes?.first.orEmpty(),
            ddgInstant = ddgText,
            deepAcademic = academicText,
            pubmedArticles = pubmedRes?.first.orEmpty(),
            cveIntel = cveRes?.first.orEmpty(),
            darkWebTech = darkTechText,
            liveTorNetwork = onionooRes?.first.orEmpty(),
            citations = citations,
            isFromLiveNetwork = isLive
        )
        if (!hasOverrides && result.hasAnyContent()) {
            putStructuredCache(cacheKey, result)
        }
        result
    }

    data class CachedWikiLookupResult(
        val wikiResult: WikiLayerResult,
        val usedOfflineVectorCache: Boolean,
        val cachedNewSourcesCount: Int,
        val vectorHits: List<com.ayra.ai.app.ai.rag.VectorSearchHit> = emptyList()
    )

    /**
     * Unified Multi-Layer Wiki lookup backed by Room Local Vector Database:
     * - Online (when Local-Only Mode is OFF): queries live clearnet APIs (Wikipedia, Wikidata, DDG,
     *   OpenAlex, arXiv, CrossRef, PubMed, CIRCL CVE, Tor Onionoo) AND automatically vectorizes/caches
     *   fetched results into Room SQLite.
     * - Offline / Local-Only Mode / Network Failure: serves fast, accurate hybrid cosine+BM25 results
     *   from the local Room Vector Database (curated corpus + previously cached live fetches).
     */
    suspend fun queryMultiLayerWikiWithVectorCache(
        context: android.content.Context,
        prompt: String,
        isBengali: Boolean,
        layerFilter: String? = null,
        offlineOnlyOverride: Boolean = false,
        forceAllLayers: Boolean = true,
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient,
        vectorRepoOverride: com.ayra.ai.app.ai.rag.VectorKnowledgeRepository? = null,
        wikipediaBaseUrl: String? = null,
        ddgBaseUrl: String? = null,
        openAlexBaseUrl: String? = null,
        arxivBaseUrl: String? = null,
        wikidataBaseUrl: String? = null,
        pubmedBaseUrl: String? = null,
        cveBaseUrl: String? = null,
        onionooBaseUrl: String? = null
    ): CachedWikiLookupResult {
        val repo = vectorRepoOverride ?: com.ayra.ai.app.ai.rag.VectorKnowledgeRepository(
            dao = com.ayra.ai.app.data.ChatDatabase.getDatabase(context).vectorKnowledgeDao(),
            context = context
        )
        repo.ensureSeeded()

        val localOnly = offlineOnlyOverride || com.ayra.ai.app.data.AyraPreferences.isLocalOnlyAiModeEnabled(context)
        if (!localOnly) {
            val liveResult = runCatching {
                queryMultiLayerWikiStructured(
                    prompt = prompt,
                    isBengali = isBengali,
                    httpClient = httpClient,
                    wikipediaBaseUrl = wikipediaBaseUrl,
                    ddgBaseUrl = ddgBaseUrl,
                    openAlexBaseUrl = openAlexBaseUrl,
                    arxivBaseUrl = arxivBaseUrl,
                    wikidataBaseUrl = wikidataBaseUrl,
                    pubmedBaseUrl = pubmedBaseUrl,
                    cveBaseUrl = cveBaseUrl,
                    onionooBaseUrl = onionooBaseUrl,
                    forceAllLayers = forceAllLayers
                )
            }.getOrNull()

            if (liveResult != null && liveResult.isFromLiveNetwork) {
                val cachedCount = runCatching {
                    repo.cacheWikiLayerResult(liveResult, language = if (isBengali) "bn" else "en")
                }.getOrDefault(0)
                val hits = runCatching {
                    repo.search(query = prompt, layerFilter = layerFilter, topK = 4)
                }.getOrDefault(emptyList())
                return CachedWikiLookupResult(
                    wikiResult = liveResult,
                    usedOfflineVectorCache = false,
                    cachedNewSourcesCount = cachedCount,
                    vectorHits = hits
                )
            }
        }

        // Offline / Local-Only / Unreachable fallback -> Room Vector DB Hybrid Search
        val hits = repo.search(query = prompt, layerFilter = layerFilter, topK = 6)
        val offlineWiki = repo.searchAsWikiLayerResult(
            query = prompt,
            layerFilter = layerFilter,
            topK = 6
        )
        if (offlineWiki.hasAnyContent()) {
            return CachedWikiLookupResult(
                wikiResult = offlineWiki,
                usedOfflineVectorCache = true,
                cachedNewSourcesCount = 0,
                vectorHits = hits
            )
        }

        val topic = extractTopicKeyword(prompt).ifBlank { prompt.trim() }
        val fallbackDark = if (GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(prompt)) {
            GlobalWebKnowledgeGateway.synthesizeGlobalNetworkKnowledge(prompt, isBengali, isGfMode = false)
        } else ""
        return CachedWikiLookupResult(
            wikiResult = WikiLayerResult(
                topic = topic,
                darkWebTech = fallbackDark,
                isFromLiveNetwork = false
            ),
            usedOfflineVectorCache = true,
            cachedNewSourcesCount = 0,
            vectorHits = emptyList()
        )
    }

    private fun isBiomedicalQuery(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val cues = listOf(
            "medical", "medicine", "clinical", "disease", "virus", "bacteria",
            "cancer", "gene", "dna", "rna", "protein", "vaccine", "pubmed",
            "neuro", "cardiac", "therapy", "diagnosis", "চিকিৎসা", "রোগ", "ওষুধ"
        )
        return cues.any { lower.contains(it) }
    }

    private fun isAcademicOrTechnicalQuery(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val cues = listOf(
            "research", "paper", "study", "arxiv", "quantum", "algorithm", "neural",
            "ai", "llm", "transformer", "cryptography", "encryption", "protocol",
            "cancer", "biology", "physics", "chemistry", "relativity", "black hole",
            "architecture", "security", "kernel", "compiler", "database", "distributed",
            "deep web", "dark web", "onion", "গবেষণা", "বিজ্ঞান", "প্রযুক্তি",
            "কোয়ান্টাম", "এনক্রিপশন", "অ্যালগরিদম", "ডার্ক ওয়েব", "ডিপ ওয়েব"
        )
        return lower.length > 40 || cues.any { lower.contains(it) }
    }

    /**
     * Queries Wikipedia open API for real-time encyclopedic extracts in Bengali or English.
     */
    @JvmOverloads
    fun queryLiveWebKnowledge(
        prompt: String,
        isBengali: Boolean,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): String {
        return queryLiveWebKnowledgeWithUrl(prompt, isBengali, httpClient, baseUrlOverride).first
    }

    internal fun queryLiveWebKnowledgeWithUrl(
        prompt: String,
        isBengali: Boolean,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, String> {
        val topic = extractTopicKeyword(prompt)
        val queryTerm = if (topic.isNotBlank()) topic else prompt.trim()
        if (queryTerm.isBlank()) return "" to ""
        val cacheKey = "wiki:${if (isBengali) "bn" else "en"}:$queryTerm"

        fun pageUrl(lang: String, title: String): String =
            "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"

        if (baseUrlOverride == null) {
            knowledgeCache[cacheKey]?.let { cached ->
                return cached to pageUrl(if (isBengali) "bn" else "en", queryTerm)
            }
        }

        fun fetchWikiGeneratorSearch(lang: String, term: String): Pair<String, String>? {
            return try {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                    ?: "https://$lang.wikipedia.org/"
                val searchUrl = "${base}w/api.php?action=query&generator=search&gsrsearch=$encoded&gsrlimit=1&prop=extracts&exintro=1&explaintext=1&format=json"
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
                    val firstKey = pages.keys().asSequence().firstOrNull() ?: return null
                    if (firstKey == "-1") return null
                    val pageObj = pages.getJSONObject(firstKey)
                    val title = pageObj.optString("title", term)
                    val extract = pageObj.optString("extract", "").trim()
                    if (extract.length >= 25) Pair(title, extract) else null
                }
            } catch (_: Exception) { null }
        }

        fun fetchWikiSummary(lang: String, term: String): Pair<String, String>? {
            return try {
                val encoded = URLEncoder.encode(term.replace(" ", "_"), "UTF-8")
                val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                    ?: "https://$lang.wikipedia.org/"
                val summaryUrl = "${base}api/rest_v1/page/summary/$encoded"
                val req = Request.Builder()
                    .url(summaryUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val extract = json.optString("extract", "").trim()
                    val title = json.optString("title", term)
                    if (extract.length >= 25) Pair(title, extract) else null
                }
            } catch (_: Exception) { null }
        }

        if (isBengali) {
            val bnGen = fetchWikiGeneratorSearch("bn", queryTerm) ?: fetchWikiGeneratorSearch("bn", prompt.trim())
            if (bnGen != null) {
                val formatted = formatKnowledgePoints(bnGen.first, bnGen.second, isBengali = true)
                if (baseUrlOverride == null) putCache(cacheKey, formatted)
                return formatted to pageUrl("bn", bnGen.first)
            }
            val bnSummary = fetchWikiSummary("bn", queryTerm)
            if (bnSummary != null) {
                val formatted = formatKnowledgePoints(bnSummary.first, bnSummary.second, isBengali = true)
                if (baseUrlOverride == null) putCache(cacheKey, formatted)
                return formatted to pageUrl("bn", bnSummary.first)
            }
        }

        val enGen = fetchWikiGeneratorSearch("en", queryTerm) ?: fetchWikiGeneratorSearch("en", prompt.trim())
        if (enGen != null) {
            val formatted = formatKnowledgePoints(enGen.first, enGen.second, isBengali = isBengali)
            if (baseUrlOverride == null) putCache(cacheKey, formatted)
            return formatted to pageUrl("en", enGen.first)
        }

        val enSummary = fetchWikiSummary("en", queryTerm)
        if (enSummary != null) {
            val formatted = formatKnowledgePoints(enSummary.first, enSummary.second, isBengali = isBengali)
            if (baseUrlOverride == null) putCache(cacheKey, formatted)
            return formatted to pageUrl("en", enSummary.first)
        }

        return "" to ""
    }

    internal fun fetchDuckDuckGoInstantAnswer(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://api.duckduckgo.com/"
            val url = "$base?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string() ?: return ""
                val json = JSONObject(body)
                val heading = json.optString("Heading", "").trim()
                val abstractText = json.optString("AbstractText", "").trim()
                val definition = json.optString("Definition", "").trim()
                val abstractUrl = json.optString("AbstractURL", "").trim()
                val related = json.optJSONArray("RelatedTopics")

                val sb = StringBuilder()
                val mainSummary = abstractText.ifBlank { definition }
                if (mainSummary.isNotBlank()) {
                    if (heading.isNotBlank()) sb.append("**$heading**: ")
                    sb.append(mainSummary)
                    if (abstractUrl.isNotBlank()) sb.append(" ($abstractUrl)")
                    sb.append("\n")
                }
                if (related != null && sb.length < 400) {
                    var added = 0
                    for (i in 0 until related.length()) {
                        if (added >= 2) break
                        val item = related.optJSONObject(i) ?: continue
                        val txt = item.optString("Text", "").trim()
                        if (txt.length > 20) {
                            sb.append("• ").append(txt).append("\n")
                            added++
                        }
                    }
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    internal fun fetchOpenAlexResearch(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://api.openalex.org/"
            val url = "${base}works?search=$encoded&per-page=2&select=title,publication_year,doi,abstract_inverted_index"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string() ?: return ""
                val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
                if (results.length() == 0) return ""
                val sb = StringBuilder()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    val year = item.optInt("publication_year", 0)
                    val doi = item.optString("doi", "").trim()
                    if (title.isBlank()) continue
                    sb.append("• **$title**")
                    if (year > 0) sb.append(" ($year)")
                    if (doi.isNotBlank()) sb.append(" — $doi")
                    val inverted = item.optJSONObject("abstract_inverted_index")
                    val snippet = reconstructInvertedAbstract(inverted, maxWords = 45)
                    if (snippet.isNotBlank()) {
                        sb.append("\n  Summary: ").append(snippet)
                    }
                    sb.append("\n")
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun reconstructInvertedAbstract(inverted: JSONObject?, maxWords: Int = 45): String {
        if (inverted == null || inverted.length() == 0) return ""
        val positioned = sortedMapOf<Int, String>()
        val keys = inverted.keys()
        while (keys.hasNext()) {
            val word = keys.next()
            val arr = inverted.optJSONArray(word) ?: continue
            for (i in 0 until arr.length()) {
                val pos = arr.optInt(i, -1)
                if (pos in 0 until maxWords) {
                    positioned[pos] = word
                }
            }
        }
        return positioned.values.joinToString(" ").trim()
    }

    internal fun fetchArxivPreprints(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://export.arxiv.org/"
            val url = "${base}api/query?search_query=all:$encoded&max_results=2"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val xml = resp.body?.string() ?: return ""
                val entryRegex = Regex("<entry>([\\s\\S]*?)</entry>")
                val titleRegex = Regex("<title>([\\s\\S]*?)</title>")
                val summaryRegex = Regex("<summary>([\\s\\S]*?)</summary>")
                val entries = entryRegex.findAll(xml).take(2).toList()
                if (entries.isEmpty()) return ""
                val sb = StringBuilder()
                for (entry in entries) {
                    val block = entry.groupValues[1]
                    val title = titleRegex.find(block)?.groupValues?.get(1)?.replace(WHITESPACE_REGEX, " ")?.trim() ?: continue
                    val summary = summaryRegex.find(block)?.groupValues?.get(1)?.replace(WHITESPACE_REGEX, " ")?.trim()?.take(240) ?: ""
                    sb.append("• **arXiv:** ").append(title)
                    if (summary.isNotBlank()) sb.append("\n  ").append(summary).append("...")
                    sb.append("\n")
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    internal fun fetchCrossRefResearch(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://api.crossref.org/"
            val url = "${base}works?query=$encoded&rows=2&select=title,published,DOI,author"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body?.string() ?: return ""
                val items = JSONObject(body).optJSONObject("message")?.optJSONArray("items") ?: JSONArray()
                if (items.length() == 0) return ""
                val sb = StringBuilder()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val title = item.optJSONArray("title")?.optString(0)?.trim().orEmpty()
                    if (title.isBlank()) continue
                    val doi = item.optString("DOI", "").trim()
                    val year = item.optJSONObject("published")
                        ?.optJSONArray("date-parts")
                        ?.optJSONArray(0)
                        ?.optInt(0, 0) ?: 0
                    val authorObj = item.optJSONArray("author")?.optJSONObject(0)
                    val authorName = listOfNotNull(
                        authorObj?.optString("given")?.takeIf { it.isNotBlank() },
                        authorObj?.optString("family")?.takeIf { it.isNotBlank() }
                    ).joinToString(" ")

                    sb.append("• **CrossRef:** $title")
                    if (year > 0) sb.append(" ($year)")
                    if (authorName.isNotBlank()) sb.append(" — $authorName")
                    if (doi.isNotBlank()) sb.append(" [DOI: $doi]")
                    sb.append("\n")
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    internal fun fetchWikidataEntities(
        query: String,
        isBengali: Boolean,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, List<WikiCitation>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val lang = if (isBengali) "bn" else "en"
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://www.wikidata.org/"
            val url = "${base}w/api.php?action=wbsearchentities&search=$encoded&language=$lang&uselang=$lang&limit=3&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "" to emptyList()
                val body = resp.body?.string() ?: return "" to emptyList()
                val searchArr = JSONObject(body).optJSONArray("search") ?: return "" to emptyList()
                if (searchArr.length() == 0) return "" to emptyList()
                val sb = StringBuilder("**Wikidata Knowledge Graph:**\n")
                val cits = mutableListOf<WikiCitation>()
                for (i in 0 until searchArr.length()) {
                    val item = searchArr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    val label = item.optString("label", "").trim()
                    val desc = item.optString("description", "").trim()
                    if (label.isBlank()) continue
                    sb.append("• **$label** ($id)")
                    if (desc.isNotBlank()) sb.append(" — $desc")
                    sb.append("\n")
                    if (id.isNotBlank()) {
                        cits.add(WikiCitation("Wikidata: $label ($id)", "https://www.wikidata.org/wiki/$id", "Surface Wiki"))
                    }
                }
                sb.toString().trim() to cits
            }
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    internal fun fetchPubMedResearch(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, List<WikiCitation>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
            val searchReq = Request.Builder()
                .url("${base}esearch.fcgi?db=pubmed&term=$encoded&retmax=2&retmode=json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val ids = httpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return "" to emptyList()
                val body = resp.body?.string() ?: return "" to emptyList()
                val idList = JSONObject(body).optJSONObject("esearchresult")?.optJSONArray("idlist") ?: return "" to emptyList()
                (0 until idList.length()).mapNotNull { idList.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            if (ids.isEmpty()) return "" to emptyList()

            val summaryReq = Request.Builder()
                .url("${base}esummary.fcgi?db=pubmed&id=${ids.joinToString(",")}&retmode=json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(summaryReq).execute().use { resp ->
                if (!resp.isSuccessful) return "" to emptyList()
                val body = resp.body?.string() ?: return "" to emptyList()
                val resultObj = JSONObject(body).optJSONObject("result") ?: return "" to emptyList()
                val sb = StringBuilder("**PubMed / NIH Biomedical Index:**\n")
                val cits = mutableListOf<WikiCitation>()
                for (pmid in ids) {
                    val art = resultObj.optJSONObject(pmid) ?: continue
                    val title = art.optString("title", "").trim()
                    val pubDate = art.optString("pubdate", "").trim()
                    val source = art.optString("source", "").trim()
                    if (title.isBlank()) continue
                    sb.append("• **$title**")
                    if (source.isNotBlank() || pubDate.isNotBlank()) sb.append(" ($source $pubDate)")
                    sb.append(" [PMID: $pmid]\n")
                    cits.add(WikiCitation("PubMed $pmid: ${title.take(60)}", "https://pubmed.ncbi.nlm.nih.gov/$pmid/", "Deep Web Wiki"))
                }
                sb.toString().trim() to cits
            }
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    internal fun fetchCveIntelligence(
        cveId: String?,
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, List<WikiCitation>> {
        return try {
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://cve.circl.lu/api/"
            if (!cveId.isNullOrBlank()) {
                val url = "${base}cve/${URLEncoder.encode(cveId, "UTF-8")}"
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "" to emptyList()
                    val body = resp.body?.string() ?: return "" to emptyList()
                    val json = JSONObject(body)
                    val summary = json.optString("summary", "").ifBlank {
                        json.optJSONObject("containers")?.optJSONObject("cna")
                            ?.optJSONArray("descriptions")?.optJSONObject(0)?.optString("value", "").orEmpty()
                    }
                    val cvss = json.optDouble("cvss", Double.NaN)
                    if (summary.isBlank()) return "" to emptyList()
                    val sb = StringBuilder("**Live CVE Threat Advisory ($cveId):**\n")
                    if (!cvss.isNaN()) sb.append("• **CVSS Score:** $cvss\n")
                    sb.append("• **Summary:** ${summary.take(360)}")
                    val nvdUrl = "https://nvd.nist.gov/vuln/detail/$cveId"
                    return sb.toString().trim() to listOf(WikiCitation("NVD Advisory: $cveId", nvdUrl, "Dark Web & Cyber Wiki"))
                }
            }
            val url = "${base}last/2"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "" to emptyList()
                val body = resp.body?.string() ?: return "" to emptyList()
                val arr = JSONArray(body)
                if (arr.length() == 0) return "" to emptyList()
                val sb = StringBuilder("**Latest Public CVE Vulnerability Advisories (CIRCL Feed):**\n")
                val cits = mutableListOf<WikiCitation>()
                for (i in 0 until minOf(2, arr.length())) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    val summary = item.optString("summary", "").trim()
                    if (id.isNotBlank() && summary.isNotBlank()) {
                        sb.append("• **$id:** ${summary.take(180)}...\n")
                        cits.add(WikiCitation("NVD Advisory: $id", "https://nvd.nist.gov/vuln/detail/$id", "Dark Web & Cyber Wiki"))
                    }
                }
                sb.toString().trim() to cits
            }
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    internal fun fetchTorOnionooTelemetry(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, WikiCitation?> {
        return try {
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://onionoo.torproject.org/"
            val url = "${base}summary?limit=3&running=true"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "" to null
                val body = resp.body?.string() ?: return "" to null
                val json = JSONObject(body)
                val relays = json.optJSONArray("relays") ?: JSONArray()
                val bridges = json.optJSONArray("bridges") ?: JSONArray()
                val published = json.optString("relays_published", "").trim()
                if (relays.length() == 0 && published.isBlank()) return "" to null
                val sampleNicknames = (0 until minOf(3, relays.length())).mapNotNull { idx ->
                    val r = relays.optJSONObject(idx) ?: return@mapNotNull null
                    val nick = r.optString("n", "unnamed")
                    val running = r.optBoolean("r", false)
                    if (running) nick else null
                }
                val text = buildString {
                    append("**Live Tor Project Onionoo Network Telemetry:**\n")
                    if (published.isNotBlank()) append("• **Directory Published (UTC):** $published\n")
                    if (sampleNicknames.isNotEmpty()) {
                        append("• **Sample Active Guard/Middle Relays:** ${sampleNicknames.joinToString(", ")}\n")
                    }
                    append("• **Protocol Verification:** Live directory consensus fetched over public HTTPS clearnet API (`onionoo.torproject.org`).")
                }
                text to WikiCitation(
                    title = "Tor Project Onionoo Live Relay Directory",
                    url = "https://metrics.torproject.org/rs.html",
                    layer = "Dark Web & Cyber Wiki"
                )
            }
        } catch (_: Exception) {
            "" to null
        }
    }

    /**
     * Fetches and cleans a public HTTPS webpage into readable text (SSRF-protected).
     */
    fun fetchReadableWebpage(
        url: String,
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient
    ): Result<Pair<String, String>> {
        if (!GitHubPluginEngine.isSafePublicHttpsUrl(url)) {
            return Result.failure(IllegalArgumentException("Blocked unsafe or non-HTTPS URL: $url"))
        }
        return fetchReadableWebpageInternal(url, httpClient)
    }

    internal fun fetchReadableWebpageInternal(
        url: String,
        httpClient: OkHttpClient
    ): Result<Pair<String, String>> {
        return try {
            val req = Request.Builder()
                .url(url.trim())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html, text/plain, application/xhtml+xml")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(RuntimeException("HTTP ${resp.code}"))
                }
                val rawHtml = resp.body?.string().orEmpty()
                if (rawHtml.isBlank()) {
                    return Result.failure(RuntimeException("Empty response body"))
                }
                val titleMatch = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(rawHtml)
                val title = decodeHtmlEntities(titleMatch?.groupValues?.get(1)?.replace(WHITESPACE_REGEX, " ")?.trim().orEmpty())
                    .ifBlank { url }
                val stripped = rawHtml
                    .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                    .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                    .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
                    .replace(Regex("(?is)<svg[^>]*>.*?</svg>"), " ")
                    .replace(Regex("(?i)</?(p|div|li|h[1-6]|br|tr|section|article)[^>]*>"), "\n")
                    .replace(Regex("<[^>]+>"), " ")
                val cleanText = decodeHtmlEntities(stripped)
                    .lines()
                    .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .take(12_000)

                Result.success(title to cleanText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeHtmlEntities(raw: String): String {
        return raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    fun formatKnowledgePoints(title: String, rawExtract: String, isBengali: Boolean): String {
        val cleanExtract = rawExtract.replace(Regex("\\s+"), " ").trim()
        val sentences = cleanExtract.split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 8 }

        val overview = sentences.take(2).joinToString(" ")
        val bulletPoints = sentences.drop(2).take(6)

        return buildString {
            append(if (isBengali) "**$title** (উইকিপিডিয়া রেফারেন্স)\n\n" else "**$title** (Wikipedia Reference)\n\n")
            append(overview).append("\n\n")
            if (bulletPoints.isNotEmpty()) {
                bulletPoints.forEach { point ->
                    append("• $point\n")
                }
            }
        }.trimEnd()
    }

    /**
     * Extracts salient topic keywords by stripping question affixes.
     */
    fun extractTopicKeyword(prompt: String): String {
        var clean = prompt.trim()
        clean = STOP_WORDS_EN_REGEX.replace(clean, "")
        clean = STOP_WORDS_BN_REGEX.replace(clean, " ")
        clean = PUNCTUATION_REGEX.replace(clean, " ").trim()
        val words = WHITESPACE_REGEX.split(clean).filter { it.isNotBlank() && it.length > 1 }
        return if (words.isNotEmpty()) words.take(5).joinToString(" ") else clean
    }

    /**
     * Evaluates arithmetic expressions deterministically.
     */
    fun tryCalculateMath(prompt: String): String? {
        val clean = prompt
            .replace(Regex("(?i)^(what is|calculate|solve|find|কত|মান কত|বলুন|বলো)\\s+"), "")
            .replace("=", "").replace("?", "").replace("।", "").trim()
        val match = MATH_REGEX.find(clean) ?: return null
        val num1 = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val num2 = match.groupValues[3].toDoubleOrNull() ?: return null

        val (opSymbol, result) = when (op) {
            "+", "যোগ" -> Pair("+", num1 + num2)
            "-", "বিয়োগ" -> Pair("-", num1 - num2)
            "*", "x", "X", "×", "গুণ" -> Pair("×", num1 * num2)
            "/", "÷", "ভাগ" -> Pair("÷", if (num2 != 0.0) num1 / num2 else Double.NaN)
            "^" -> Pair("^", Math.pow(num1, num2))
            "%" -> Pair("%", num1 % num2)
            else -> return null
        }
        val formattedResult = if (result.isNaN()) "অসংজ্ঞায়িত (Undefined - Division by Zero)"
        else if (result % 1.0 == 0.0) result.toLong().toString()
        else String.format(Locale.US, "%.4f", result)

        return "$num1 $opSymbol $num2 = $formattedResult"
    }
}

