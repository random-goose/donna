package com.example.cactuspoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cactus.CactusLM
import com.cactus.CactusInitParams
import com.cactus.CactusCompletionParams
import com.cactus.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

enum class ItemType { ACTIONABLE, CONTEXTUAL }
enum class ContactGroup { WORK, PERSONAL }

data class AgentItem(
    val id: String,
    val text: String,
    val type: ItemType,
    val contactName: String,
    val contactGroup: ContactGroup,
    val timestampMs: Long,
    val tags: List<String>
)

data class VectorEntry(
    val item: AgentItem,
    val embedding: List<Double>
)

data class ProposedAction(
    val triggerItem: AgentItem,
    val matchedItem: AgentItem,
    val similarity: Double,
    val isCrossContact: Boolean,
    val reasoning: String,
    val actionType: String,
    val notificationMessage: String
)

sealed class DemoState {
    object Idle : DemoState()
    data class Loading(val message: String) : DemoState()
    data class Processing(val log: List<String>) : DemoState()
    data class Done(val log: List<String>, val actions: List<ProposedAction>) : DemoState()
    data class Error(val message: String) : DemoState()
}

// ─────────────────────────────────────────────────────────────────────────────
// SYNTHETIC INPUTS
// ─────────────────────────────────────────────────────────────────────────────

val syntheticItems: List<AgentItem> = listOf(
    AgentItem(
        id = "ctx-001",
        text = "Sarah is vegetarian and only eats Thai or Japanese food.",
        type = ItemType.CONTEXTUAL,
        contactName = "Sarah",
        contactGroup = ContactGroup.PERSONAL,
        timestampMs = System.currentTimeMillis() - 7 * 86_400_000L,
        tags = listOf("diet", "preference", "food")
    ),
    AgentItem(
        id = "ctx-002",
        text = "Alex just started a new job at Stripe and said he is really busy this month.",
        type = ItemType.CONTEXTUAL,
        contactName = "Alex",
        contactGroup = ContactGroup.PERSONAL,
        timestampMs = System.currentTimeMillis() - 3 * 86_400_000L,
        tags = listOf("job", "life-update", "availability")
    ),
    AgentItem(
        id = "ctx-003",
        text = "Manager David gave feedback: my last presentation lacked quantitative data and charts.",
        type = ItemType.CONTEXTUAL,
        contactName = "David",
        contactGroup = ContactGroup.WORK,
        timestampMs = System.currentTimeMillis() - 1 * 86_400_000L,
        tags = listOf("feedback", "presentation", "work-performance")
    ),
    AgentItem(
        id = "act-001",
        text = "Dinner with Sarah on Friday at 7 PM. Need to pick a restaurant.",
        type = ItemType.ACTIONABLE,
        contactName = "Sarah",
        contactGroup = ContactGroup.PERSONAL,
        timestampMs = System.currentTimeMillis() - 2 * 86_400_000L,
        tags = listOf("date-event", "dinner", "personal")
    ),
    AgentItem(
        id = "act-002",
        text = "Team sync meeting with David on Friday at 6 PM — cannot be skipped.",
        type = ItemType.ACTIONABLE,
        contactName = "David",
        contactGroup = ContactGroup.WORK,
        timestampMs = System.currentTimeMillis() - 1 * 86_400_000L,
        tags = listOf("work-meeting", "important")
    ),
    AgentItem(
        id = "act-003",
        text = "Send quarterly report to David by end of this week.",
        type = ItemType.ACTIONABLE,
        contactName = "David",
        contactGroup = ContactGroup.WORK,
        timestampMs = System.currentTimeMillis() - 4 * 86_400_000L,
        tags = listOf("task", "deadline", "report")
    ),
    AgentItem(
        id = "act-004",
        text = "Catch-up call with Alex next Tuesday at 3 PM.",
        type = ItemType.ACTIONABLE,
        contactName = "Alex",
        contactGroup = ContactGroup.PERSONAL,
        timestampMs = System.currentTimeMillis() - 6 * 86_400_000L,
        tags = listOf("call", "personal", "friend")
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// IN-MEMORY VECTOR DB  (metadata-filtered)
// ─────────────────────────────────────────────────────────────────────────────

class InMemoryVectorDB {
    private val entries = mutableListOf<VectorEntry>()

    fun add(entry: VectorEntry) = entries.add(entry)

    /**
     * Two-stage search:
     *
     * Stage 1 — same-contact filter.
     *   Only compare against items for the same person. Eliminates
     *   spurious cross-contact matches entirely.
     *
     * Stage 2 — cross-contact fallback (clash detection only).
     *   If stage 1 returns nothing above threshold, search ACTIONABLE
     *   items from other contacts. A contextual fact about Sarah is never
     *   relevant to a David event, but two events can clash regardless of contact.
     *
     * Returns matches + flag indicating whether the cross-contact fallback fired.
     */
    fun search(
        queryEmbedding: List<Double>,
        topK: Int = 3,
        excludeId: String,
        contactName: String,
        threshold: Double
    ): Pair<List<Pair<VectorEntry, Double>>, Boolean> {

        // Stage 1: same contact, any type
        val sameContact = scoreAndSort(
            queryEmbedding = queryEmbedding,
            candidates = entries.filter {
                it.item.id != excludeId &&
                        it.item.contactName == contactName
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

// ─────────────────────────────────────────────────────────────────────────────
// VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

class Agent2ViewModel : ViewModel() {

    private val embeddingLM = CactusLM()  // nomic2-embed-300m  → embeddings only
    private val reasoningLM = CactusLM()  // qwen3-1.7-pro      → completion only

    private val vectorDB = InMemoryVectorDB()

    private val _state = MutableStateFlow<DemoState>(DemoState.Idle)
    val state: StateFlow<DemoState> = _state

    companion object {
        const val SIMILARITY_THRESHOLD = 0.70
        const val EMBEDDING_MODEL = "nomic2-embed-300m"
        const val REASONING_MODEL = "qwen3-1.7-pro"
    }

    fun runDemo() {
        viewModelScope.launch {
            val log = mutableListOf<String>()
            fun emit(line: String) {
                log.add(line)
                _state.value = DemoState.Processing(log.toList())
            }

            try {
                // ── Embedding model ───────────────────────────────────────────
                _state.value = DemoState.Loading("Downloading $EMBEDDING_MODEL…")
                embeddingLM.downloadModel(EMBEDDING_MODEL)
                embeddingLM.initializeModel(
                    CactusInitParams(model = EMBEDDING_MODEL, contextSize = 512)
                )
                emit("✅ Embedding model ready: $EMBEDDING_MODEL")

                // ── Reasoning model ───────────────────────────────────────────
                emit("⏳ Loading $REASONING_MODEL…")
                reasoningLM.downloadModel(REASONING_MODEL)
                reasoningLM.initializeModel(
                    CactusInitParams(model = REASONING_MODEL, contextSize = 2048)
                )
                emit("✅ Reasoning model ready: $REASONING_MODEL\n")

                val proposedActions = mutableListOf<ProposedAction>()

                for (item in syntheticItems) {
                    emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    emit("▶ [${item.id}] ${item.type}  •  ${item.contactName} (${item.contactGroup})")
                    emit("  \"${item.text}\"")
                    emit("  Tags: ${item.tags.joinToString()}")

                    // Embed raw text only — metadata is used for filtering, not embedding
                    val embResult = embeddingLM.generateEmbedding(text = item.text)
                    if (embResult == null || !embResult.success) {
                        emit("  ⚠ Embedding failed: ${embResult?.errorMessage ?: "null"}\n")
                        continue
                    }
                    emit("  📐 Embedded  dim=${embResult.dimension}")

                    vectorDB.add(VectorEntry(item, embResult.embeddings))
                    emit("  💾 Stored  [contact=${item.contactName}, group=${item.contactGroup}, type=${item.type}]")

                    val (matches, isCrossContact) = vectorDB.search(
                        queryEmbedding = embResult.embeddings,
                        topK = 3,
                        excludeId = item.id,
                        contactName = item.contactName,
                        threshold = SIMILARITY_THRESHOLD
                    )

                    if (matches.isEmpty()) {
                        emit("  🔍 No strong matches yet\n")
                        continue
                    }

                    val stage = if (isCrossContact) "⚡ cross-contact" else "👤 same-contact"
                    emit("  🔍 $stage — ${matches.size} match(es) ≥ $SIMILARITY_THRESHOLD:")
                    matches.forEach { (entry, score) ->
                        emit("     [${entry.item.id}] ${"%.3f".format(score)}  ${entry.item.contactName}  \"${entry.item.text}\"")
                    }

                    emit("  🤔 Reasoning…")
                    val (bestEntry, bestScore) = matches.first()
                    val action = reasonAndDecide(item, bestEntry.item, bestScore, isCrossContact)
                    proposedActions.add(action)

                    emit("  ✅ ${action.actionType}")
                    emit("  💬 \"${action.notificationMessage}\"\n")
                }

                emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                emit("📋 PROPOSED ACTIONS SUMMARY  (${proposedActions.size} total)")
                emit("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                proposedActions.forEachIndexed { i, a ->
                    emit("")
                    emit("  [${i + 1}] ${a.actionType}${if (a.isCrossContact) "  ⚡ cross-contact" else ""}")
                    emit("      Trigger : [${a.triggerItem.id}] ${a.triggerItem.contactName}  \"${a.triggerItem.text}\"")
                    emit("      Match   : [${a.matchedItem.id}] ${a.matchedItem.contactName}  \"${a.matchedItem.text}\"")
                    emit("      Score   : ${"%.3f".format(a.similarity)}")
                    emit("      Why     : ${a.reasoning}")
                    emit("      Notif   : \"${a.notificationMessage}\"")
                }

                _state.value = DemoState.Done(log.toList(), proposedActions)

            } catch (e: Exception) {
                _state.value = DemoState.Error(e.message ?: "Unknown error")
            } finally {
                embeddingLM.unload()
                reasoningLM.unload()
            }
        }
    }


    // ── Shared system prompt ──────────────────────────────────────────────────
    private fun buildSystemPrompt(newItem: AgentItem, matchedItem: AgentItem, isCrossContact: Boolean): String {
        val relationshipContext = if (isCrossContact)
            "${newItem.contactName} and ${matchedItem.contactName} are different people. Look for scheduling conflicts between their events."
        else
            "Both items are about the same person: ${newItem.contactName}."

        return """
You are a proactive personal assistant. You monitor a person's life — their commitments, events, and knowledge about the people around them.

A CONTEXTUAL item is passive knowledge about a person — a preference, a life update, feedback they gave you, something they mentioned.
An ACTIONABLE item is a concrete commitment — an event on the calendar, a task with a deadline, something that needs doing.

$relationshipContext
""".trimIndent()
    }

    // ── Pass 1: free reasoning ────────────────────────────────────────────────
    // Returns both the user prompt (needed for pass 2 history) and the response
    private suspend fun pass1Reason(
        newItem: AgentItem,
        matchedItem: AgentItem,
        isCrossContact: Boolean
    ): Pair<String, String> {
        val userPrompt = """
Two related pieces of information have been found.

ITEM A — ${newItem.type}
Contact: ${newItem.contactName} (${newItem.contactGroup})
Content: "${newItem.text}"

ITEM B — ${matchedItem.type}
Contact: ${matchedItem.contactName} (${matchedItem.contactGroup})
Content: "${matchedItem.text}"

What does the user need to know or do right now because of these two items? Be specific about the people and content involved.
""".trimIndent()

        val result = reasoningLM.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = userPrompt)
            ),
            params = CactusCompletionParams(maxTokens = 2048, temperature = 0.6)
        )

        val response = result?.response?.trim() ?: ""
        println("──── PASS 1 RAW ────")
        println(response)
        println("────────────────────")
        return userPrompt to response
    }

    // ── Pass 2: continuation — reformat pass 1 output as JSON ────────────────
    // Sends the full conversation history so the model knows it already reasoned
    // and just needs to reformat its own conclusion.
    private suspend fun pass2Extract(
        newItem: AgentItem,
        matchedItem: AgentItem,
        isCrossContact: Boolean,
        pass1UserPrompt: String,
        pass1Response: String
    ): Triple<String, String, String> {
        // Strip <think> block and markdown noise from pass 1 before feeding back
        val cleanedReasoning = pass1Response
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("#{1,3}\\s"), "")       // markdown headers
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")  // bold/italic
            .replace(Regex("-{3,}"), "")            // horizontal rules
            .trim()

        val extractPrompt = """
You have just reasoned about two related items. Now output only a JSON object with exactly these three keys:

{"reasoning": "...", "action": "...", "message": "..."}

Rules:
- "reasoning": one sentence — the single most important connection between the two items
- "action": must be exactly one of: NOTIFY_CLASH, SUGGEST_PREP, SEND_REMINDER, SET_ALARM
- "message": what the user sees on their phone — mention ${newItem.contactName} by name, be specific, under 15 words

Example: {"reasoning": "Sarah only eats Thai or Japanese food and the restaurant for Friday is not chosen yet.", "action": "SUGGEST_PREP", "message": "Picking a restaurant for Sarah? She only eats Thai or Japanese!"}

Output the JSON now. Nothing before it. Nothing after it.
""".trimIndent()

        val result = reasoningLM.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = pass1UserPrompt),
                ChatMessage(role = "assistant", content = cleanedReasoning),
                ChatMessage(role = "user", content = extractPrompt)
            ),
            params = CactusCompletionParams(maxTokens = 2048, temperature = 0.1)
        )

        val raw = result?.response?.trim() ?: ""
        println("──── PASS 2 RAW ────")
        println(raw)
        println("────────────────────")

        return parseActionJson(raw)
    }

    // ── JSON parser — lenient, handles model sloppiness ───────────────────────
    private fun parseActionJson(raw: String): Triple<String, String, String> {
        val cleaned = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            val reasoning = Regex(""""reasoning"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "Items are related."
            val action    = Regex(""""action"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "SEND_REMINDER"
            val message   = Regex(""""message"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "You have related items needing attention."

            val validActions = setOf("NOTIFY_CLASH", "SUGGEST_PREP", "SEND_REMINDER", "SET_ALARM")
            val safeAction = if (action.uppercase() in validActions) action.uppercase() else "SEND_REMINDER"

            Triple(reasoning, safeAction, message)
        } catch (e: Exception) {
            println("JSON parse failed: ${e.message}")
            Triple("Items are related.", "SEND_REMINDER", "You have related items needing attention.")
        }
    }

    // ── Orchestrates both passes ──────────────────────────────────────────────
    private suspend fun reasonAndDecide(
        newItem: AgentItem,
        matchedItem: AgentItem,
        similarity: Double,
        isCrossContact: Boolean
    ): ProposedAction {
        val (pass1UserPrompt, pass1Response) = pass1Reason(newItem, matchedItem, isCrossContact)
        val (extractedReasoning, action, message) = pass2Extract(
            newItem, matchedItem, isCrossContact, pass1UserPrompt, pass1Response
        )

        return ProposedAction(
            triggerItem = newItem,
            matchedItem = matchedItem,
            similarity = similarity,
            isCrossContact = isCrossContact,
            reasoning = extractedReasoning,
            actionType = action,
            notificationMessage = message
        )
    }

    override fun onCleared() {
        super.onCleared()
        embeddingLM.unload()
        reasoningLM.unload()
    }
}