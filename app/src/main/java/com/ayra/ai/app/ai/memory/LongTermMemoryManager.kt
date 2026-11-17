package com.ayra.ai.app.ai.memory

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatDatabase
import com.ayra.ai.app.data.LongTermMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

sealed class MemorySaveResult {
    data class Saved(val entity: LongTermMemoryEntity) : MemorySaveResult()
    data class Rejected(val reason: String) : MemorySaveResult()
}

data class MemoryCandidate(
    val category: String,
    val content: String
)

/**
 * Manages opt-in, encrypted Room-backed Long-Term Memory for Ayra AI.
 * Security & privacy invariants:
 * - Defaults to opt-in OFF unless the user enables Memory.
 * - Rejects API keys, Bearer/JWT tokens, passwords, OTPs, card numbers, and govt IDs.
 * - Automatic extraction never silently saves; surfaces [MemoryCandidate] for explicit user confirmation.
 */
object LongTermMemoryManager {

    val VALID_CATEGORIES = listOf("PROFILE", "PREFERENCE", "PROJECT", "WORKFLOW", "GENERAL")

    private val credentialOrPiiPatterns = listOf(
        Regex("AIza[0-9A-Za-z\\-_]{20,}"),
        Regex("\\b(sk|pk|rk|gsk|hf|xai|nvapi|pplx)[\\-_][0-9A-Za-z\\-_]{10,}"),
        Regex("\\bgh[pousr]_[0-9A-Za-z]{20,}\\b"),
        Regex("\\bgithub_pat_[0-9A-Za-z_]{20,}\\b"),
        Regex("(?i)\\b(bearer|api[\\s_-]?key|secret[\\s_-]?key|access[\\s_-]?token|refresh[\\s_-]?token|private[\\s_-]?key)\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\b(password|passcode|pin\\s*code|secret|otp)\\s*(is|=|:)\\s*\\S+"),
        Regex("\\beyJ[0-9A-Za-z_\\-]{10,}\\.[0-9A-Za-z_\\-]{10,}\\.[0-9A-Za-z_\\-]{8,}\\b"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
        Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    )

    fun containsSensitiveCredentialOrPii(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        return credentialOrPiiPatterns.any { it.containsMatchIn(clean) }
    }

    fun observeAllMemories(context: Context): Flow<List<LongTermMemoryEntity>> {
        return runCatching {
            ChatDatabase.getDatabase(context).longTermMemoryDao().observeAll()
        }.getOrElse { flowOf(emptyList()) }
    }

    suspend fun trySaveMemory(
        context: Context,
        category: String,
        rawContent: String,
        sourceSessionId: Long? = null,
        isPinned: Boolean = false,
        bypassOptInCheck: Boolean = false
    ): MemorySaveResult = withContext(Dispatchers.IO) {
        val clean = rawContent.trim().replace(Regex("\\s+"), " ").take(400)
        if (clean.length < 3) {
            return@withContext MemorySaveResult.Rejected("Memory note must be at least 3 characters.")
        }
        if (!bypassOptInCheck && !AyraPreferences.isMemoryEnabled(context)) {
            return@withContext MemorySaveResult.Rejected("Long-Term Memory is currently disabled. Enable opt-in Memory first.")
        }
        if (containsSensitiveCredentialOrPii(clean)) {
            return@withContext MemorySaveResult.Rejected(
                "Blocked for your safety: passwords, API keys, tokens, card numbers, and government IDs cannot be stored in Long-Term Memory."
            )
        }

        val normalizedCategory = category.uppercase().takeIf { it in VALID_CATEGORIES } ?: "GENERAL"
        val dao = ChatDatabase.getDatabase(context).longTermMemoryDao()
        val now = System.currentTimeMillis()
        val existing = dao.findExactSync(clean)
        val saved = if (existing != null) {
            val updated = existing.copy(
                category = normalizedCategory,
                isPinned = isPinned || existing.isPinned,
                updatedAt = now
            )
            dao.update(updated)
            updated
        } else {
            val entity = LongTermMemoryEntity(
                category = normalizedCategory,
                content = clean,
                sourceSessionId = sourceSessionId,
                isPinned = isPinned,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insert(entity)
            entity.copy(id = id)
        }
        MemorySaveResult.Saved(saved)
    }

    suspend fun updateMemory(
        context: Context,
        entity: LongTermMemoryEntity,
        newCategory: String,
        newContent: String,
        newPinned: Boolean
    ): MemorySaveResult = withContext(Dispatchers.IO) {
        val clean = newContent.trim().replace(Regex("\\s+"), " ").take(400)
        if (clean.length < 3) {
            return@withContext MemorySaveResult.Rejected("Memory note cannot be empty.")
        }
        if (containsSensitiveCredentialOrPii(clean)) {
            return@withContext MemorySaveResult.Rejected("Blocked: sensitive credentials or PII are not allowed in Memory.")
        }
        val normalizedCategory = newCategory.uppercase().takeIf { it in VALID_CATEGORIES } ?: entity.category
        val updated = entity.copy(
            category = normalizedCategory,
            content = clean,
            isPinned = newPinned,
            updatedAt = System.currentTimeMillis()
        )
        ChatDatabase.getDatabase(context).longTermMemoryDao().update(updated)
        MemorySaveResult.Saved(updated)
    }

    suspend fun deleteMemory(context: Context, id: Long) = withContext(Dispatchers.IO) {
        ChatDatabase.getDatabase(context).longTermMemoryDao().deleteById(id)
    }

    suspend fun deleteAllMemories(context: Context) = withContext(Dispatchers.IO) {
        ChatDatabase.getDatabase(context).longTermMemoryDao().deleteAll()
        AyraPreferences.clearAllMemory(context)
    }

    /**
     * Migrates any legacy SharedPreferences remembered facts into Room one time
     * (filtering out any sensitive strings) and retrieves top-K keyword/recency matches.
     */
    suspend fun retrieveRelevantMemories(
        context: Context,
        query: String,
        topK: Int = 6
    ): List<LongTermMemoryEntity> = withContext(Dispatchers.IO) {
        if (!AyraPreferences.isMemoryEnabled(context)) return@withContext emptyList()
        val dao = ChatDatabase.getDatabase(context).longTermMemoryDao()

        val legacyFacts = AyraPreferences.getRememberedFacts(context)
        if (legacyFacts.isNotEmpty()) {
            legacyFacts.forEach { legacy ->
                val clean = legacy.trim()
                if (clean.length >= 3 && !containsSensitiveCredentialOrPii(clean) && dao.findExactSync(clean) == null) {
                    dao.insert(
                        LongTermMemoryEntity(
                            category = "GENERAL",
                            content = clean.take(400)
                        )
                    )
                }
            }
        }

        val all = dao.getAllSync()
        if (all.isEmpty()) return@withContext emptyList()

        val queryTokens = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()

        if (queryTokens.isEmpty()) {
            return@withContext all.take(topK)
        }

        all.sortedByDescending { item ->
            val contentLower = item.content.lowercase()
            val overlap = queryTokens.count { contentLower.contains(it) }
            val pinBonus = if (item.isPinned) 10 else 0
            (overlap * 5) + pinBonus
        }.take(topK)
    }

    /**
     * Extracts explicit candidate user facts from a message without persisting them.
     * The UI asks the user to Confirm before saving.
     */
    fun extractCandidateFact(userPrompt: String): MemoryCandidate? {
        val clean = userPrompt.trim()
        if (clean.length < 8 || clean.length > 350 || containsSensitiveCredentialOrPii(clean)) return null

        val rememberCmd = Regex("(?i)^(?:remember(?:\\s+that)?|note\\s+that|save\\s+to\\s+memory|মনে\\s+রাখ(?:ো|বে))\\s*[:,-]?\\s*(.+)$")
        rememberCmd.find(clean)?.let { match ->
            val fact = match.groupValues[1].trim()
            if (fact.length >= 4 && !containsSensitiveCredentialOrPii(fact)) {
                return MemoryCandidate("PREFERENCE", fact)
            }
        }

        val profileMatch = Regex("(?i)\\b(?:my name is|call me|i am a|i work as|i live in|my favorite (?:language|framework|stack) is|আমার নাম|আমি থাকি)\\s+([^.!?\n]{3,90})").find(clean)
        if (profileMatch != null) {
            val fact = profileMatch.value.trim()
            if (!containsSensitiveCredentialOrPii(fact)) {
                return MemoryCandidate("PROFILE", fact)
            }
        }

        return null
    }
}
