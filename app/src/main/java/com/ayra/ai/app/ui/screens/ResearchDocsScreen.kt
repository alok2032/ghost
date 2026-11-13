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

private enum class ResearchTab(val label: String) {
    DEEP_RESEARCH("Deep Research"),
    KNOWLEDGE_RAG("Knowledge Base (RAG)")
}

@Composable
fun ResearchDocsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(ResearchTab.DEEP_RESEARCH) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CosmicBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Research & Document Knowledge Hub",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "Multi-source web/academic research with PDF, Markdown (.md), & Word (.doc) export, plus persistent Room-indexed RAG.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            ResearchTab.KNOWLEDGE_RAG -> KnowledgeRagPanel(context = context)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DeepResearchPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val aiEngine = remember { UnifiedAiEngine(context) }

    var query by remember { mutableStateOf("") }
    var reportText by remember { mutableStateOf("") }
    var modelLabel by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    fun shareFile(file: File, mime: String, title: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "com.ayra.ai.app.fileprovider", file)
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

        Button(
            onClick = {
                val clean = query.trim()
                if (clean.isBlank() || isRunning) return@Button
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
            Text(if (isRunning) "Researching Sources..." else "Run Deep Research")
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
    var searchHits by remember { mutableStateOf<List<RagSearchHit>>(emptyList()) }
    var ragAnswer by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isWorking = true
            scope.launch {
                val indexedName = withContext(Dispatchers.IO) {
                    val name = StorageHelper.getFileName(context, uri) ?: "Document"
                    val extracted = StorageHelper.extractTextFromDocument(context, uri, name)
                    if (extracted.isNotBlank()) {
                        ragEngine.ingestDocument(
                            title = name,
                            mimeType = context.contentResolver.getType(uri) ?: "text/plain",
                            rawText = extracted,
                            sourceUri = uri.toString()
                        )
                        name
                    } else null
                }
                isWorking = false
                if (indexedName != null) {
                    Toast.makeText(context, "Indexed '$indexedName' into Knowledge Base", Toast.LENGTH_SHORT).show()
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
                    text = "PDFs & text files stay indexed across restarts for cited Q&A.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            Button(
                onClick = { docPicker.launch(arrayOf("application/pdf", "text/*", "application/json")) },
                enabled = !isWorking,
                modifier = Modifier.testTag("rag_upload_doc_button")
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add File", fontSize = 12.sp)
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
