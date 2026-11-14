package com.ayra.ai.app.ai.code

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class GeneratedProjectFile(
    val relativePath: String,
    val content: String
)

data class SavedCodeProject(
    val name: String,
    val rootDir: File,
    val fileCount: Int,
    val lastModifiedMs: Long
)

/**
 * Manages multi-file generated software projects in isolated app storage and builds real ZIP archives.
 */
object ProjectWorkspaceManager {

    private val fileHeaderRegex = Regex("(?m)^(?:===\\s*FILE:\\s*|//\\s*FILE:\\s*|#\\s*FILE:\\s*)([^=\\r\\n]+?)(?:\\s*===)?\\s*$")

    fun parseProjectFilesFromAiResponse(aiOutput: String, fallbackProjectName: String = "ayra_project"): List<GeneratedProjectFile> {
        val matches = fileHeaderRegex.findAll(aiOutput).toList()
        if (matches.isNotEmpty()) {
            val files = mutableListOf<GeneratedProjectFile>()
            for (i in matches.indices) {
                val rawPath = matches[i].groupValues[1].trim()
                val safePath = sanitizeRelativePath(rawPath)
                val bodyStart = matches[i].range.last + 1
                val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else aiOutput.length
                val rawBody = aiOutput.substring(bodyStart, bodyEnd).trim()
                val unwrapped = stripOuterCodeFence(rawBody)
                if (safePath.isNotBlank() && unwrapped.isNotBlank()) {
                    files.add(GeneratedProjectFile(safePath, unwrapped))
                }
            }
            if (files.isNotEmpty()) return files
        }

        // Fallback: extract fenced code blocks as numbered files
        val fenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\s*\\n([\\s\\S]*?)```")
        val fenced = fenceRegex.findAll(aiOutput).toList()
        if (fenced.isNotEmpty()) {
            return fenced.mapIndexed { idx, match ->
                val lang = match.groupValues[1].lowercase().trim()
                val ext = when (lang) {
                    "kotlin", "kt" -> "kt"
                    "java" -> "java"
                    "python", "py" -> "py"
                    "javascript", "js" -> "js"
                    "typescript", "ts" -> "ts"
                    "html" -> "html"
                    "css" -> "css"
                    "json" -> "json"
                    "xml" -> "xml"
                    else -> "txt"
                }
                GeneratedProjectFile(
                    relativePath = "${fallbackProjectName}/file_${idx + 1}.$ext",
                    content = match.groupValues[2].trim()
                )
            }
        }

        return listOf(
            GeneratedProjectFile(
                relativePath = "$fallbackProjectName/README.md",
                content = aiOutput.trim()
            )
        )
    }

    fun writeProjectToDiskAndZip(
        context: Context,
        projectName: String,
        files: List<GeneratedProjectFile>
    ): Pair<File, File> {
        val safeProj = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "ayra_project" }
        val rootDir = File(context.filesDir, "code_projects/$safeProj").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val rootCanonicalPrefix = rootDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
        for (file in files) {
            val safePath = sanitizeRelativePath(file.relativePath)
            val target = File(rootDir, safePath)
            // Canonical path traversal guard (prevents sibling prefix bypass)
            if (!target.canonicalPath.startsWith(rootCanonicalPrefix)) continue
            target.parentFile?.mkdirs()
            target.writeText(file.content, Charsets.UTF_8)
        }

        val zipFile = createProjectZip(context, safeProj, rootDir)
        return rootDir to zipFile
    }

    fun listSavedProjects(context: Context): List<SavedCodeProject> {
        val baseDir = File(context.filesDir, "code_projects")
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                val latest = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
                SavedCodeProject(
                    name = dir.name,
                    rootDir = dir,
                    fileCount = files.size,
                    lastModifiedMs = latest
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            .orEmpty()
    }

    fun loadProjectFiles(rootDir: File): List<GeneratedProjectFile> {
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        return rootDir.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                runCatching {
                    val rel = file.relativeTo(rootDir).path.replace("\\", "/")
                    GeneratedProjectFile(rel, file.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .sortedBy { it.relativePath }
            .toList()
    }

    fun saveEditedProjectFile(rootDir: File, relativePath: String, content: String): Boolean {
        return try {
            val safePath = sanitizeRelativePath(relativePath)
            val target = File(rootDir, safePath)
            val rootCanonicalPrefix = rootDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
            if (!target.canonicalPath.startsWith(rootCanonicalPrefix)) return false
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun createProjectFile(rootDir: File, relativePath: String, initialContent: String = ""): Boolean {
        return saveEditedProjectFile(rootDir, relativePath, initialContent)
    }

    fun deleteProject(context: Context, projectName: String): Boolean {
        val safeProj = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { return false }
        val baseDir = File(context.filesDir, "code_projects")
        val targetDir = File(baseDir, safeProj)
        val basePrefix = baseDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
        if (!targetDir.canonicalPath.startsWith(basePrefix)) return false
        return if (targetDir.exists()) targetDir.deleteRecursively() else false
    }

    fun createProjectZip(context: Context, projectName: String, rootDir: File): File {
        val safeProj = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "ayra_project" }
        val exportsDir = File(context.filesDir, "project_zips").apply { if (!exists()) mkdirs() }
        val zipFile = File(exportsDir, "${safeProj}_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            rootDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(rootDir).path.replace("\\", "/")
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun sanitizeRelativePath(raw: String): String {
        return raw.replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[^a-zA-Z0-9._-]"), "_") }
            .ifBlank { "main.txt" }
    }

    private fun stripOuterCodeFence(raw: String): String {
        val trimmed = raw.trim()
        val match = Regex("^```[a-zA-Z0-9_+-]*\\s*\\n([\\s\\S]*?)\\n```\\s*$").find(trimmed)
        return match?.groupValues?.get(1)?.trim() ?: trimmed
    }
}
