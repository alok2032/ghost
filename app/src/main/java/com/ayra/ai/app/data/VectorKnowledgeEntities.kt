package com.ayra.ai.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata row for each knowledge document stored in the local Room Vector Database
 * (curated offline corpus entries as well as live-cached Deep Web / Dark Web / Surface Wiki fetches).
 */
@Entity(
    tableName = "vector_knowledge_sources",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["layer"]),
        Index(value = ["originType"])
    ]
)
data class VectorKnowledgeSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceKey: String,
    val title: String,
    val layer: String,          // DARK_WEB, DEEP_WEB, SURFACE_WIKI
    val providerName: String,   // e.g., Tor Project Onionoo, CIRCL / NVD CVE, OpenAlex, arXiv, PubMed, Ayra Curated Corpus
    val sourceUrl: String,
    val originType: String,     // CURATED_OFFLINE, LIVE_CACHED
    val language: String = "en",
    val summaryText: String,
    val chunkCount: Int,
    val fetchedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L    // 0L = evergreen archival/curated; >0 = cache freshness deadline
)

/**
 * Vectorized chunk belonging to a [VectorKnowledgeSourceEntity].
 * Stores a compact IEEE-754 little-endian L2-normalized Float32 vector in SQLite BLOB format.
 */
@Entity(
    tableName = "vector_knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = VectorKnowledgeSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceKey"]),
        Index(value = ["layer"])
    ]
)
data class VectorKnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceId: Long,
    val sourceKey: String,
    val layer: String,
    val providerName: String,
    val title: String,
    val sourceUrl: String,
    val originType: String,
    val language: String,
    val chunkIndex: Int,
    val content: String,
    val keywordsCsv: String = "",
    val tokenCount: Int,
    val embeddingDims: Int,
    val embeddingBlob: ByteArray,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorKnowledgeChunkEntity
        return id == other.id &&
            sourceId == other.sourceId &&
            sourceKey == other.sourceKey &&
            chunkIndex == other.chunkIndex &&
            content == other.content &&
            embeddingDims == other.embeddingDims &&
            embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + sourceKey.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}

data class LayerVectorCount(
    val layer: String,
    val chunkCount: Int
)
