package com.ayra.ai.app.ai.plugins

import android.content.Context
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.InstalledGitHubPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe in-memory mirror over Room [GitHubPluginDao] so installed GitHub plugins
 * can be queried in <1ms during prompt orchestration without Frame-1 Main Thread I/O.
 */
object GitHubPluginRegistry {

    private val cachedPlugins = CopyOnWriteArrayList<InstalledGitHubPlugin>()
    private val initializedFromDb = AtomicBoolean(false)

    private val _pluginsFlow = MutableStateFlow<List<InstalledGitHubPlugin>>(emptyList())
    val pluginsFlow: StateFlow<List<InstalledGitHubPlugin>> = _pluginsFlow.asStateFlow()

    suspend fun getAllPlugins(context: Context?): List<InstalledGitHubPlugin> {
        if (context != null && initializedFromDb.compareAndSet(false, true)) {
            refreshFromDatabase(context)
        }
        return cachedPlugins.toList()
    }

    suspend fun getEnabledPlugins(context: Context?): List<InstalledGitHubPlugin> {
        return getAllPlugins(context).filter { it.isEnabled }
    }

    suspend fun refreshFromDatabase(context: Context) = withContext(Dispatchers.IO) {
        try {
            val rows = ChatDatabase.getDatabase(context.applicationContext)
                .gitHubPluginDao()
                .getAllPluginsSync()
            cachedPlugins.clear()
            cachedPlugins.addAll(rows)
            _pluginsFlow.value = rows
            initializedFromDb.set(true)
        } catch (_: Exception) {
            initializedFromDb.set(false)
        }
    }

    suspend fun savePlugin(context: Context?, plugin: InstalledGitHubPlugin) = withContext(Dispatchers.IO) {
        if (context != null) {
            ChatDatabase.getDatabase(context.applicationContext)
                .gitHubPluginDao()
                .upsertPlugin(plugin)
            syncSinglePluginToCloudQuietly(context, plugin)
        }
        val updated = cachedPlugins.filterNot { it.repoFullName.equals(plugin.repoFullName, ignoreCase = true) }
            .toMutableList()
        updated.add(0, plugin)
        cachedPlugins.clear()
        cachedPlugins.addAll(updated)
        _pluginsFlow.value = updated
        initializedFromDb.set(true)
    }

    suspend fun setPluginEnabled(context: Context?, repoFullName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        if (context != null) {
            ChatDatabase.getDatabase(context.applicationContext)
                .gitHubPluginDao()
                .setEnabled(repoFullName, enabled)
        }
        val updated = cachedPlugins.map {
            if (it.repoFullName.equals(repoFullName, ignoreCase = true)) it.copy(isEnabled = enabled) else it
        }
        cachedPlugins.clear()
        cachedPlugins.addAll(updated)
        _pluginsFlow.value = updated
        if (context != null) {
            updated.firstOrNull { it.repoFullName.equals(repoFullName, ignoreCase = true) }
                ?.let { syncSinglePluginToCloudQuietly(context, it) }
        }
    }

    suspend fun uninstallPlugin(context: Context?, repoFullName: String) = withContext(Dispatchers.IO) {
        if (context != null) {
            ChatDatabase.getDatabase(context.applicationContext)
                .gitHubPluginDao()
                .deletePlugin(repoFullName)
            runCatching {
                val repo = com.ayra.ai.app.cloud.AyraCloudRepository.create(context)
                if (repo.currentUserIdOrNull() != null) {
                    repo.deletePluginFromCloud(repoFullName)
                }
            }
        }
        val updated = cachedPlugins.filterNot { it.repoFullName.equals(repoFullName, ignoreCase = true) }
        cachedPlugins.clear()
        cachedPlugins.addAll(updated)
        _pluginsFlow.value = updated
    }

    suspend fun reconcileWithCloud(context: Context, cloudRepo: com.ayra.ai.app.cloud.AyraCloudRepository) = withContext(Dispatchers.IO) {
        if (cloudRepo.currentUserIdOrNull() == null) return@withContext
        runCatching {
            val local = getAllPlugins(context)
            val cloud = cloudRepo.fetchCloudPluginsOnce()
            val localNames = local.map { it.repoFullName.lowercase() }.toSet()
            val cloudNames = cloud.map { it.repoFullName.lowercase() }.toSet()

            // Push local-only plugins to cloud
            local.filter { it.repoFullName.lowercase() !in cloudNames }.forEach { plugin ->
                runCatching { cloudRepo.syncPluginToCloud(plugin) }
            }
            // Pull cloud-only plugins to local Room DB
            val dao = ChatDatabase.getDatabase(context.applicationContext).gitHubPluginDao()
            cloud.filter { it.repoFullName.lowercase() !in localNames }.forEach { plugin ->
                dao.upsertPlugin(plugin)
            }
            refreshFromDatabase(context)
        }
    }

    private suspend fun syncSinglePluginToCloudQuietly(context: Context, plugin: InstalledGitHubPlugin) {
        runCatching {
            if (com.google.firebase.FirebaseApp.getApps(context.applicationContext).isNotEmpty()) {
                val repo = com.ayra.ai.app.cloud.AyraCloudRepository.create(context)
                if (repo.currentUserIdOrNull() != null) {
                    repo.syncPluginToCloud(plugin)
                }
            }
        }
    }

    fun getCachedEnabledPlugins(): List<InstalledGitHubPlugin> = cachedPlugins.filter { it.isEnabled }

    fun clearForTesting() {
        cachedPlugins.clear()
        _pluginsFlow.value = emptyList()
        initializedFromDb.set(false)
    }
}
