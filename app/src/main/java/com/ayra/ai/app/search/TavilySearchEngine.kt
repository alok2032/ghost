package com.ayra.ai.app.search

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.security.NetworkConnectivityManager
import com.ayra.ai.app.security.NetworkSecurityHelper
import com.ayra.ai.app.security.NetworkSecurityHelper.readBounded
import com.ayra.ai.app.security.NonRetryableHttpException
import com.ayra.ai.app.security.RetryableHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production Tavily Live Web Search Engine.
 * Delivers real, citation-grounded live search results from across the web.
 * Uses on-device AES-256 encrypted API key.
 */
class TavilySearchEngine(private val context: Context) {

    private val httpClient = com.ayra.ai.app.security.NetworkSecurityHelper.hardenedPooledClient
        .newBuilder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(20L, TimeUnit.SECONDS)
        .callTimeout(35L, TimeUnit.SECONDS)
        .build()

    data class SearchResult(
        val title: String,
        val url: String,
        val content: String,
        val score: Double = 0.0,
        val publishedDate: String? = null,
        val domain: String = ""
    )

    data class SearchResponse(
        val query: String,
        val results: List<SearchResult>,
        val formattedSummary: String,
        val latencyMs: Long,
        val hasConflictingClaims: Boolean = false
    )

    /**
     * Executes real web search query via Tavily API.
     * Returns structured results, domain/date provenance, and formatted citation block.
     */
    suspend fun search(
        query: String,
        maxResults: Int = 4,
        searchDepth: String = "basic"
    ): SearchResponse = withContext(Dispatchers.IO) {
        val apiKey = AyraPreferences.getTavilySearchKey(context).trim()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("No Tavily Search API key configured. Please add one in AI Settings.")
        }

        val start = System.currentTimeMillis()

        val jsonPayload = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query.trim())
            put("search_depth", searchDepth)
            put("include_answer", true)
            put("max_results", maxResults.coerceIn(1, 10))
        }

        val body = try {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 2) {
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        throw NonRetryableHttpException(resp.code, "Invalid Tavily API Key (HTTP ${resp.code}). Please verify your key at tavily.com.")
                    }
                    if (resp.code == 429) {
                        throw RetryableHttpException(resp.code, "Tavily search rate limit or quota exceeded.")
                    }
                    if (resp.code in 502..504) {
                        throw RetryableHttpException(resp.code, "Tavily Search API temporarily unavailable: HTTP ${resp.code}")
                    }
                    if (!resp.isSuccessful) {
                        throw NonRetryableHttpException(resp.code, "Tavily Search API error: HTTP ${resp.code} ${resp.message}")
                    }
                    resp.readBounded().ifBlank { throw IOException("Empty response from Tavily search.") }
                }
            }.also {
                NetworkConnectivityManager.recordObservedNetworkSuccess()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkConnectivityManager.recordObservedNetworkFailure(error = e)
            throw e
        }
        val json = JSONObject(body)

            val resultsArray = json.optJSONArray("results") ?: JSONArray()
            val searchResults = mutableListOf<SearchResult>()

            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val urlStr = item.optString("url", "")
                val hostDomain = runCatching { java.net.URI(urlStr).host.orEmpty().removePrefix("www.") }.getOrDefault("")
                val pubDate = item.optString("published_date", "").ifBlank { null }
                searchResults.add(
                    SearchResult(
                        title = item.optString("title", "Untitled Source"),
                        url = urlStr,
                        content = item.optString("content", "").take(600),
                        score = item.optDouble("score", 0.0),
                        publishedDate = pubDate,
                        domain = hostDomain
                    )
                )
            }

            val hasConflict = detectCrossSourceConflict(searchResults)
            val directAnswer = json.optString("answer", "")
            val sb = StringBuilder()
            if (directAnswer.isNotBlank()) {
                sb.append("### 🌐 Live Web Search Overview\n")
                sb.append(directAnswer.trim()).append("\n\n")
            }

            if (searchResults.isNotEmpty()) {
                if (hasConflict) {
                    sb.append("> ⚠️ *Source Note: Retrieved sources show differing years or conflicting quantitative claims. Verify primary citations below.*\n\n")
                }
                sb.append("### 📚 Grounded Web Sources & Citations\n")
                searchResults.forEachIndexed { index, res ->
                    val metaParts = buildList {
                        if (res.domain.isNotBlank()) add(res.domain)
                        if (!res.publishedDate.isNullOrBlank()) add(res.publishedDate)
                    }
                    val metaSuffix = if (metaParts.isNotEmpty()) " *(${metaParts.joinToString(" • ")})*" else ""
                    sb.append("**[${index + 1}] [${res.title}](${res.url})**$metaSuffix\n")
                    sb.append("> ${res.content.trim()}\n\n")
                }
            } else if (directAnswer.isBlank()) {
                sb.append("No verified web sources found for \"$query\".")
            }

            SearchResponse(
                query = query,
                results = searchResults,
                formattedSummary = sb.toString(),
                latencyMs = System.currentTimeMillis() - start,
                hasConflictingClaims = hasConflict
            )
    }

    private fun detectCrossSourceConflict(results: List<SearchResult>): Boolean {
        if (results.size < 2) return false
        val yearsPerSource = results.map { res ->
            Regex("\\b(19|20)\\d{2}\\b").findAll("${res.title} ${res.content}").map { it.value }.toSet()
        }.filter { it.isNotEmpty() }
        val distinctYears = yearsPerSource.flatten().toSet()
        val hasDisputedCue = results.any { r ->
            val l = r.content.lowercase()
            l.contains("disputed") || l.contains("contradict") || l.contains("corrected") || l.contains("however,")
        }
        return (distinctYears.size >= 3 && yearsPerSource.size >= 2) || hasDisputedCue
    }

    /**
     * Real connection test against Tavily API.
     * Returns latency in milliseconds or throws descriptive exception.
     */
    suspend fun testConnection(customKey: String? = null): Long = withContext(Dispatchers.IO) {
        val apiKey = (customKey ?: AyraPreferences.getTavilySearchKey(context)).trim()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("Tavily API key is empty.")
        }

        val start = System.currentTimeMillis()
        val jsonPayload = JSONObject().apply {
            put("api_key", apiKey)
            put("query", "ping test")
            put("search_depth", "basic")
            put("max_results", 1)
        }

        try {
            NetworkSecurityHelper.executeWithSafeRetry(maxAttempts = 2) {
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    resp.readBounded()
                    if (resp.code == 401 || resp.code == 403) {
                        throw NonRetryableHttpException(resp.code, "Invalid Tavily API key (HTTP ${resp.code})")
                    }
                    if (resp.code == 429 || resp.code in 502..504) {
                        throw RetryableHttpException(resp.code, "Tavily ping error: HTTP ${resp.code}")
                    }
                    if (!resp.isSuccessful) {
                        throw NonRetryableHttpException(resp.code, "Tavily ping error: HTTP ${resp.code}")
                    }
                }
            }
            NetworkConnectivityManager.recordObservedNetworkSuccess()
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkConnectivityManager.recordObservedNetworkFailure(error = e)
            throw e
        }
    }
}
