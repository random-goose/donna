package com.example.cactuspoc

import kotlin.math.sqrt

/**
 * In-memory vector store with metadata-filtered cosine similarity search.
 *
 * Search strategy:
 *   Stage 1 — same-contact, any type. Avoids spurious cross-contact matches.
 *   Stage 2 — cross-contact fallback, actionable items only (clash detection).
 */
class InMemoryVectorDB {

    private val entries = mutableListOf<VectorEntry>()

    fun add(entry: VectorEntry) = entries.add(entry)

    fun size() = entries.size

    fun clear() = entries.clear()

    /**
     * Returns (matches, isCrossContact).
     */
    fun search(
        queryEmbedding: List<Double>,
        topK: Int = 3,
        excludeId: String,
        contactName: String,
        threshold: Double
    ): Pair<List<Pair<VectorEntry, Double>>, Boolean> {

        // Stage 1: same contact
        val sameContact = scoreAndSort(
            queryEmbedding = queryEmbedding,
            candidates = entries.filter {
                it.item.id != excludeId && it.item.contactName == contactName
            },
            topK = topK
        ).filter { it.second >= threshold }

        if (sameContact.isNotEmpty()) return sameContact to false

        // Stage 2: cross-contact, actionable only
        val crossContact = scoreAndSort(
            queryEmbedding = queryEmbedding,
            candidates = entries.filter {
                it.item.id != excludeId &&
                        it.item.contactName != contactName &&
                        it.item.type == ItemType.ACTIONABLE
            },
            topK = topK
        ).filter { it.second >= threshold }

        return crossContact to true
    }

    private fun scoreAndSort(
        queryEmbedding: List<Double>,
        candidates: List<VectorEntry>,
        topK: Int
    ): List<Pair<VectorEntry, Double>> =
        candidates
            .map { it to cosineSimilarity(queryEmbedding, it.embedding) }
            .sortedByDescending { it.second }
            .take(topK)

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
