package com.example.cactuspoc

import kotlin.math.sqrt

/**
 * Singleton in-memory vector store.
 * Persists for the full app session — embeddings from earlier jobs
 * are available for cross-contact matching with later jobs.
 */
object InMemoryVectorDB {

    private val entries = mutableListOf<VectorEntry>()

    fun add(entry: VectorEntry) {
        synchronized(entries) { entries.add(entry) }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    /**
     * Two-stage search:
     *
     * Stage 1 — same-contact: compare against all items for the same person.
     * Stage 2 — cross-contact fallback: if stage 1 yields nothing above threshold,
     *   search ACTIONABLE items from other contacts (clash detection).
     *
     * Returns (matches, isCrossContact).
     */
    fun search(
        queryEmbedding: List<Double>,
        topK: Int = 3,
        excludeId: String,
        contactName: String,
        threshold: Double = 0.70
    ): Pair<List<Pair<VectorEntry, Double>>, Boolean> {
        val snapshot = synchronized(entries) { entries.toList() }

        val sameContact = scoreAndSort(
            queryEmbedding,
            snapshot.filter { it.item.id != excludeId && it.item.contactName == contactName },
            topK
        ).filter { it.second >= threshold }

        if (sameContact.isNotEmpty()) return sameContact to false

        val crossContact = scoreAndSort(
            queryEmbedding,
            snapshot.filter {
                it.item.id != excludeId &&
                it.item.contactName != contactName &&
                it.item.type == ItemType.ACTIONABLE
            },
            topK
        ).filter { it.second >= threshold }

        return crossContact to true
    }

    private fun scoreAndSort(
        query: List<Double>,
        candidates: List<VectorEntry>,
        topK: Int
    ): List<Pair<VectorEntry, Double>> =
        candidates
            .map { it to cosineSimilarity(query, it.embedding) }
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
