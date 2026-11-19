package com.ayra.ai.app.ai.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

enum class EmbeddingKind {
    LEXICAL_HASH_FALLBACK,
    SEMANTIC_VECTOR
}

interface EmbeddingProvider {
    val modelId: String
    val dimensions: Int
    val kind: EmbeddingKind
    val versionFingerprint: String
    fun embed(
        text: String,
        title: String = "",
        keywordsCsv: String = ""
    ): FloatArray
}

class SemanticEmbeddingAdapter(
    override val modelId: String,
    override val dimensions: Int,
    override val versionFingerprint: String = "$modelId:v1:$dimensions",
    private val encoder: (text: String, title: String, keywordsCsv: String) -> FloatArray
) : EmbeddingProvider {
    override val kind: EmbeddingKind = EmbeddingKind.SEMANTIC_VECTOR

    override fun embed(text: String, title: String, keywordsCsv: String): FloatArray {
        val raw = encoder(text, title, keywordsCsv)
        val sized = if (raw.size == dimensions) raw.copyOf() else FloatArray(dimensions) { idx ->
            if (idx < raw.size) raw[idx] else 0f
        }
        var sumSq = 0.0
        for (v in sized) sumSq += v * v
        if (sumSq > 1e-12) {
            val invNorm = (1.0 / sqrt(sumSq)).toFloat()
            for (i in sized.indices) sized[i] *= invNorm
        }
        return sized
    }
}

/**
 * Deterministic, multilingual (English / Bengali / Banglish / Technical IDs) on-device dense vectorizer
 * producing 256-dimensional L2-normalized Float32 embeddings stored compactly as SQLite BLOBs.
 *
 * Combines:
 * - Unigram log-TF features
 * - Adjacent word bigrams
 * - Character 3-grams / 4-grams (subword morphology & typo resilience)
 * - Cross-lingual Bengali <-> English Deep/Dark Web concept alignment
 * - Signed dual-hash projection + L2 unit normalization (so cosine similarity == dot product)
 */
object LocalVectorEmbedder : EmbeddingProvider {

    const val DIMENSIONS: Int = 256
    const val EMBEDDING_MODEL_ID: String = "ayra-bilingual-subword-256d"
    const val EMBEDDING_VERSION_FINGERPRINT: String = "$EMBEDDING_MODEL_ID:v2:$DIMENSIONS"

    override val modelId: String = EMBEDDING_MODEL_ID
    override val dimensions: Int = DIMENSIONS
    override val kind: EmbeddingKind = EmbeddingKind.LEXICAL_HASH_FALLBACK
    override val versionFingerprint: String = EMBEDDING_VERSION_FINGERPRINT

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "in", "on", "at", "to",
        "for", "of", "and", "or", "with", "this", "that", "it", "by", "from", "as", "what",
        "how", "why", "who", "when", "where", "can", "you", "me", "my", "about", "explain",
        "tell", "ki", "kivabe", "keno", "কী", "কি", "কেন", "কীভাবে", "সম্পর্কে", "বলো", "বলুন"
    )

    // Cross-lingual & domain synonym projections so Bengali/Banglish & English queries map into shared conceptual subspaces.
    private val SYNSET_ANCHORS: Map<String, List<String>> = mapOf(
        "ডার্ক" to listOf("dark", "darknet", "onion", "tor"),
        "ডার্কওয়েব" to listOf("dark", "web", "darknet", "onion", "tor", "i2p"),
        "ডিপ" to listOf("deep", "academic", "scholarly", "archive"),
        "ডিপওয়েব" to listOf("deep", "web", "academic", "database", "arxiv", "openalex", "pubmed"),
        "অনিয়ন" to listOf("onion", "tor", "relay", "circuit", "v3"),
        "টর" to listOf("tor", "onion", "router", "relay", "guard", "exit"),
        "হিডেন" to listOf("hidden", "wiki", "directory", "onion", "ahmia"),
        "উইকি" to listOf("wiki", "encyclopedia", "wikipedia", "directory"),
        "এনক্রিপশন" to listOf("encryption", "cryptography", "cipher", "aes", "curve25519", "pgp"),
        "ক্রিপ্টোগ্রাফি" to listOf("cryptography", "encryption", "pgp", "ed25519", "pqc"),
        "সাইবার" to listOf("cyber", "security", "owasp", "cve", "vulnerability", "threat"),
        "ভালনারেবিলিটি" to listOf("vulnerability", "cve", "cvss", "nvd", "exploit", "advisory"),
        "গবেষণা" to listOf("research", "paper", "arxiv", "openalex", "doi", "academic"),
        "চিকিৎসা" to listOf("pubmed", "clinical", "medical", "biomedical", "nih"),
        "রোগ" to listOf("disease", "pubmed", "clinical", "medical"),
        "গার্লিক" to listOf("garlic", "i2p", "tunnel", "netdb"),
        "পিজিপি" to listOf("pgp", "openpgp", "gpg", "signature", "key"),
        "ভিপিএন" to listOf("vpn", "wireguard", "tunnel", "doh", "dns"),
        "কোয়ান্টাম" to listOf("quantum", "pqc", "postquantum", "kyber", "lattice"),
        "টেলিমেট্রি" to listOf("telemetry", "onionoo", "relay", "consensus"),
        "darkweb" to listOf("dark", "web", "darknet", "tor", "onion", "i2p"),
        "deepweb" to listOf("deep", "web", "openalex", "arxiv", "pubmed", "crossref"),
        "onion" to listOf("tor", "relay", "circuit", "ed25519", "rendezvous"),
        "tor" to listOf("onion", "relay", "onionoo", "guard", "exit", "curve25519"),
        "i2p" to listOf("garlic", "eepsite", "netdb", "tunnel", "leaseset"),
        "cve" to listOf("vulnerability", "cvss", "nvd", "circl", "advisory", "security"),
        "pubmed" to listOf("biomedical", "clinical", "nih", "medicine", "pmid"),
        "arxiv" to listOf("preprint", "research", "paper", "scholarly", "academic"),
        "openalex" to listOf("scholarly", "academic", "citation", "doi", "research"),
        "pgp" to listOf("openpgp", "gpg", "signature", "asymmetric", "trust"),
        "wireguard" to listOf("vpn", "chacha20", "poly1305", "noise", "tunnel")
    )

    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}_.-]+"))
            .map { it.trim('.', '-', '_') }
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }

    override fun embed(
        text: String,
        title: String,
        keywordsCsv: String
    ): FloatArray {
        val vec = FloatArray(DIMENSIONS)

        fun accumulateTokens(raw: String, fieldWeight: Float) {
            if (raw.isBlank()) return
            val tokens = tokenize(raw)
            if (tokens.isEmpty()) return

            val counts = tokens.groupingBy { it }.eachCount()
            for ((token, tf) in counts) {
                val tfWeight = fieldWeight * (1.0f + ln(tf.toDouble()).toFloat())
                projectFeature("w:$token", tfWeight, vec)

                // Subword 3-gram & 4-gram projection for morphological & technical matching
                if (token.length >= 4) {
                    val limit = minOf(token.length - 3, 18)
                    for (i in 0..limit) {
                        projectFeature("g3:${token.substring(i, i + 3)}", tfWeight * 0.35f, vec)
                    }
                }
                if (token.length >= 5) {
                    val limit4 = minOf(token.length - 4, 14)
                    for (i in 0..limit4) {
                        projectFeature("g4:${token.substring(i, i + 4)}", tfWeight * 0.25f, vec)
                    }
                }

                // Cross-lingual / domain semantic synset enrichment
                SYNSET_ANCHORS[token]?.forEach { anchor ->
                    projectFeature("syn:$anchor", tfWeight * 0.72f, vec)
                }
            }

            // Word bigrams capture multi-word concepts ("onion routing", "hidden wiki", "supply chain")
            for (i in 0 until tokens.size - 1) {
                projectFeature("bi:${tokens[i]}_${tokens[i + 1]}", fieldWeight * 0.9f, vec)
            }
        }

        accumulateTokens(title, 1.85f)
        accumulateTokens(keywordsCsv.replace(",", " "), 1.45f)
        accumulateTokens(text, 1.0f)

        normalizeInPlace(vec)
        return vec
    }

    fun toByteArray(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buf.putFloat(v)
        return buf.array()
    }

    fun fromByteArray(bytes: ByteArray): FloatArray {
        val count = bytes.size / 4
        val out = FloatArray(count)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            out[i] = buf.getFloat()
        }
        return out
    }

    /**
     * Zero-allocation cosine similarity for pre-normalized Float32 query vector against SQLite BLOB.
     */
    fun cosineSimilarity(normalizedQuery: FloatArray, targetBlob: ByteArray): Float {
        if (targetBlob.size != normalizedQuery.size * 4) return 0f
        var dot = 0f
        var i = 0
        var b = 0
        while (i < normalizedQuery.size) {
            val bits = (targetBlob[b].toInt() and 0xFF) or
                ((targetBlob[b + 1].toInt() and 0xFF) shl 8) or
                ((targetBlob[b + 2].toInt() and 0xFF) shl 16) or
                ((targetBlob[b + 3].toInt() and 0xFF) shl 24)
            dot += normalizedQuery[i] * java.lang.Float.intBitsToFloat(bits)
            i++
            b += 4
        }
        return dot.coerceIn(-1f, 1f)
    }

    private fun projectFeature(feature: String, weight: Float, target: FloatArray) {
        val h1 = fnv1a64(feature, seed = 0x9E3779B97F4A7C15UL)
        val h2 = fnv1a64(feature, seed = 0xBF58476D1CE4E5B9UL)
        val idx1 = ((h1 and 0x7FFFFFFFUL) % DIMENSIONS.toULong()).toInt()
        val idx2 = ((h2 and 0x7FFFFFFFUL) % DIMENSIONS.toULong()).toInt()
        val sign1 = if (((h1 shr 63) and 1UL) == 0UL) 1f else -1f
        val sign2 = if (((h2 shr 63) and 1UL) == 0UL) 1f else -1f
        target[idx1] += sign1 * weight
        target[idx2] += sign2 * weight * 0.7071f
    }

    private fun fnv1a64(input: String, seed: ULong): ULong {
        var hash = 0xcbf29ce484222325UL xor seed
        for (i in 0 until input.length) {
            hash = hash xor input[i].code.toULong()
            hash *= 0x100000001b3UL
        }
        hash = hash xor (hash shr 33)
        hash *= 0xff51afd7ed558ccdUL
        hash = hash xor (hash shr 33)
        return hash
    }

    private fun normalizeInPlace(vec: FloatArray) {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        if (sumSq <= 1e-12) return
        val invNorm = (1.0 / sqrt(sumSq)).toFloat()
        for (i in vec.indices) {
            vec[i] *= invNorm
        }
    }
}
