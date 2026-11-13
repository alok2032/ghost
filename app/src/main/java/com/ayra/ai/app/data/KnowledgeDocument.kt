package com.ayra.ai.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "knowledge_documents")
data class KnowledgeDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mimeType: String,
    val charCount: Int,
    val chunkCount: Int,
    val sourceUri: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val documentTitle: String,
    val chunkIndex: Int,
    val content: String,
    val tokenEstimate: Int
)

@Dao
interface KnowledgeDocumentDao {
    @Query("SELECT * FROM knowledge_documents ORDER BY createdAt DESC")
    fun observeAllDocuments(): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_documents ORDER BY createdAt DESC")
    suspend fun getAllDocumentsSync(): List<KnowledgeDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: KnowledgeDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM document_chunks ORDER BY documentId ASC, chunkIndex ASC")
    suspend fun getAllChunksSync(): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    suspend fun getChunksForDocument(documentId: Long): List<DocumentChunkEntity>

    @Query("DELETE FROM knowledge_documents WHERE id = :documentId")
    suspend fun deleteDocumentById(documentId: Long)

    @Query("DELETE FROM knowledge_documents")
    suspend fun clearAllDocuments()
}
