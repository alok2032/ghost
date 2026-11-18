package com.ayra.ai.app.ui.screens.research

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.rag.VectorKnowledgeRepository
import com.ayra.ai.app.ai.rag.VectorSearchHit
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class WikiLayerFilter(val label: String, val dbLayer: String?) {
    ALL("All Layers", null),
    SURFACE("Surface Wiki", "SURFACE_WIKI"),
    DEEP_WEB("Deep Web Wiki", "DEEP_WEB"),
    DARK_WEB("Dark Web & Tor Tech", "DARK_WEB")
}

@Composable
fun MultiLayerWikiExplorerPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val pool = remember { ProviderPool(context) }
    val db = remember { ChatDatabase.getDatabase(context) }
    val vectorRepo = remember { VectorKnowledgeRepository(db.vectorKnowledgeDao(), context) }

    val totalSources by vectorRepo.observeSourceCount().collectAsStateWithLifecycle(initialValue = 0)
    val totalChunks by vectorRepo.observeChunkCount().collectAsStateWithLifecycle(initialValue = 0)
    val layerCounts by vectorRepo.observeLayerChunkCounts().collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { vectorRepo.ensureSeeded() }
    }

    var query by remember { mutableStateOf("") }
    var layerFilter by remember { mutableStateOf(WikiLayerFilter.ALL) }
    var offlineVaultOnly by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AutonomousKnowledgeEngine.WikiLayerResult?>(null) }
    var vectorHits by remember { mutableStateOf<List<VectorSearchHit>>(emptyList()) }
    var aiSynthesis by remember { mutableStateOf<String?>(null) }
    var statusNote by remember { mutableStateOf<String?>(null) }

    fun runWikiSearch(topicText: String, filter: WikiLayerFilter) {
        val clean = topicText.trim()
        if (clean.isBlank() || isSearching) return
        isSearching = true
        aiSynthesis = null
        statusNote = null
        scope.launch {
            val isBengali = clean.any { it in '\u0980'..'\u09FF' }
            val localOnly = AyraPreferences.isLocalOnlyAiModeEnabled(context) || offlineVaultOnly
            val enrichedQuery = if (filter == WikiLayerFilter.DARK_WEB &&
                !localOnly &&
                !clean.contains("tor", true) && !clean.contains("onion", true) && !clean.contains("dark", true)
            ) "$clean tor dark web" else clean

            val lookup = withContext(Dispatchers.IO) {
                AutonomousKnowledgeEngine.queryMultiLayerWikiWithVectorCache(
                    context = context,
                    prompt = enrichedQuery,
                    isBengali = isBengali,
                    layerFilter = filter.dbLayer,
                    offlineOnlyOverride = offlineVaultOnly,
                    forceAllLayers = true,
                    vectorRepoOverride = vectorRepo
                )
            }
            result = lookup.wikiResult
            vectorHits = lookup.vectorHits
            statusNote = when {
                localOnly -> "Offline Room Vector DB Hybrid Search: ${lookup.vectorHits.size} semantic matches (<15ms local)."
                !lookup.usedOfflineVectorCache && lookup.wikiResult.isFromLiveNetwork ->
                    "Live network response retrieved & auto-cached ${lookup.cachedNewSourcesCount} source(s) to Room Vector DB."
                lookup.usedOfflineVectorCache && lookup.vectorHits.isNotEmpty() ->
                    "Network unavailable — served ${lookup.vectorHits.size} verified matches from Offline Room Vector DB."
                else -> "Showing curated overlay network reference."
            }
            isSearching = false
        }
    }

    val darkChunks = layerCounts.firstOrNull { it.layer == "DARK_WEB" }?.chunkCount ?: 0
    val deepChunks = layerCounts.firstOrNull { it.layer == "DEEP_WEB" }?.chunkCount ?: 0
    val surfaceChunks = layerCounts.firstOrNull { it.layer == "SURFACE_WIKI" }?.chunkCount ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Multi-Layer Wiki & Offline Vector Vault",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = "Live clearnet Wiki (Wikipedia, Wikidata, DDG, OpenAlex, arXiv, PubMed, CVE, Tor Onionoo) auto-vectorized into a 256-dim Room Vector DB for instant offline Dark/Deep Web search.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.5.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Room Vector DB: $totalSources sources • $totalChunks vectors",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("vector_db_stats_text")
                    )
                    Text(
                        text = "Dark Web: $darkChunks • Deep Web: $deepChunks • Surface: $surfaceChunks (256d Cosine + BM25)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.5.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = offlineVaultOnly,
                    onClick = { offlineVaultOnly = !offlineVaultOnly },
                    label = { Text(if (offlineVaultOnly) "Offline Vault Only: ON" else "Offline Vault Only", fontSize = 11.sp) },
                    modifier = Modifier.testTag("wiki_offline_mode_chip")
                )
                OutlinedButton(
                    onClick = {
                        if (isSyncing) return@OutlinedButton
                        isSyncing = true
                        scope.launch {
                            val report = vectorRepo.syncLiveCuratedFeeds()
                            isSyncing = false
                            statusNote = if (report.blockedByLocalOnlyMode) {
                                "Sync skipped: Local-Only AI Mode is enabled."
                            } else {
                                "Synced ${report.syncedSources} live Deep/Dark Web feeds (${report.totalChunksInDb} vectors cached)." +
                                    if (report.failedFeeds.isNotEmpty()) " Unavailable: ${report.failedFeeds.joinToString()}" else ""
                            }
                        }
                    },
                    enabled = !isSyncing,
                    modifier = Modifier.testTag("sync_vector_feeds_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isSyncing) "Syncing..." else "Sync Live Feeds Offline", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            vectorRepo.clearLiveCachedOnly()
                            statusNote = "Cleared cached live fetches; curated Dark/Deep Web corpus preserved."
                        }
                    },
                    modifier = Modifier.testTag("clear_live_vector_cache_button")
                ) {
                    Text("Clear Live Cache", fontSize = 11.sp)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search Wiki, arXiv paper, CVE-ID, or Tor/I2P topic") },
            modifier = Modifier.fillMaxWidth().testTag("wiki_explorer_input"),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WikiLayerFilter.entries.forEach { f ->
                FilterChip(
                    selected = layerFilter == f,
                    onClick = { layerFilter = f },
                    label = { Text(f.label, fontSize = 11.sp) },
                    modifier = Modifier.testTag("wiki_layer_${f.name.lowercase()}")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Tor Onion Routing" to WikiLayerFilter.DARK_WEB,
                "The Hidden Wiki" to WikiLayerFilter.DARK_WEB,
                "I2P Garlic Routing" to WikiLayerFilter.DARK_WEB,
                "Transformer Attention" to WikiLayerFilter.DEEP_WEB,
                "CVE-2024-3094" to WikiLayerFilter.DEEP_WEB,
                "Post-Quantum ML-KEM" to WikiLayerFilter.DEEP_WEB
            ).forEach { (preset, f) ->
                OutlinedButton(
                    onClick = {
                        query = preset
                        layerFilter = f
                        runWikiSearch(preset, f)
                    }
                ) {
                    Text(preset, fontSize = 11.sp)
                }
            }
        }

        Button(
            onClick = { runWikiSearch(query, layerFilter) },
            enabled = query.isNotBlank() && !isSearching,
            modifier = Modifier.fillMaxWidth().testTag("wiki_explorer_search_button")
        ) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    isSearching -> "Searching Vector DB & Live Layers..."
                    offlineVaultOnly -> "Search Offline Room Vector DB"
                    else -> "Search Live + Offline Vector DB"
                }
            )
        }

        statusNote?.let { note ->
            Text(text = note, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }

        if (vectorHits.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Semantic Vector DB Matches (${vectorHits.size})",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                vectorHits.take(4).forEach { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable { runCatching { uriHandler.openUri(hit.sourceUrl) } }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val originLabel = if (hit.originType == "LIVE_CACHED") "LIVE CACHED" else "CURATED"
                        Text(
                            text = "${hit.title} • ${hit.similarityPercent}% match [$originLabel]",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${hit.layer} • ${hit.providerName} • Cosine ${String.format(Locale.US, "%.2f", hit.cosineSimilarity)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            text = hit.excerpt.take(220) + if (hit.excerpt.length > 220) "..." else "",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        result?.let { res ->
            if (res.hasAnyContent()) {
                OutlinedButton(
                    onClick = {
                        if (isSynthesizing) return@OutlinedButton
                        isSynthesizing = true
                        scope.launch {
                            val promptText = "Synthesize a concise, factual summary of these verified Multi-Layer Wiki findings for '${res.topic}':\n\n${res.toFormattedMarkdown()}"
                            aiSynthesis = withContext(Dispatchers.IO) {
                                runCatching {
                                    pool.executeSync(
                                        ChatRequest(
                                            prompt = promptText,
                                            persona = "PROFESSIONAL",
                                            mode = ProviderPoolMode.FAST
                                        )
                                    ).text
                                }.getOrElse { err -> "AI synthesis unavailable: ${err.localizedMessage ?: "Configure an AI key first."}" }
                            }
                            isSynthesizing = false
                        }
                    },
                    enabled = !isSynthesizing,
                    modifier = Modifier.fillMaxWidth().testTag("wiki_ai_synthesize_button")
                ) {
                    Text(if (isSynthesizing) "Synthesizing with AI..." else "AI Synthesize Report from Wiki Sources")
                }

                aiSynthesis?.let { syn ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("AI Synthesized Brief", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Text(syn, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, lineHeight = 18.sp)
                    }
                }

                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.SURFACE) {
                    val surfaceText = listOf(res.surfaceWiki, res.wikidataEntities, res.ddgInstant)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (surfaceText.isNotBlank()) {
                        WikiSectionCard("Surface Wiki (Wikipedia • Wikidata • DDG)", surfaceText, MaterialTheme.colorScheme.primary)
                    }
                }
                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.DEEP_WEB) {
                    val deepText = listOf(res.deepAcademic, res.pubmedArticles, res.cveIntel)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (deepText.isNotBlank()) {
                        WikiSectionCard("Deep Web Wiki (OpenAlex • arXiv • CrossRef • PubMed • CVE)", deepText, MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.DARK_WEB) {
                    val darkText = listOf(res.liveTorNetwork, res.darkWebTech)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (darkText.isNotBlank()) {
                        WikiSectionCard("Dark Web & Overlay Network Technical Wiki (Live Onionoo + Specs)", darkText, MaterialTheme.colorScheme.secondary)
                    }
                }

                if (res.citations.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Verified Clickable Sources", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        res.citations.distinctBy { it.url }.take(8).forEach { cit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        runCatching { uriHandler.openUri(cit.url) }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cit.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("${cit.layer} • ${cit.url}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, maxLines = 1)
                                }
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open source", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiSectionCard(title: String, body: String, accentColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        Text(text = body, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}
