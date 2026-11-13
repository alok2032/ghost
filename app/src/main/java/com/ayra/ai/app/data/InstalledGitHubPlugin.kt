package com.ayra.ai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent Room entity representing a real installed GitHub repository plugin,
 * Claude-style Skill Pack, ChatGPT-style OpenAPI Plugin, or MCP Server definition.
 */
@Entity(tableName = "installed_github_plugins")
data class InstalledGitHubPlugin(
    @PrimaryKey
    val repoFullName: String, // e.g. "obra/superpowers"
    val repoUrl: String,
    val name: String,
    val owner: String,
    val description: String,
    val defaultBranch: String = "main",
    val stars: Int = 0,
    val primaryLanguage: String = "Unknown",
    val pluginType: String = "CODE_PLUGIN", // SKILL_PACK, OPENAPI_PLUGIN, MCP_SERVER, WEB_TOOL, CODE_PLUGIN
    val topicsJson: String = "[]",
    val capabilitiesSummary: String = "",
    val skillPromptContext: String = "",
    val openApiSpecJson: String = "[]",
    val commandsJson: String = "[]",
    val fileTreeJson: String = "[]",
    val isEnabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long = System.currentTimeMillis()
)
