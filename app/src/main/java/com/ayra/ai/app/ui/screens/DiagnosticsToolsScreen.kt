package com.ayra.ai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.ui.components.CyberTerminalCard
import com.ayra.ai.app.ui.components.GitHubPluginHubSection
import com.ayra.ai.app.ui.components.ProviderKeysSection
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.TextPrimary
import com.ayra.ai.app.ui.theme.TextSecondary

private enum class ToolsSubTab(val label: String) {
    KEYS("API Keys Vault"),
    TERMINAL("Cyber Terminal"),
    PLUGINS("GitHub Plugins")
}

@Composable
fun DiagnosticsToolsScreen(
    onRunPromptInChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(ToolsSubTab.KEYS) }

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
            text = "Tools, Security Vault & Plugins",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "Manage encrypted Android Keystore API keys, run live network/CVE terminal probes, or install GitHub Skill & OpenAPI plugins.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeTab == ToolsSubTab.KEYS,
                onClick = { activeTab = ToolsSubTab.KEYS },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Keys", fontSize = 12.sp) },
                modifier = Modifier.testTag("tools_subtab_keys")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.TERMINAL,
                onClick = { activeTab = ToolsSubTab.TERMINAL },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Terminal", fontSize = 12.sp) },
                modifier = Modifier.testTag("tools_subtab_terminal")
            )
            FilterChip(
                selected = activeTab == ToolsSubTab.PLUGINS,
                onClick = { activeTab = ToolsSubTab.PLUGINS },
                leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Plugins", fontSize = 12.sp) },
                modifier = Modifier.testTag("tools_subtab_plugins")
            )
        }

        when (activeTab) {
            ToolsSubTab.KEYS -> {
                ProviderKeysSection(context = context)
            }
            ToolsSubTab.TERMINAL -> {
                CyberTerminalCard(
                    onClose = { activeTab = ToolsSubTab.KEYS }
                )
            }
            ToolsSubTab.PLUGINS -> {
                GitHubPluginHubSection(
                    context = context,
                    onUsePluginPrompt = { pluginPrompt ->
                        onRunPromptInChat(pluginPrompt)
                    },
                    onQuickFeedback = { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
