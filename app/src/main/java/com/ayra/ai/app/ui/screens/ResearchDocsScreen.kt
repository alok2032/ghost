package com.ayra.ai.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.ai.rag.RagSearchHit
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.GlassSurfaceDark
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.NeonGreen
import com.ayra.ai.app.ui.theme.NeonPurple
import com.ayra.ai.app.ui.theme.NeonRose
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalUriHandler
import com.ayra.ai.app.data.AyraPreferences

private enum class ResearchTab(val label: String) {
    DEEP_RESEARCH("Deep Research"),
    MULTI_WIKI("Multi-Layer Wiki"),
    KNOWLEDGE_RAG("Knowledge Base (RAG)")
}

@Composable
fun ResearchDocsScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(ResearchTab.DEEP_RESEARCH) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("research_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Research, Multi-Wiki & RAG Hub",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Surface Wiki, Deep Web Academic/CVE/PubMed, Dark Web Tor Telemetry, HTTPS Reader & Room RAG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ResearchTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { activeTab = tab },
                    label = { Text(tab.label, fontSize = 12.sp) },
                    modifier = Modifier.testTag("research_subtab_${tab.name.lowercase()}")
                )
            }
        }

        when (activeTab) {
            ResearchTab.DEEP_RESEARCH -> DeepResearchPanel(context = context)
            ResearchTab.MULTI_WIKI -> MultiLayerWikiExplorerPanel(context = context)
            ResearchTab.KNOWLEDGE_RAG -> KnowledgeRagPanel(context = context)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private enum class WikiLayerFilter(val label: String, val dbLayer: String?) {
    ALL("All Layers", null),
    SURFACE("Surface Wiki", "SURFACE_WIKI"),
    DEEP_WEB("Deep Web Wiki", "DEEP_WEB"),
    DARK_WEB("Dark Web & Tor Tech", "DARK_WEB")
}

@Composable
private fun MultiLayerWikiExplorerPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val pool = remember { ProviderPool(context) }
    val db = remember { ChatDatabase.getDatabase(context) }
    val vectorRepo = remember {
        com.ayra.ai.app.ai.rag.VectorKnowledgeRepository(db.vectorKnowledgeDao(), context)
    }

    val totalSources by vectorRepo.observeSourceCount().collectAsState(initial = 0)
    val totalChunks by vectorRepo.observeChunkCount().collectAsState(initial = 0)
    val layerCounts by vectorRepo.observeLayerChunkCounts().collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { vectorRepo.ensureSeeded() }
    }

    var query by remember { mutableStateOf("") }
    var layerFilter by remember { mutableStateOf(WikiLayerFilter.ALL) }
    var offlineVaultOnly by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AutonomousKnowledgeEngine.WikiLayerResult?>(null) }
    var vectorHits by remember { mutableStateOf<List<com.ayra.ai.app.ai.rag.VectorSearchHit>>(emptyList()) }
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
            .background(GlassSurfaceDark)
            .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Multi-Layer Wiki & Offline Vector Vault",
            color = NeonCyan,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = "Live clearnet Wiki (Wikipedia, Wikidata, DDG, OpenAlex, arXiv, PubMed, CVE, Tor Onionoo) auto-vectorized into a 256-dim Room Vector DB for instant offline Dark/Deep Web search.",
            color = TextSecondary,
            fontSize = 11.5.sp
        )

        // Room Vector DB Telemetry & Sync Controls Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CosmicBackground)
                .border(1.dp, NeonGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
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
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("vector_db_stats_text")
                    )
                    Text(
                        text = "Dark Web: $darkChunks • Deep Web: $deepChunks • Surface: $surfaceChunks (256d Cosine + BM25)",
                        color = TextSecondary,
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
            Text(text = note, color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }

        if (vectorHits.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CosmicBackground)
                    .border(1.dp, NeonPurple.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Semantic Vector DB Matches (${vectorHits.size})",
                    color = NeonPurple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                vectorHits.take(4).forEach { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassSurfaceDark)
                            .clickable { runCatching { uriHandler.openUri(hit.sourceUrl) } }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val originLabel = if (hit.originType == "LIVE_CACHED") "LIVE CACHED" else "CURATED"
                        Text(
                            text = "${hit.title} • ${hit.similarityPercent}% match [$originLabel]",
                            color = NeonCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${hit.layer} • ${hit.providerName} • Cosine ${String.format("%.2f", hit.cosineSimilarity)}",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                        Text(
                            text = hit.excerpt.take(220) + if (hit.excerpt.length > 220) "..." else "",
                            color = TextPrimary,
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
                            .background(CosmicBackground)
                            .border(1.dp, NeonPurple.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("AI Synthesized Brief", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Text(syn, color = TextPrimary, fontSize = 12.5.sp, lineHeight = 18.sp)
                    }
                }

                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.SURFACE) {
                    val surfaceText = listOf(res.surfaceWiki, res.wikidataEntities, res.ddgInstant)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (surfaceText.isNotBlank()) {
                        WikiSectionCard("Surface Wiki (Wikipedia • Wikidata • DDG)", surfaceText, NeonCyan)
                    }
                }
                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.DEEP_WEB) {
                    val deepText = listOf(res.deepAcademic, res.pubmedArticles, res.cveIntel)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (deepText.isNotBlank()) {
                        WikiSectionCard("Deep Web Wiki (OpenAlex • arXiv • CrossRef • PubMed • CVE)", deepText, NeonPurple)
                    }
                }
                if (layerFilter == WikiLayerFilter.ALL || layerFilter == WikiLayerFilter.DARK_WEB) {
                    val darkText = listOf(res.liveTorNetwork, res.darkWebTech)
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                    if (darkText.isNotBlank()) {
                        WikiSectionCard("Dark Web & Overlay Network Technical Wiki (Live Onionoo + Specs)", darkText, NeonGreen)
                    }
                }

                if (res.citations.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CosmicBackground)
                            .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Verified Clickable Sources", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                                    Text(cit.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("${cit.layer} • ${cit.url}", color = TextSecondary, fontSize = 10.5.sp, maxLines = 1)
                                }
                                Icon(Icons.Default.OpenInNew, contentDescription = "Open source", tint = NeonCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiSectionCard(title: String, body: String, accentColor: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CosmicBackground)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        Text(text = body, color = TextPrimary, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun DeepResearchPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val aiEngine = remember { UnifiedAiEngine(context) }
    val pool = remember { ProviderPool(context) }

    var query by remember { mutableStateOf("") }
    var webUrlInput by remember { mutableStateOf("") }
    var reportText by remember { mutableStateOf("") }
    var modelLabel by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isReadingWeb by remember { mutableStateOf(false) }
    val historyPrefs = remember {
        com.ayra.ai.app.security.EncryptedPreferences(
            context = context,
            prefsFileName = "ayra_research_history_enc",
            keyAlias = com.ayra.ai.app.security.AyraKeyManager.ALIAS_PREFS_KEK
        )
    }
    val researchHistory = remember {
        mutableStateListOf<String>().apply {
            var raw = historyPrefs.getString("queries", "")
            if (raw.isBlank()) {
                val legacy = context.getSharedPreferences("ayra_research_history", Context.MODE_PRIVATE)
                val legacyRaw = legacy.getString("queries", "").orEmpty()
                if (legacyRaw.isNotBlank()) {
                    raw = legacyRaw
                    historyPrefs.putString("queries", legacyRaw)
                }
                legacy.edit().clear().apply()
            }
            val saved = raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            addAll(saved)
        }
    }

    fun recordHistory(q: String) {
        researchHistory.remove(q)
        researchHistory.add(0, q)
        if (researchHistory.size > 8) researchHistory.removeAt(researchHistory.lastIndex)
        historyPrefs.putString("queries", researchHistory.joinToString("\n"))
    }

    fun shareFile(file: File, mime: String, title: String) {
        runCatching {
            val shareable = StorageHelper.prepareShareableFile(context, file)
            val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", shareable)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, title))
        }.onFailure {
            Toast.makeText(context, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Enter topic for live multi-source Deep Research") },
            modifier = Modifier.fillMaxWidth().testTag("research_query_input"),
            maxLines = 3
        )

        if (researchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                researchHistory.forEach { item ->
                    FilterChip(
                        selected = query == item,
                        onClick = { query = item },
                        label = { Text(item.take(28), fontSize = 11.sp) },
                        modifier = Modifier.testTag("research_history_chip")
                    )
                }
            }
        }

        Button(
            onClick = {
                val clean = query.trim()
                if (clean.isBlank() || isRunning) return@Button
                recordHistory(clean)
                isRunning = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        aiEngine.generateUnifiedResponse(
                            prompt = "deep research $clean",
                            personaMode = "PROFESSIONAL",
                            modelTier = "best"
                        )
                    }
                    reportText = res.text
                    modelLabel = res.modelUsed
                    isRunning = false
                }
            },
            enabled = query.isNotBlank() && !isRunning,
            modifier = Modifier.fillMaxWidth().testTag("research_run_button")
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(if (isRunning) "Researching Sources..." else "Run Deep Research (Web + Wikipedia + CrossRef)")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = webUrlInput,
                onValueChange = { webUrlInput = it },
                label = { Text("HTTPS Webpage Reader URL") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("research_url_input")
            )
            OutlinedButton(
                onClick = {
                    val url = webUrlInput.trim()
                    if (url.isBlank() || isReadingWeb) return@OutlinedButton
                    isReadingWeb = true
                    scope.launch {
                        val fetchRes = withContext(Dispatchers.IO) {
                            AutonomousKnowledgeEngine.fetchReadableWebpage(url)
                        }
                        reportText = fetchRes.fold(
                            onSuccess = { (pageTitle, pageBody) ->
                                val summary = withContext(Dispatchers.IO) {
                                    runCatching {
                                        pool.executeSync(
                                            ChatRequest(
                                                prompt = "Summarize key facts and citations from '$pageTitle':\n\n$pageBody",
                                                persona = "PROFESSIONAL",
                                                mode = ProviderPoolMode.FAST
                                            )
                                        ).text
                                    }.getOrNull()
                                }
                                if (!summary.isNullOrBlank()) {
                                    "# $pageTitle\n\n## AI Summary\n$summary\n\n---\n\n$pageBody"
                                } else {
                                    "# $pageTitle\n\n$pageBody"
                                }
                            },
                            onFailure = { err ->
                                "Webpage read blocked or failed: ${err.localizedMessage ?: "Unknown error"}"
                            }
                        )
                        modelLabel = "SSRF-Safe HTTPS Reader"
                        isReadingWeb = false
                    }
                },
                enabled = webUrlInput.isNotBlank() && !isReadingWeb,
                modifier = Modifier.testTag("research_fetch_url_button")
            ) {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isReadingWeb) "Reading" else "Read URL", fontSize = 11.5.sp)
            }
        }

        if (reportText.isNotBlank()) {
            modelLabel?.let {
                Text(text = "Source Engine: $it", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val pdf = withContext(Dispatchers.IO) {
                                StorageHelper.createRealPdf(context, query.trim().ifBlank { "Research" }, reportText)
                            }
                            if (pdf != null) shareFile(pdf, "application/pdf", "Share PDF Report")
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("export_pdf_button")
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PDF", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val md = withContext(Dispatchers.IO) {
                                StorageHelper.exportMarkdownDocument(context, query.trim().ifBlank { "Research" }, reportText)
                            }
                            shareFile(md, "text/markdown", "Share Markdown (.md)")
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("export_md_button")
                ) {
                    Text(".MD", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val doc = withContext(Dispatchers.IO) {
                                StorageHelper.exportWordDocDocument(context, query.trim().ifBlank { "Research" }, reportText)
                            }
                            shareFile(doc, "application/msword", "Share Word (.doc)")
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("export_doc_button")
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(".DOC", fontSize = 12.sp)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmicBackground)
                    .border(1.dp, NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = reportText,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun KnowledgeRagPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val db = remember { ChatDatabase.getDatabase(context) }
    val ragEngine = remember { DocumentRagEngine(db.knowledgeDocumentDao()) }
    val docs by ragEngine.observeDocuments().collectAsState(initial = emptyList())
    val pool = remember { ProviderPool(context) }

    var searchQuery by remember { mutableStateOf("") }
    var lastExtractedPreview by remember { mutableStateOf<String?>(null) }
    var searchHits by remember { mutableStateOf<List<RagSearchHit>>(emptyList()) }
    var ragAnswer by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isWorking = true
            scope.launch {
                val indexedPair = withContext(Dispatchers.IO) {
                    val name = StorageHelper.getFileName(context, uri) ?: "Document"
                    val extracted = StorageHelper.extractTextFromDocument(context, uri, name)
                    if (extracted.isNotBlank()) {
                        ragEngine.ingestDocument(
                            title = name,
                            mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                            rawText = extracted,
                            sourceUri = uri.toString()
                        )
                        name to extracted.take(700)
                    } else null
                }
                isWorking = false
                if (indexedPair != null) {
                    lastExtractedPreview = "${indexedPair.first}:\n${indexedPair.second}"
                    Toast.makeText(context, "Indexed '${indexedPair.first}' into Knowledge Base", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurfaceDark)
            .border(1.dp, NeonPurple.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Persistent Documents (${docs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonPurple
                )
                Text(
                    text = "PDF, DOCX, XLSX, PPTX, CSV & text stay indexed across restarts.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Button(
                onClick = {
                    docPicker.launch(
                        arrayOf(
                            "application/pdf",
                            "text/*",
                            "application/json",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        )
                    )
                },
                enabled = !isWorking,
                modifier = Modifier.testTag("rag_upload_doc_button")
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add File", fontSize = 12.sp)
            }
        }

        lastExtractedPreview?.let { preview ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmicBackground)
                    .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(text = preview, color = TextPrimary, fontSize = 11.5.sp)
            }
        }

        docs.forEach { doc ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmicBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = doc.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(text = "${doc.chunkCount} chunks • ${doc.charCount} chars", color = TextSecondary, fontSize = 11.sp)
                }
                IconButton(
                    onClick = { scope.launch { ragEngine.deleteDocument(doc.id) } },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${doc.title}", tint = NeonRose)
                }
            }
        }

        if (docs.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Ask or search across all indexed documents") },
                modifier = Modifier.fillMaxWidth().testTag("rag_query_input"),
                singleLine = true
            )

            Button(
                onClick = {
                    val q = searchQuery.trim()
                    if (q.isBlank() || isWorking) return@Button
                    isWorking = true
                    scope.launch {
                        val (ctxText, hits) = ragEngine.buildCitedContext(q, topK = 4)
                        searchHits = hits
                        ragAnswer = if (hits.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    pool.executeSync(
                                        ChatRequest(
                                            prompt = q,
                                            persona = "PROFESSIONAL",
                                            mode = ProviderPoolMode.BEST,
                                            documentText = ctxText,
                                            systemPromptOverride = "Answer ONLY from the provided knowledge base chunks and preserve [Doc: ...] citation tags accurately."
                                        )
                                    ).text
                                }.getOrNull()
                            }
                        } else null
                        isWorking = false
                    }
                },
                enabled = searchQuery.isNotBlank() && !isWorking,
                modifier = Modifier.fillMaxWidth().testTag("rag_search_button")
            ) {
                Text(if (isWorking) "Searching..." else "Retrieve Citations & Answer")
            }

            ragAnswer?.let { ans ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CosmicBackground)
                        .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(text = ans, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }

            searchHits.forEach { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CosmicBackground)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${hit.citationTag} • Score ${String.format("%.2f", hit.score)}",
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = hit.excerpt.take(360),
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}
