package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GitHubPluginDao {
    @Query("SELECT * FROM installed_github_plugins ORDER BY installedAt DESC")
    fun getAllPlugins(): Flow<List<InstalledGitHubPlugin>>

    @Query("SELECT * FROM installed_github_plugins ORDER BY installedAt DESC")
    suspend fun getAllPluginsSync(): List<InstalledGitHubPlugin>

    @Query("SELECT * FROM installed_github_plugins WHERE isEnabled = 1 ORDER BY installedAt DESC")
    suspend fun getEnabledPluginsSync(): List<InstalledGitHubPlugin>

    @Query("SELECT * FROM installed_github_plugins WHERE repoFullName = :repoFullName LIMIT 1")
    suspend fun getPlugin(repoFullName: String): InstalledGitHubPlugin?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlugin(plugin: InstalledGitHubPlugin)

    @Query("UPDATE installed_github_plugins SET isEnabled = :enabled WHERE repoFullName = :repoFullName")
    suspend fun setEnabled(repoFullName: String, enabled: Boolean)

    @Query("DELETE FROM installed_github_plugins WHERE repoFullName = :repoFullName")
    suspend fun deletePlugin(repoFullName: String)
}
