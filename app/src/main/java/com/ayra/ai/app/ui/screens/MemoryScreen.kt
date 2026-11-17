package com.ayra.ai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.ayra.ai.app.ai.memory.LongTermMemoryManager
import com.ayra.ai.app.ai.memory.MemorySaveResult
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.LongTermMemoryEntity
import com.ayra.ai.app.ui.theme.LocalAyraUiConfig
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiConfig = LocalAyraUiConfig.current

    var memoryEnabled by remember { mutableStateOf(AyraPreferences.isMemoryEnabled(context)) }
    val memories by remember { LongTermMemoryManager.observeAllMemories(context) }.collectAsStateWithLifecycle(initialValue = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var newMemoryText by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("PREFERENCE") }
    var editingEntity by remember { mutableStateOf<LongTermMemoryEntity?>(null) }
    var editDraftText by remember { mutableStateOf("") }
    var editDraftCategory by remember { mutableStateOf("GENERAL") }
    var editDraftPinned by remember { mutableStateOf(false) }
    var confirmWipeAll by remember { mutableStateOf(false) }

    val filtered = remember(memories, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        memories.filter { item ->
            val matchesCat = selectedCategory == "ALL" || item.category.equals(selectedCategory, ignoreCase = true)
            val matchesQ = q.isBlank() || item.content.lowercase().contains(q) || item.category.lowercase().contains(q)
            matchesCat && matchesQ
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("memory_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = uiConfig.accentColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Encrypted Long-Term Memory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Opt-in Room vault (${memories.size} saved) • Secrets & PII auto-blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Opt-In Memory Recall", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (memoryEnabled) "Ghost AI uses top relevant memories during chat (requires your confirmation to save new facts)."
                        else "Off by default for maximum privacy. Nothing is recalled or stored.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        AyraPreferences.setMemoryEnabled(context, it)
                    },
                    modifier = Modifier.testTag("memory_opt_in_switch")
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search saved memories...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("memory_search_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (listOf("ALL") + LongTermMemoryManager.VALID_CATEGORIES).forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, fontSize = 11.sp) }
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add Memory Manually", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LongTermMemoryManager.VALID_CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = newCategory == cat,
                            onClick = { newCategory = cat },
                            label = { Text(cat, fontSize = 10.sp) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMemoryText,
                        onValueChange = { newMemoryText = it },
                        placeholder = { Text("e.g., Prefer Kotlin coroutines & Bengali replies") },
                        modifier = Modifier.weight(1f).testTag("memory_add_input"),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val res = LongTermMemoryManager.trySaveMemory(
                                    context = context,
                                    category = newCategory,
                                    rawContent = newMemoryText,
                                    bypassOptInCheck = true
                                )
                                when (res) {
                                    is MemorySaveResult.Saved -> {
                                        if (!memoryEnabled) {
                                            memoryEnabled = true
                                            AyraPreferences.setMemoryEnabled(context, true)
                                        }
                                        newMemoryText = ""
                                        Toast.makeText(context, "Memory saved to encrypted vault", Toast.LENGTH_SHORT).show()
                                    }
                                    is MemorySaveResult.Rejected -> {
                                        Toast.makeText(context, res.reason, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("memory_add_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Save Memory", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (memories.isEmpty()) "No memories stored yet." else "No memories match your filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                            .border(
                                width = if (item.isPinned) 1.2.dp else 0.8.dp,
                                color = if (item.isPinned) uiConfig.accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = (if (item.isPinned) "📌 " else "") + item.category,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = uiConfig.accentColor
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(item.content, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    LongTermMemoryManager.updateMemory(
                                        context, item, item.category, item.content, !item.isPinned
                                    )
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = if (item.isPinned) "Unpin" else "Pin",
                                tint = if (item.isPinned) uiConfig.accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                editingEntity = item
                                editDraftText = item.content
                                editDraftCategory = item.category
                                editDraftPinned = item.isPinned
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Memory", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                scope.launch { LongTermMemoryManager.deleteMemory(context, item.id) }
                            },
                            modifier = Modifier.size(48.dp).testTag("memory_delete_${item.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Memory", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (memories.isNotEmpty()) {
            OutlinedButton(
                onClick = { confirmWipeAll = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("memory_wipe_all_button")
            ) {
                Text("Delete All Long-Term Memories")
            }
        }
    }

    editingEntity?.let { target ->
        AlertDialog(
            onDismissRequest = { editingEntity = null },
            title = { Text("Edit Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editDraftText,
                        onValueChange = { editDraftText = it },
                        label = { Text("Memory Content") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LongTermMemoryManager.VALID_CATEGORIES.forEach { cat ->
                            FilterChip(
                                selected = editDraftCategory == cat,
                                onClick = { editDraftCategory = cat },
                                label = { Text(cat, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val res = LongTermMemoryManager.updateMemory(
                            context, target, editDraftCategory, editDraftText, editDraftPinned
                        )
                        if (res is MemorySaveResult.Rejected) {
                            Toast.makeText(context, res.reason, Toast.LENGTH_LONG).show()
                        } else {
                            editingEntity = null
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingEntity = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmWipeAll) {
        AlertDialog(
            onDismissRequest = { confirmWipeAll = false },
            title = { Text("Delete All Memories?") },
            text = { Text("This permanently deletes all saved user memories from the encrypted Room database.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmWipeAll = false
                        scope.launch { LongTermMemoryManager.deleteAllMemories(context) }
                    }
                ) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeAll = false }) { Text("Cancel") }
            }
        )
    }
}
