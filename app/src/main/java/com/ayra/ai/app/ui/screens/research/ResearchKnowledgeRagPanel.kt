package com.ayra.ai.app.ui.screens.research

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.ai.rag.DocumentRagEngine
import com.ayra.ai.app.ai.rag.RagSearchHit
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun KnowledgeRagPanel(context: Context) {
    val scope = rememberCoroutineScope()
    val db = remember { ChatDatabase.getDatabase(context) }
    val ragEngine = remember { DocumentRagEngine(db.knowledgeDocumentDao()) }
    val docs by ragEngine.observeDocuments().collectAsStateWithLifecycle(initialValue = emptyList())
    val pool = remember { ProviderPool(context) }

    var searchQuery by remember { mutableStateOf("") }
    var lastExtractedPreview by remember { mutableStateOf<String?>(null) }
    var searchHits by remember { mutableStateOf<List<RagSearchHit>>(emptyList()) }
    var ragAnswer by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var showExternalConsentDialog by remember { mutableStateOf(false) }

    fun executeRagQuery(allowCloudSynthesis: Boolean) {
        val q = searchQuery.trim()
        if (q.isBlank() || isWorking) return
        isWorking = true
        scope.launch {
            val (ctxText, hits) = ragEngine.buildCitedContext(q, topK = 4)
            searchHits = hits
            ragAnswer = if (hits.isNotEmpty() && allowCloudSynthesis && !AyraPreferences.isLocalOnlyAiModeEnabled(context)) {
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
            } else if (hits.isNotEmpty()) {
                "Local citation search found ${hits.size} excerpt(s) without sending document text to external cloud models."
            } else null
            isWorking = false
        }
    }

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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
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
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = "PDF, DOCX, XLSX, PPTX, CSV & text stay indexed across restarts.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(text = preview, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp)
            }
        }

        docs.forEach { doc ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = doc.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(text = "${doc.chunkCount} chunks • ${doc.charCount} chars", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                IconButton(
                    onClick = { scope.launch { ragEngine.deleteDocument(doc.id) } },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${doc.title}", tint = MaterialTheme.colorScheme.error)
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
                    if (AyraPreferences.isLocalOnlyAiModeEnabled(context) || AyraPreferences.hasRagExternalSendConsent(context)) {
                        executeRagQuery(allowCloudSynthesis = !AyraPreferences.isLocalOnlyAiModeEnabled(context))
                    } else {
                        showExternalConsentDialog = true
                    }
                },
                enabled = searchQuery.isNotBlank() && !isWorking,
                modifier = Modifier.fillMaxWidth().testTag("rag_search_button")
            ) {
                Text(if (isWorking) "Searching..." else "Retrieve Citations & Answer")
            }

            if (showExternalConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showExternalConsentDialog = false },
                    title = { Text("Send Document Excerpts to AI?", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("To synthesize an AI answer, matching excerpts from your indexed documents are sent to your configured AI provider. You can also run Local Search Only without any cloud upload.")
                    },
                    confirmButton = {
                        Button(onClick = {
                            AyraPreferences.setRagExternalSendConsent(context, true)
                            showExternalConsentDialog = false
                            executeRagQuery(allowCloudSynthesis = true)
                        }) { Text("Allow AI Synthesis") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showExternalConsentDialog = false
                            executeRagQuery(allowCloudSynthesis = false)
                        }) { Text("Local Search Only") }
                    }
                )
            }

            ragAnswer?.let { ans ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(text = ans, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }

            searchHits.forEach { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${hit.citationTag} • Score ${String.format(Locale.US, "%.2f", hit.score)}",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = hit.excerpt.take(360),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}
