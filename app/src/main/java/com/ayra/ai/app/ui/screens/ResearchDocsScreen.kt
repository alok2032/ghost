package com.ayra.ai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ui.screens.research.DeepResearchPanel
import com.ayra.ai.app.ui.screens.research.KnowledgeRagPanel
import com.ayra.ai.app.ui.screens.research.MultiLayerWikiExplorerPanel

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
