package com.ayra.ai.app.ui.screens.research

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.content.FileProvider
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.AyraKeyManager
import com.ayra.ai.app.security.EncryptedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DeepResearchPanel(context: Context) {
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
        EncryptedPreferences(
            context = context,
            prefsFileName = "ayra_research_history_enc",
            keyAlias = AyraKeyManager.ALIAS_PREFS_KEK
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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
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
                Text(text = "Source Engine: $it", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = reportText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}
