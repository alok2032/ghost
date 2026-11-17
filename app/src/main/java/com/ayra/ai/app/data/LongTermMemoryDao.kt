package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LongTermMemoryDao {
    @Query("SELECT * FROM long_term_memories ORDER BY isPinned DESC, updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<LongTermMemoryEntity>>

    @Query("SELECT * FROM long_term_memories ORDER BY isPinned DESC, updatedAt DESC, id DESC")
    suspend fun getAllSync(): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memories WHERE LOWER(content) LIKE '%' || LOWER(:query) || '%' OR LOWER(category) LIKE '%' || LOWER(:query) || '%' ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun searchSync(query: String): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memories WHERE LOWER(TRIM(content)) = LOWER(TRIM(:content)) LIMIT 1")
    suspend fun findExactSync(content: String): LongTermMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LongTermMemoryEntity): Long

    @Update
    suspend fun update(entity: LongTermMemoryEntity)

    @Query("DELETE FROM long_term_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM long_term_memories WHERE LOWER(TRIM(content)) = LOWER(TRIM(:content))")
    suspend fun deleteByContent(content: String)

    @Query("DELETE FROM long_term_memories")
    suspend fun deleteAll()
}
