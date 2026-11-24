package com.ayra.ai.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDao
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.ui.components.OnboardingOverlay
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import com.ayra.ai.app.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class AyraDestination(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val shortLabel: String
) {
    CHAT("Ayra Chat", "Prompt-Driven Photo, Video, Code & AI", Icons.AutoMirrored.Filled.Chat, "Chat"),
    RESEARCH_WIKI("Multi-Layer Wiki & RAG", "Surface, Deep Academic & Dark Web Tech", Icons.Default.Public, "Wiki"),
    PROJECTS_ASSISTANTS("Projects & AI Assistants", "Isolated Contexts & Custom Personas", Icons.Default.FolderSpecial, "Projects"),
    MEMORY("Long-Term Memory", "Opt-In Encrypted Recall & Facts", Icons.Default.Psychology, "Memory"),
    MODELS_ARENA("Models Ecosystem & Arena", "Registry, Health & Live Benchmark", Icons.Default.Hub, "Arena"),
    TOOLS_HUB("Tools, Keys & Plugins", "API Keys, Terminal, Files & GitHub", Icons.Default.Build, "Tools"),
    SETTINGS_APPEARANCE("Appearance & Personalization", "Themes, Memory, Thinking & Health", Icons.Default.Palette, "Theme"),
    AI_SETTINGS("AI Control & Keys", "Auto-Active Multi-AI, Live Search & Optional Keys", Icons.Default.AutoAwesome, "AI"),
    SECURITY_CENTER("Zero-Trust Security Vault", "Biometrics, E2EE, Audit & Wipe", Icons.Default.Security, "Security")
}

@Composable
fun AyraWorkspaceHost(
    chatDao: ChatDao,
    viewModel: ChatViewModel,
    currentUser: FirebaseUser?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var currentDest by rememberSaveable { mutableStateOf(AyraDestination.CHAT) }
    var drawerSearch by remember { mutableStateOf("") }
    var showOnboarding by rememberSaveable { mutableStateOf(!AyraPreferences.isOnboardingCompleted(context)) }

    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val activeProjectId by AyraPreferences.activeProjectIdFlow.collectAsStateWithLifecycle()
    val activeAssistantId by AyraPreferences.activeAssistantIdFlow.collectAsStateWithLifecycle()

    val workspaceDao = remember { ChatDatabase.getDatabase(context).ayraWorkspaceDao() }
    val projectsFlow = remember(workspaceDao) {
        workspaceDao.observeProjects().catch { err ->
            if (err !is IllegalStateException) throw err
            emit(emptyList())
        }
    }
    val assistantsFlow = remember(workspaceDao) {
        workspaceDao.observeAssistants().catch { err ->
            if (err !is IllegalStateException) throw err
            emit(emptyList())
        }
    }
    val projects by projectsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val assistants by assistantsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeProject = remember(projects, activeProjectId) { projects.firstOrNull { it.id == activeProjectId } }
    val activeAssistant = remember(assistants, activeAssistantId) { assistants.firstOrNull { it.id == activeAssistantId } }

    BackHandler(enabled = drawerState.isOpen || currentDest != AyraDestination.CHAT) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            currentDest = AyraDestination.CHAT
        }
    }

    fun navigateTo(dest: AyraDestination) {
        currentDest = dest
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentDest == AyraDestination.CHAT || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(310.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Column {
                            Text(
                                text = "Ayra AI • By Alok",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = uiConfig.accentColor
                            )
                            Text(
                                text = "Multi-Model Intelligence Platform",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = uiConfig.accentColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable {
                                    viewModel.createNewSession()
                                    navigateTo(AyraDestination.CHAT)
                                }
                                .testTag("drawer_new_chat_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.AddComment, contentDescription = "New Chat", tint = uiConfig.accentColor, modifier = Modifier.size(16.dp))
                                Text("New", color = uiConfig.accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (activeProject != null || activeAssistant != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(uiConfig.accentColor.copy(alpha = 0.10f))
                                .clickable { navigateTo(AyraDestination.PROJECTS_ASSISTANTS) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            activeProject?.let {
                                Text("Project: ${it.name}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = uiConfig.accentColor)
                            }
                            activeAssistant?.let {
                                Text("Assistant: ${it.iconEmoji} ${it.name}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    AyraDestination.entries.forEach { dest ->
                        NavigationDrawerItem(
                            selected = currentDest == dest,
                            onClick = { navigateTo(dest) },
                            icon = { Icon(dest.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = {
                                Column {
                                    Text(dest.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(dest.subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = uiConfig.accentColor.copy(alpha = 0.16f),
                                selectedIconColor = uiConfig.accentColor,
                                selectedTextColor = uiConfig.accentColor
                            ),
                            modifier = Modifier.testTag("drawer_nav_${dest.name.lowercase()}")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                    Text(
                        text = "Recent Conversations (${sessions.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    OutlinedTextField(
                        value = drawerSearch,
                        onValueChange = { drawerSearch = it },
                        placeholder = { Text("Search chats...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )

                    val filteredSessions = remember(sessions, drawerSearch) {
                        val q = drawerSearch.trim().lowercase()
                        if (q.isBlank()) sessions.take(15)
                        else sessions.filter {
                            it.title.lowercase().contains(q) || it.lastMessagePreview.lowercase().contains(q)
                        }.take(20)
                    }

                    filteredSessions.forEach { sess ->
                        val selected = sess.id == activeSessionId && currentDest == AyraDestination.CHAT
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) uiConfig.accentColor.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .clickable {
                                    viewModel.selectSession(sess.id)
                                    navigateTo(AyraDestination.CHAT)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = (if (sess.isPinned) "📌 " else "") + sess.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (sess.lastMessagePreview.isNotBlank()) {
                                    Text(
                                        text = sess.lastMessagePreview,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 840.dp
            Row(modifier = Modifier.fillMaxSize()) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("workspace_navigation_rail")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    viewModel.createNewSession()
                                    navigateTo(AyraDestination.CHAT)
                                },
                                containerColor = uiConfig.accentColor.copy(alpha = 0.18f),
                                contentColor = uiConfig.accentColor,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("rail_new_chat_button")
                            ) {
                                Icon(Icons.Default.AddComment, contentDescription = "New Chat")
                            }
                            HorizontalDivider(modifier = Modifier.width(40.dp).padding(vertical = 6.dp))
                            AyraDestination.entries.forEach { dest ->
                                NavigationRailItem(
                                    selected = currentDest == dest,
                                    onClick = { navigateTo(dest) },
                                    icon = { Icon(dest.icon, contentDescription = dest.title) },
                                    label = {
                                        Text(
                                            text = dest.shortLabel,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = uiConfig.accentColor,
                                        selectedTextColor = uiConfig.accentColor,
                                        indicatorColor = uiConfig.accentColor.copy(alpha = 0.16f)
                                    ),
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("rail_nav_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentDest) {
                        AyraDestination.CHAT -> ChatScreen(
                            chatDao = chatDao,
                            viewModel = viewModel,
                            currentUser = currentUser,
                            onSignOut = onSignOut,
                            onOpenSecurityCenter = { currentDest = AyraDestination.SECURITY_CENTER },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            embeddedInWorkspace = false,
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.RESEARCH_WIKI -> ResearchDocsScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.PROJECTS_ASSISTANTS -> ProjectsAndAssistantsScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            onOpenProjectChat = { _, _ ->
                                viewModel.createNewSession("Workspace Chat")
                                currentDest = AyraDestination.CHAT
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.MEMORY -> MemoryScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.MODELS_ARENA -> ModelsArenaScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.TOOLS_HUB -> DiagnosticsToolsScreen(
                            onRunPromptInChat = { prompt ->
                                viewModel.updateInputText(prompt)
                                currentDest = AyraDestination.CHAT
                            },
                            onVaultWiped = {
                                currentDest = AyraDestination.CHAT
                                onSignOut()
                            },
                            onBack = { currentDest = AyraDestination.CHAT },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.SETTINGS_APPEARANCE -> SettingsAppearanceScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            onOpenModels = { currentDest = AyraDestination.MODELS_ARENA },
                            onOpenTools = { currentDest = AyraDestination.TOOLS_HUB },
                            onOpenSecurityCenter = { currentDest = AyraDestination.SECURITY_CENTER },
                            onOpenMemory = { currentDest = AyraDestination.MEMORY },
                            onOpenAiSettings = { currentDest = AyraDestination.AI_SETTINGS },
                            onPersonaChanged = { style -> viewModel.setPersona(style) },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.AI_SETTINGS -> AiUnifiedSettingsHubScreen(
                            onBack = { currentDest = AyraDestination.SETTINGS_APPEARANCE },
                            onOpenModels = { currentDest = AyraDestination.MODELS_ARENA },
                            onOpenSecurityCenter = { currentDest = AyraDestination.SECURITY_CENTER },
                            modifier = Modifier.fillMaxSize()
                        )
                        AyraDestination.SECURITY_CENTER -> SecurityCenterScreen(
                            onBack = { currentDest = AyraDestination.CHAT },
                            onVaultWiped = {
                                currentDest = AyraDestination.CHAT
                                onSignOut()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            if (showOnboarding) {
                OnboardingOverlay(
                    onDismissed = { showOnboarding = false }
                )
            }
        }
    }
}
