package com.ayra.ai.app.ai

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

    private val STOP_WORDS_EN_REGEX = Regex("(?i)^(why does|why is|why do|what is|what are|how does|how to|who is|where is|when did|explain|tell me about|meaning of|definition of)\\s+")
    private val STOP_WORDS_BN_REGEX = Regex("\\b(কেন|কীভাবে|কিভাবে|কোথায়|কখন|কেমন|কার|কোনটি|কী|কি|বলুন|বলো তো|বলো|সম্পর্কে|জানাও|ব্যাখ্যা করো|দেখা যায়|দেখায়|হয়|হলো|কাকে বলে|অর্থ কি|নাম কি|কারণে)\\b")
    private val PUNCTUATION_REGEX = Regex("[?।!.,:;]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MATH_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX×÷^%]|যোগ|বিয়োগ|গুণ|ভাগ)\\s*(\\d+(?:\\.\\d+)?)$")
    private val USER_AGENT = "AyraAiAssistant/3.2 (Linux; Android 14; Mobile; https://ayra.ai/assistant)"

    private fun putCache(key: String, value: String) {
        if (value.isBlank()) return
        if (knowledgeCache.size >= MAX_CACHE_ENTRIES) {
            knowledgeCache.clear()
        }
        knowledgeCache[key] = value
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
        val cacheKey = "multi:${if (isBengali) "bn" else "en"}:${topic.lowercase(Locale.ROOT)}"
        knowledgeCache[cacheKey]?.let { return@supervisorScope it }

        val isDarkOrDeep = GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(cleanPrompt)
        val wantsDeepAcademic = isDarkOrDeep || isAcademicOrTechnicalQuery(cleanPrompt)

        val wikiDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(1600L) {
                queryLiveWebKnowledge(cleanPrompt, isBengali, httpClient, wikipediaBaseUrl)
            } ?: ""
        }

        val ddgDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(1500L) {
                fetchDuckDuckGoInstantAnswer(topic, httpClient, ddgBaseUrl)
            } ?: ""
        }

        val deepAcademicDeferred = async(Dispatchers.IO) {
            if (!wantsDeepAcademic) return@async ""
            withTimeoutOrNull(1800L) {
                val oa = fetchOpenAlexResearch(topic, httpClient, openAlexBaseUrl)
                val ax = if (oa.isBlank() || cleanPrompt.lowercase().contains("arxiv") || cleanPrompt.lowercase().contains("paper")) {
                    fetchArxivPreprints(topic, httpClient, arxivBaseUrl)
                } else ""
                listOf(oa, ax).filter { it.isNotBlank() }.joinToString("\n\n")
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

        if (assembled.isNotBlank() && wikipediaBaseUrl == null && ddgBaseUrl == null && openAlexBaseUrl == null && arxivBaseUrl == null) {
            putCache(cacheKey, assembled)
        }
        assembled
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
        val cacheKey = "wiki:${if (isBengali) "bn" else "en"}:$queryTerm"

        if (baseUrlOverride == null) {
            knowledgeCache[cacheKey]?.let { return it }
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
                return formatted
            }
            val bnSummary = fetchWikiSummary("bn", queryTerm)
            if (bnSummary != null) {
                val formatted = formatKnowledgePoints(bnSummary.first, bnSummary.second, isBengali = true)
                if (baseUrlOverride == null) putCache(cacheKey, formatted)
                return formatted
            }
        }

        val enGen = fetchWikiGeneratorSearch("en", queryTerm) ?: fetchWikiGeneratorSearch("en", prompt.trim())
        if (enGen != null) {
            val formatted = formatKnowledgePoints(enGen.first, enGen.second, isBengali = isBengali)
            if (baseUrlOverride == null) putCache(cacheKey, formatted)
            return formatted
        }

        val enSummary = fetchWikiSummary("en", queryTerm)
        if (enSummary != null) {
            val formatted = formatKnowledgePoints(enSummary.first, enSummary.second, isBengali = isBengali)
            if (baseUrlOverride == null) putCache(cacheKey, formatted)
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

