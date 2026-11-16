package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface VectorKnowledgeDao {

    @Query("SELECT * FROM vector_knowledge_sources ORDER BY fetchedAt DESC, id DESC")
    fun observeAllSources(): Flow<List<VectorKnowledgeSourceEntity>>

    @Query("SELECT COUNT(*) FROM vector_knowledge_sources")
    fun observeSourceCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vector_knowledge_chunks")
    fun observeChunkCount(): Flow<Int>

    @Query("SELECT layer, COUNT(*) AS chunkCount FROM vector_knowledge_chunks GROUP BY layer")
    fun observeLayerChunkCounts(): Flow<List<LayerVectorCount>>

    @Query("SELECT COUNT(*) FROM vector_knowledge_sources")
    suspend fun countSourcesOnce(): Int

    @Query("SELECT COUNT(*) FROM vector_knowledge_chunks")
    suspend fun countChunksOnce(): Int

    @Query("SELECT * FROM vector_knowledge_sources WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getSourceByKey(sourceKey: String): VectorKnowledgeSourceEntity?

    @Query("SELECT * FROM vector_knowledge_sources ORDER BY fetchedAt DESC")
    suspend fun getAllSourcesOnce(): List<VectorKnowledgeSourceEntity>

    @Query("SELECT * FROM vector_knowledge_chunks")
    suspend fun getAllChunksOnce(): List<VectorKnowledgeChunkEntity>

    @Query("SELECT * FROM vector_knowledge_chunks WHERE layer = :layer")
    suspend fun getChunksByLayerOnce(layer: String): List<VectorKnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: VectorKnowledgeSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<VectorKnowledgeChunkEntity>)

    @Query("DELETE FROM vector_knowledge_sources WHERE sourceKey = :sourceKey")
    suspend fun deleteSourceByKey(sourceKey: String)

    @Query("DELETE FROM vector_knowledge_sources WHERE id = :sourceId")
    suspend fun deleteSourceById(sourceId: Long)

    @Query("DELETE FROM vector_knowledge_sources WHERE originType = 'LIVE_CACHED'")
    suspend fun deleteLiveCachedSources()

    @Query("DELETE FROM vector_knowledge_sources")
    suspend fun clearAllSources()

    @Transaction
    suspend fun upsertSourceWithChunks(
        source: VectorKnowledgeSourceEntity,
        chunks: List<VectorKnowledgeChunkEntity>
    ): Long {
        deleteSourceByKey(source.sourceKey)
        val sourceId = insertSource(source.copy(id = 0L, chunkCount = chunks.size))
        if (chunks.isNotEmpty()) {
            insertChunks(chunks.map { it.copy(id = 0L, sourceId = sourceId) })
        }
        return sourceId
    }
}
