package com.ayra.ai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.data.ChatDao
import com.ayra.ai.app.ui.theme.CosmicBackground
import com.ayra.ai.app.ui.theme.CosmicSurface
import com.ayra.ai.app.ui.theme.NeonCyan
import com.ayra.ai.app.ui.theme.TextSecondary
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseUser

enum class WorkspaceTab(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    CHAT("Chat", Icons.Default.ChatBubble, "nav_tab_chat"),
    STUDIOS("Studios", Icons.Default.AutoAwesome, "nav_tab_studios"),
    RESEARCH("Research", Icons.Default.TravelExplore, "nav_tab_research"),
    ARENA("Arena", Icons.AutoMirrored.Filled.CompareArrows, "nav_tab_arena"),
    TOOLS("Tools", Icons.Default.Build, "nav_tab_tools")
}

@Composable
fun AyraWorkspaceHost(
    chatDao: ChatDao,
    viewModel: ChatViewModel,
    currentUser: FirebaseUser?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by rememberSaveable { mutableStateOf(WorkspaceTab.CHAT) }
    var showSecurityCenter by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showSecurityCenter || activeTab != WorkspaceTab.CHAT) {
        if (showSecurityCenter) {
            showSecurityCenter = false
        } else {
            activeTab = WorkspaceTab.CHAT
        }
    }

    if (showSecurityCenter) {
        SecurityCenterScreen(
            onBack = { showSecurityCenter = false },
            onVaultWiped = {
                showSecurityCenter = false
                onSignOut()
            },
            modifier = modifier.fillMaxSize()
        )
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        val activeContent: @Composable (Modifier) -> Unit = { contentModifier ->
            when (activeTab) {
                WorkspaceTab.CHAT -> ChatScreen(
                    chatDao = chatDao,
                    viewModel = viewModel,
                    currentUser = currentUser,
                    onSignOut = onSignOut,
                    onOpenSecurityCenter = { showSecurityCenter = true },
                    embeddedInWorkspace = true,
                    modifier = contentModifier
                )
                WorkspaceTab.STUDIOS -> StudiosScreen(modifier = contentModifier)
                WorkspaceTab.RESEARCH -> ResearchDocsScreen(modifier = contentModifier)
                WorkspaceTab.ARENA -> ModelsArenaScreen(modifier = contentModifier)
                WorkspaceTab.TOOLS -> DiagnosticsToolsScreen(
                    onRunPromptInChat = { prompt ->
                        viewModel.updateInputText(prompt)
                        activeTab = WorkspaceTab.CHAT
                    },
                    onVaultWiped = onSignOut,
                    modifier = contentModifier
                )
            }
        }

        if (useRail) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        WorkspaceTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        tint = if (activeTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                label = { Text(tab.label, fontSize = 11.sp) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        activeContent(Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.navigationBars,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        WorkspaceTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = activeTab == tab,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label,
                                        tint = if (activeTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                activeContent(Modifier.fillMaxSize().padding(innerPadding))
            }
        }
    }
}
