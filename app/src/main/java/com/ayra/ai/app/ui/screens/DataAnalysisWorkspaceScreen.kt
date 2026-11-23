package com.ayra.ai.app.ui.screens

import android.widget.Toast
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ai.DataAnalysisEngine
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DataAnalysisWorkspaceScreen(
    onBack: () -> Unit,
    onSendInsightPromptToChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current

    var fileName by remember { mutableStateOf("sample_metrics.csv") }
    var rawCsv by remember {
        mutableStateOf(
            "month,revenue,expenses,users\nJan,12500,7200,410\nFeb,14900,7800,520\nMar,18200,8400,690\nApr,22400,9100,880\nMay,26800,9900,1140"
        )
    }
    var aiInsights by remember { mutableStateOf<String?>(null) }
    var isAnalyzingAi by remember { mutableStateOf(false) }

    val summary = remember(rawCsv, fileName) {
        DataAnalysisEngine.analyzeTabularText(rawCsv, fileName)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val pickedName = StorageHelper.getFileName(context, uri) ?: "imported.csv"
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                }.getOrDefault("")
                withContext(Dispatchers.Main) {
                    if (content.isNotBlank()) {
                        fileName = pickedName
                        rawCsv = content.take(40_000)
                        aiInsights = null
                    } else {
                        Toast.makeText(context, "Could not read tabular file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("data_analysis_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Data & CSV Analysis Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Deterministic statistical profiling, distribution visualization & live AI insights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { filePicker.launch(arrayOf("text/csv", "text/tab-separated-values", "text/plain")) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("import_csv_button")
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import CSV / TSV")
            }
            OutlinedButton(
                onClick = {
                    val profile = DataAnalysisEngine.summarizeTabularText(rawCsv, fileName)
                    onSendInsightPromptToChat("Analyze this dataset thoroughly and suggest actionable charts & anomalies:\n\n$profile")
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text("Discuss in Chat")
            }
        }

        OutlinedTextField(
            value = rawCsv,
            onValueChange = { rawCsv = it },
            label = { Text("CSV / TSV Data ($fileName)") },
            minLines = 5,
            maxLines = 9,
            modifier = Modifier.fillMaxWidth().testTag("csv_input_field")
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = uiConfig.accentColor, modifier = Modifier.size(18.dp))
                    Text(
                        "Summary: ${summary.rowCount} rows × ${summary.columnCount} columns",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = uiConfig.accentColor
                    )
                }

                summary.columns.forEach { col ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(0.8.dp, uiConfig.accentColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text("${col.name} (${if (col.isNumeric) "Numeric" else "Text"} • n=${col.nonEmptyCount}, missing=${col.missingCount})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (col.isNumeric) {
                            Text(
                                String.format(Locale.US, "min=%.2f • mean=%.2f • median=%.2f • max=%.2f • std=%.2f", col.min ?: 0.0, col.mean ?: 0.0, col.median ?: 0.0, col.max ?: 0.0, col.stdDev ?: 0.0),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (col.topCategories.isNotEmpty()) {
                            Text("Top values: ${col.topCategories.joinToString(", ") { "${it.first} (${it.second})" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        val numericCols = remember(summary) { summary.columns.filter { it.isNumeric && (it.mean ?: 0.0) > 0.0 } }
        if (numericCols.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Numeric Column Means Visualization", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val maxMean = (numericCols.maxOfOrNull { it.mean ?: 0.0 } ?: 1.0).coerceAtLeast(1e-6)
                    val barColor = uiConfig.accentColor
                    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        val barCount = numericCols.size.coerceAtLeast(1)
                        val slotWidth = size.width / barCount
                        val barWidth = (slotWidth * 0.55f).coerceAtLeast(12f)
                        numericCols.forEachIndexed { idx, col ->
                            val ratio = ((col.mean ?: 0.0) / maxMean).toFloat().coerceIn(0.04f, 1f)
                            val barHeight = size.height * ratio
                            val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                            drawRect(
                                color = barColor,
                                topLeft = Offset(left, size.height - barHeight),
                                size = Size(barWidth, barHeight)
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        numericCols.forEach { col ->
                            Text(col.name.take(10), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                isAnalyzingAi = true
                scope.launch {
                    val profile = DataAnalysisEngine.summarizeTabularText(rawCsv, fileName)
                    val res = runCatching {
                        ProviderPool(context).executeSync(
                            ChatRequest(
                                prompt = "Provide executive statistical insights, trend interpretation, outlier warnings, and recommended visualizations for this dataset profile:\n\n$profile",
                                mode = ProviderPoolMode.FAST
                            )
                        ).text
                    }
                    isAnalyzingAi = false
                    aiInsights = res.getOrElse { "AI insight unavailable: ${it.localizedMessage}" }
                }
            },
            enabled = !isAnalyzingAi && rawCsv.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ask_ai_data_insights_button")
        ) {
            if (isAnalyzingAi) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Generate Live AI Data Insights")
            }
        }

        aiInsights?.let { insights ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(insights, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
            }
        }
    }
}
