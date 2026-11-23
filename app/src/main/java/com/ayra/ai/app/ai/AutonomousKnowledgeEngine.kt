package com.ayra.ai.app.ai

import androidx.compose.runtime.Immutable
import android.content.Context
import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Real-time Multi-Layer Wiki & Encyclopedic Grounding Engine:
 * 1. Surface Wiki: Live Wikipedia (bn/en) + DuckDuckGo Instant Answer API.
 * 2. Deep Web Open Academic Wiki: OpenAlex scholarly works + arXiv open research index.
 * 3. Dark Web & Overlay Network Technical Wiki: Educational protocol index via GlobalWebKnowledgeGateway.
 * Plus deterministic arithmetic evaluation.
 */
object AutonomousKnowledgeEngine {

    @Immutable
    data class WikiCitation(
        val title: String,
        val url: String,
        val layer: String
    )

    @Immutable
    data class WikiLayerResult(
        val topic: String,
        val surfaceWiki: String = "",
        val wikidataEntities: String = "",
        val ddgInstant: String = "",
        val deepAcademic: String = "",
        val pubmedArticles: String = "",
        val cveIntel: String = "",
        val liveTorNetwork: String = "",
        val darkWebTech: String = "",
        val citations: List<WikiCitation> = emptyList(),
        val isFromLiveNetwork: Boolean = false
    ) {
        fun hasAnyContent(): Boolean = listOf(
            surfaceWiki,
            wikidataEntities,
            ddgInstant,
            deepAcademic,
            pubmedArticles,
            cveIntel,
            liveTorNetwork,
            darkWebTech
        ).any { it.isNotBlank() }

        fun toFormattedMarkdown(): String = buildString {
            val surface = listOf(surfaceWiki, wikidataEntities, ddgInstant).filter { it.isNotBlank() }.joinToString("\n\n")
            if (surface.isNotBlank()) {
                append("### [SURFACE WIKI — WIKIPEDIA / WIKIDATA / DDG]\n").append(surface).append("\n\n")
            }
            val deep = listOf(deepAcademic, pubmedArticles, cveIntel).filter { it.isNotBlank() }.joinToString("\n\n")
            if (deep.isNotBlank()) {
                append("### [DEEP WEB WIKI — OPENALEX / ARXIV / PUBMED / CVE]\n").append(deep).append("\n\n")
            }
            val dark = listOf(liveTorNetwork, darkWebTech).filter { it.isNotBlank() }.joinToString("\n\n")
            if (dark.isNotBlank()) {
                append("### [DARK WEB & OVERLAY NETWORK TECHNICAL WIKI]\n").append(dark).append("\n\n")
            }
            if (citations.isNotEmpty()) {
                append("### [VERIFIED LIVE SOURCES]\n")
                citations.distinctBy { it.url }.forEach { cit ->
                    append("• [${cit.title}](${cit.url}) (${cit.layer})\n")
                }
                append("\n")
            }
        }.trim()
    }

    data class CachedWikiLookupResult(
        val wikiResult: WikiLayerResult,
        val vectorHits: List<com.ayra.ai.app.ai.rag.VectorSearchHit> = emptyList(),
        val usedOfflineVectorCache: Boolean = false,
        val cachedNewSourcesCount: Int = 0
    )

    private const val MAX_CACHE_ENTRIES = 250
    private val cacheLock = Any()
    private val knowledgeCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }
    private val CVE_ID_REGEX = Regex("CVE-\\d{4}-\\d{4,7}", RegexOption.IGNORE_CASE)

    private val STOP_WORDS_EN_REGEX = Regex("(?i)^(why does|why is|why do|what is|what are|how does|how to|who is|where is|when did|explain|tell me about|meaning of|definition of)\\s+")
    private val STOP_WORDS_BN_REGEX = Regex("\\b(কেন|কীভাবে|কিভাবে|কোথায়|কখন|কেমন|কার|কোনটি|কী|কি|বলুন|বলো তো|বলো|সম্পর্কে|জানাও|ব্যাখ্যা করো|দেখা যায়|দেখায়|হয়|হলো|কাকে বলে|অর্থ কি|নাম কি|কারণে)\\b")
    private val PUNCTUATION_REGEX = Regex("[?।!.,:;]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MATH_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX×÷^%]|যোগ|বিয়োগ|গুণ|ভাগ)\\s*(\\d+(?:\\.\\d+)?)$")
    private val USER_AGENT = "AyraAiAssistant/3.2 (Linux; Android 14; Mobile; https://ayra-ai.app/assistant)"

    internal fun getCache(key: String): String? = synchronized(cacheLock) {
        knowledgeCache[key]
    }

    internal fun putCache(key: String, value: String) {
        if (value.isBlank()) return
        synchronized(cacheLock) {
            knowledgeCache[key] = value
        }
    }

    internal fun cacheSizeForTests(): Int = synchronized(cacheLock) {
        knowledgeCache.size
    }

    internal fun clearCacheForTests() {
        synchronized(cacheLock) {
            knowledgeCache.clear()
        }
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
        arxivBaseUrl: String? = null
    ): String = supervisorScope {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.length < 3) return@supervisorScope ""

        val topic = extractTopicKeyword(cleanPrompt).ifBlank { cleanPrompt }
        val isTimeSensitive = com.ayra.ai.app.ai.providers.ProviderPool.isTimeSensitiveQuery(cleanPrompt)
        val cacheKey = "multi:${if (isBengali) "bn" else "en"}:${topic.lowercase(Locale.ROOT)}"
        if (!isTimeSensitive) {
            getCache(cacheKey)?.let { return@supervisorScope it }
        }

        val isDarkOrDeep = GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(cleanPrompt)
        val wantsDeepAcademic = isDarkOrDeep || isAcademicOrTechnicalQuery(cleanPrompt)

        val wikiDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(2400L) {
                queryLiveWebKnowledge(cleanPrompt, isBengali, httpClient, wikipediaBaseUrl)
            } ?: ""
        }

        val ddgDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(2000L) {
                fetchDuckDuckGoInstantAnswer(topic, httpClient, ddgBaseUrl)
            } ?: ""
        }

        val deepAcademicDeferred = async(Dispatchers.IO) {
            if (!wantsDeepAcademic) return@async ""
            withTimeoutOrNull(2400L) {
                val oa = fetchOpenAlexResearch(topic, httpClient, openAlexBaseUrl)
                val ax = if (oa.isBlank() || cleanPrompt.lowercase().contains("arxiv") || cleanPrompt.lowercase().contains("paper")) {
                    fetchArxivPreprints(topic, httpClient, arxivBaseUrl)
                } else ""
                val cr = if (oa.isBlank() || cleanPrompt.lowercase().contains("doi") || cleanPrompt.lowercase().contains("journal") || cleanPrompt.lowercase().contains("citation")) {
                    fetchCrossRefResearch(topic, httpClient)
                } else ""
                listOf(oa, ax, cr).filter { it.isNotBlank() }.joinToString("\n\n")
            } ?: ""
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

        val wikiText = wikiDeferred.await()
        val ddgText = ddgDeferred.await()
        val academicText = deepAcademicDeferred.await()
        val darkTechText = darkTechDeferred.await()

        val assembled = buildString {
            if (wikiText.isNotBlank()) {
                append("### [SURFACE WIKI — WIKIPEDIA]\n")
                append(wikiText.take(1200)).append("\n\n")
            }
            if (ddgText.isNotBlank()) {
                append("### [LIVE INSTANT KNOWLEDGE — DUCKDUCKGO]\n")
                append(ddgText.take(700)).append("\n\n")
            }
            if (academicText.isNotBlank()) {
                append("### [DEEP WEB ACADEMIC WIKI — OPENALEX / ARXIV]\n")
                append(academicText.take(1000)).append("\n\n")
            }
            if (darkTechText.isNotBlank()) {
                append("### [DARK WEB & OVERLAY NETWORK TECHNICAL WIKI]\n")
                append(darkTechText.take(1400)).append("\n\n")
            }
        }.trim()

        if (!isTimeSensitive && assembled.isNotBlank() && wikipediaBaseUrl == null && ddgBaseUrl == null && openAlexBaseUrl == null && arxivBaseUrl == null) {
            putCache(cacheKey, assembled)
        }
        assembled
    }

    suspend fun queryMultiLayerWikiWithVectorCache(
        context: Context,
        prompt: String,
        isBengali: Boolean,
        layerFilter: String? = null,
        offlineOnlyOverride: Boolean? = null,
        forceAllLayers: Boolean = false,
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
    ): CachedWikiLookupResult = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        val topic = extractTopicKeyword(cleanPrompt).ifBlank { cleanPrompt }
        val vectorRepo = vectorRepoOverride ?: com.ayra.ai.app.ai.rag.VectorKnowledgeRepository(
            ChatDatabase.getDatabase(context).vectorKnowledgeDao(),
            context
        )
        vectorRepo.ensureSeeded()

        val localOnly = offlineOnlyOverride ?: AyraPreferences.isLocalOnlyAiModeEnabled(context)
        if (localOnly || cleanPrompt.length < 2) {
            val hits = vectorRepo.search(cleanPrompt.ifBlank { topic }, layerFilter = layerFilter, topK = 6)
            val offlineWiki = vectorRepo.searchAsWikiLayerResult(cleanPrompt.ifBlank { topic }, layerFilter = layerFilter, topK = 6)
            return@withContext CachedWikiLookupResult(
                wikiResult = offlineWiki,
                vectorHits = hits,
                usedOfflineVectorCache = true,
                cachedNewSourcesCount = 0
            )
        }

        val liveResult = queryMultiLayerWikiStructured(
            prompt = cleanPrompt,
            isBengali = isBengali,
            layerFilter = layerFilter,
            forceAllLayers = forceAllLayers,
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

        if (liveResult.isFromLiveNetwork && liveResult.hasAnyContent()) {
            val cachedCount = try {
                vectorRepo.cacheWikiLayerResult(liveResult, if (isBengali) "bn" else "en")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            val hits = vectorRepo.search(cleanPrompt, layerFilter = layerFilter, topK = 6)
            return@withContext CachedWikiLookupResult(
                wikiResult = liveResult,
                vectorHits = hits,
                usedOfflineVectorCache = false,
                cachedNewSourcesCount = cachedCount
            )
        }

        val hits = vectorRepo.search(cleanPrompt, layerFilter = layerFilter, topK = 6)
        val offlineWiki = vectorRepo.searchAsWikiLayerResult(cleanPrompt, layerFilter = layerFilter, topK = 6)
        CachedWikiLookupResult(
            wikiResult = if (offlineWiki.hasAnyContent()) offlineWiki else liveResult,
            vectorHits = hits,
            usedOfflineVectorCache = true,
            cachedNewSourcesCount = 0
        )
    }

    suspend fun queryMultiLayerWikiStructured(
        prompt: String,
        isBengali: Boolean,
        layerFilter: String? = null,
        forceAllLayers: Boolean = false,
        httpClient: OkHttpClient = ExternalAiProviders.sharedFastWebClient,
        wikipediaBaseUrl: String? = null,
        ddgBaseUrl: String? = null,
        openAlexBaseUrl: String? = null,
        arxivBaseUrl: String? = null,
        wikidataBaseUrl: String? = null,
        pubmedBaseUrl: String? = null,
        cveBaseUrl: String? = null,
        onionooBaseUrl: String? = null
    ): WikiLayerResult = supervisorScope {
        val cleanPrompt = prompt.trim()
        val topic = extractTopicKeyword(cleanPrompt).ifBlank { cleanPrompt }
        val isDarkOrDeep = forceAllLayers || GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(cleanPrompt)
        val wantsDeepAcademic = forceAllLayers || isDarkOrDeep || isAcademicOrTechnicalQuery(cleanPrompt)
        val wantSurface = layerFilter == null || layerFilter == "SURFACE_WIKI"
        val wantDeep = layerFilter == null || layerFilter == "DEEP_WEB"
        val wantDark = layerFilter == null || layerFilter == "DARK_WEB"
        val cveMatch = CVE_ID_REGEX.find(cleanPrompt)?.value?.uppercase(Locale.ROOT)

        val wikiDef = async(Dispatchers.IO) {
            if (!wantSurface) return@async ""
            withTimeoutOrNull(2000L) { queryLiveWebKnowledge(cleanPrompt, isBengali, httpClient, wikipediaBaseUrl) } ?: ""
        }
        val wikidataDef = async(Dispatchers.IO) {
            if (!wantSurface) return@async Pair("", emptyList<WikiCitation>())
            withTimeoutOrNull(1800L) { fetchWikidataEntities(topic, isBengali, httpClient, wikidataBaseUrl) }
                ?: Pair("", emptyList())
        }
        val ddgDef = async(Dispatchers.IO) {
            if (!wantSurface) return@async ""
            withTimeoutOrNull(1600L) { fetchDuckDuckGoInstantAnswer(topic, httpClient, ddgBaseUrl) } ?: ""
        }
        val deepAcademicDef = async(Dispatchers.IO) {
            if (!wantDeep || !wantsDeepAcademic) return@async ""
            withTimeoutOrNull(2200L) {
                val oa = fetchOpenAlexResearch(topic, httpClient, openAlexBaseUrl)
                val ax = fetchArxivPreprints(topic, httpClient, arxivBaseUrl)
                val cr = if (oa.isBlank() && openAlexBaseUrl == null) fetchCrossRefResearch(topic, httpClient) else ""
                listOf(oa, ax, cr).filter { it.isNotBlank() }.joinToString("\n\n")
            } ?: ""
        }
        val pubmedDef = async(Dispatchers.IO) {
            if (!wantDeep || !wantsDeepAcademic) return@async Pair("", emptyList<WikiCitation>())
            withTimeoutOrNull(1800L) { fetchPubMedResearch(topic, httpClient, pubmedBaseUrl) }
                ?: Pair("", emptyList())
        }
        val cveDef = async(Dispatchers.IO) {
            if (!wantDeep || (cveMatch == null && !forceAllLayers && !cleanPrompt.contains("cve", true))) {
                return@async Pair("", emptyList<WikiCitation>())
            }
            withTimeoutOrNull(2000L) { fetchCveIntelligence(cveMatch, topic, httpClient, cveBaseUrl) }
                ?: Pair("", emptyList())
        }
        val onionooDef = async(Dispatchers.IO) {
            if (!wantDark || (!isDarkOrDeep && !forceAllLayers)) return@async Pair("", null)
            withTimeoutOrNull(2000L) { fetchTorOnionooTelemetry(topic, httpClient, onionooBaseUrl) }
                ?: Pair("", null)
        }
        val darkTechDef = async(Dispatchers.IO) {
            if (!wantDark || !isDarkOrDeep) return@async ""
            GlobalWebKnowledgeGateway.synthesizeGlobalNetworkKnowledge(cleanPrompt, isBengali, isGfMode = false)
        }

        val surfaceWiki = wikiDef.await()
        val (wikidataEntities, wikidataCits) = wikidataDef.await()
        val ddgInstant = ddgDef.await()
        val deepAcademic = deepAcademicDef.await()
        val (pubmedArticles, pubmedCits) = pubmedDef.await()
        val (cveIntel, cveCits) = cveDef.await()
        val (liveTorNetwork, onionCit) = onionooDef.await()
        val darkWebTech = darkTechDef.await()

        val citations = buildList {
            if (surfaceWiki.isNotBlank()) {
                val lang = if (isBengali) "bn" else "en"
                add(WikiCitation("Wikipedia: $topic", "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(topic.replace(" ", "_"), "UTF-8")}", "Surface Wiki"))
            }
            addAll(wikidataCits)
            if (deepAcademic.isNotBlank()) {
                add(WikiCitation("OpenAlex / arXiv: $topic", "https://openalex.org/works?search=${URLEncoder.encode(topic, "UTF-8")}", "Deep Web Academic"))
            }
            addAll(pubmedCits)
            addAll(cveCits)
            if (onionCit != null) add(onionCit)
        }

        val liveHit = listOf(surfaceWiki, wikidataEntities, ddgInstant, deepAcademic, pubmedArticles, cveIntel, liveTorNetwork)
            .any { it.isNotBlank() }

        WikiLayerResult(
            topic = topic,
            surfaceWiki = surfaceWiki,
            wikidataEntities = wikidataEntities,
            ddgInstant = ddgInstant,
            deepAcademic = deepAcademic,
            pubmedArticles = pubmedArticles,
            cveIntel = cveIntel,
            liveTorNetwork = liveTorNetwork,
            darkWebTech = darkWebTech,
            citations = citations,
            isFromLiveNetwork = liveHit
        )
    }

    internal fun fetchTorOnionooTelemetry(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, WikiCitation?> {
        return try {
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://onionoo.torproject.org/"
            val encoded = URLEncoder.encode(query.ifBlank { "tor" }, "UTF-8")
            val url = "${base}summary?limit=6&search=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair("", null)
                val body = resp.readBounded().ifBlank { return Pair("", null) }
                val json = JSONObject(body)
                val published = json.optString("relays_published", "").trim()
                val relays = json.optJSONArray("relays") ?: JSONArray()
                if (relays.length() == 0) return Pair("", null)
                val sb = StringBuilder()
                if (published.isNotBlank()) sb.append("Tor Onionoo Consensus Published: ").append(published).append("\n")
                for (i in 0 until relays.length()) {
                    val r = relays.optJSONObject(i) ?: continue
                    val nick = r.optString("n", r.optString("nickname", "")).trim()
                    if (nick.isBlank()) continue
                    val running = r.optBoolean("r", r.optBoolean("running", true))
                    sb.append("• Relay: ").append(nick).append(" (running=").append(running).append(")\n")
                }
                val text = sb.toString().trim()
                if (text.isBlank()) Pair("", null)
                else Pair(text, WikiCitation("Tor Project Onionoo Consensus", "https://metrics.torproject.org/rs.html", "Dark Web Telemetry"))
            }
        } catch (_: Exception) {
            Pair("", null)
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
            val url = if (!cveId.isNullOrBlank()) "${base}cve/$cveId" else "${base}last/5"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair("", emptyList())
                val body = resp.readBounded().trim()
                if (body.isBlank()) return Pair("", emptyList())
                val entries = mutableListOf<JSONObject>()
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
                } else if (body.startsWith("{")) {
                    entries.add(JSONObject(body))
                }
                if (entries.isEmpty()) return Pair("", emptyList())
                val sb = StringBuilder()
                val citations = mutableListOf<WikiCitation>()
                entries.take(5).forEach { obj ->
                    val id = obj.optString("id", cveId ?: query).trim()
                    val summary = obj.optString("summary", "").trim()
                    val cvss = if (obj.has("cvss") && !obj.isNull("cvss")) obj.optDouble("cvss", Double.NaN) else Double.NaN
                    if (id.isNotBlank() && summary.isNotBlank()) {
                        sb.append("• **$id**")
                        if (!cvss.isNaN() && cvss.isFinite()) {
                            sb.append(" — **CVSS Score:** $cvss")
                        }
                        sb.append(": ").append(summary).append("\n")
                        citations.add(WikiCitation("CVE Advisory: $id", "https://nvd.nist.gov/vuln/detail/$id", "Deep Web CVE"))
                    }
                }
                Pair(sb.toString().trim(), citations)
            }
        } catch (_: Exception) {
            Pair("", emptyList())
        }
    }

    internal fun fetchWikidataEntities(
        query: String,
        isBengali: Boolean,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, List<WikiCitation>> {
        return try {
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://www.wikidata.org/"
            val lang = if (isBengali) "bn" else "en"
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${base}w/api.php?action=wbsearchentities&search=$encoded&language=$lang&format=json&limit=2"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair("", emptyList())
                val body = resp.readBounded().ifBlank { return Pair("", emptyList()) }
                val arr = JSONObject(body).optJSONArray("search") ?: return Pair("", emptyList())
                val sb = StringBuilder()
                val citations = mutableListOf<WikiCitation>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    val label = item.optString("label", "").trim()
                    val desc = item.optString("description", "").trim()
                    if (label.isNotBlank()) {
                        val idSuffix = if (id.isNotBlank()) " ($id)" else ""
                        sb.append("• **Wikidata$idSuffix — $label**: $desc\n")
                        if (id.isNotBlank()) {
                            citations.add(WikiCitation("Wikidata: $label ($id)", "https://www.wikidata.org/wiki/$id", "Surface Wiki"))
                        }
                    }
                }
                Pair(sb.toString().trim(), citations)
            }
        } catch (_: Exception) {
            Pair("", emptyList())
        }
    }

    internal fun fetchPubMedResearch(
        query: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): Pair<String, List<WikiCitation>> {
        return try {
            val base = baseUrlOverride?.let { if (it.endsWith("/")) it else "$it/" }
                ?: "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${base}esearch.fcgi?db=pubmed&term=$encoded&retmax=2&retmode=json"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            val pmids = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair("", emptyList())
                val body = resp.readBounded().ifBlank { return Pair("", emptyList()) }
                val ids = JSONObject(body).optJSONObject("esearchresult")?.optJSONArray("idlist")
                    ?: return Pair("", emptyList())
                (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { id -> id.isNotBlank() } }
            }
            if (pmids.isEmpty()) return Pair("", emptyList())

            val citations = mutableListOf<WikiCitation>()
            val sb = StringBuilder()
            val summaryUrl = "${base}esummary.fcgi?db=pubmed&id=${pmids.joinToString(",")}&retmode=json"
            val summaryReq = Request.Builder().url(summaryUrl).header("User-Agent", USER_AGENT).get().build()
            val summaryResult = try {
                httpClient.newCall(summaryReq).execute().use { sResp ->
                    if (sResp.isSuccessful) {
                        val sBody = sResp.readBounded()
                        if (sBody.isNotBlank()) JSONObject(sBody).optJSONObject("result") else null
                    } else null
                }
            } catch (_: Exception) {
                null
            }

            pmids.forEach { pmid ->
                val doc = summaryResult?.optJSONObject(pmid)
                val title = doc?.optString("title", "")?.trim().orEmpty()
                val source = doc?.optString("source", "")?.trim().orEmpty()
                val pubdate = doc?.optString("pubdate", "")?.trim().orEmpty()
                val link = "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
                if (title.isNotBlank()) {
                    sb.append("• **PubMed (PMID $pmid):** $title")
                    val meta = listOf(source, pubdate).filter { it.isNotBlank() }.joinToString(" • ")
                    if (meta.isNotBlank()) sb.append(" ($meta)")
                    sb.append(" — $link\n")
                    citations.add(WikiCitation("PubMed: $title", link, "Deep Web PubMed"))
                } else {
                    sb.append("• **PubMed PMID $pmid:** $link\n")
                    citations.add(WikiCitation("PubMed PMID $pmid", link, "Deep Web PubMed"))
                }
            }

            Pair(sb.toString().trim(), citations)
        } catch (_: Exception) {
            Pair("", emptyList())
        }
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
        val topic = extractTopicKeyword(prompt)
        val queryTerm = if (topic.isNotBlank()) topic else prompt.trim()
        if (queryTerm.isBlank()) return ""
        val isTimeSensitive = com.ayra.ai.app.ai.providers.ProviderPool.isTimeSensitiveQuery(prompt)
        val cacheKey = "wiki:${if (isBengali) "bn" else "en"}:$queryTerm"

        if (baseUrlOverride == null && !isTimeSensitive) {
            getCache(cacheKey)?.let { return it }
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
                    val body = resp.readBounded()
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
                    val body = resp.readBounded()
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
                if (baseUrlOverride == null && !isTimeSensitive) putCache(cacheKey, formatted)
                return formatted
            }
            val bnSummary = fetchWikiSummary("bn", queryTerm)
            if (bnSummary != null) {
                val formatted = formatKnowledgePoints(bnSummary.first, bnSummary.second, isBengali = true)
                if (baseUrlOverride == null && !isTimeSensitive) putCache(cacheKey, formatted)
                return formatted
            }
        }

        val enGen = fetchWikiGeneratorSearch("en", queryTerm) ?: fetchWikiGeneratorSearch("en", prompt.trim())
        if (enGen != null) {
            val formatted = formatKnowledgePoints(enGen.first, enGen.second, isBengali = isBengali)
            if (baseUrlOverride == null && !isTimeSensitive) putCache(cacheKey, formatted)
            return formatted
        }

        val enSummary = fetchWikiSummary("en", queryTerm)
        if (enSummary != null) {
            val formatted = formatKnowledgePoints(enSummary.first, enSummary.second, isBengali = isBengali)
            if (baseUrlOverride == null && !isTimeSensitive) putCache(cacheKey, formatted)
            return formatted
        }

        return ""
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
                val body = resp.readBounded().ifBlank { return "" }
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
                val body = resp.readBounded().ifBlank { return "" }
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
                val xml = resp.readBounded().ifBlank { return "" }
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
                val body = resp.readBounded().ifBlank { return "" }
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
                val rawHtml = resp.readBounded(truncateOnOverflow = true)
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

