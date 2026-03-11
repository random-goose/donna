package com.example.cactuspoc

import android.util.Log
import com.cactus.CactusLM
import com.cactus.CactusCompletionParams
import com.cactus.ChatMessage
import com.example.cactuspoc.agent1.ExtractionResult
import com.example.cactuspoc.agent1.JobStatus
import com.example.cactuspoc.agent1.PipelineJob
import com.example.cactuspoc.PipelineManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.cactuspoc.agent3.ActionRouter
import com.example.cactuspoc.agent3.AgentCommand
import java.util.UUID

object Agent2Processor {

    private const val TAG = "Agent2Processor"
    private const val SIMILARITY_THRESHOLD = 0.70

    private lateinit var appContext: android.content.Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _actions = MutableStateFlow<List<ProposedAction>>(emptyList())
    val actions: StateFlow<List<ProposedAction>> = _actions.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun start(context: android.content.Context) {
        appContext = context.applicationContext
        appendLog("Agent2Processor started")
    }

    /**
     * Called directly by PipelineManager when Agent 1 finishes a job.
     * Runs on whatever thread PipelineManager calls from — launches own coroutine.
     */
    fun onJobDone(job: PipelineJob) {
        val result = job.result ?: return
        scope.launch { processExtractionResult(job, result) }
    }

    // ── Map ExtractionResult → AgentItems ────────────────────────────────────

    private fun toAgentItems(result: ExtractionResult, jobId: String): List<AgentItem> {
        val group = if (result.contactGroup == "work") ContactGroup.WORK else ContactGroup.PERSONAL
        val tsMs = try {
            java.time.Instant.parse(result.timestamp).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        return result.actionable.map { text ->
            AgentItem(
                id = UUID.randomUUID().toString(),
                text = text,
                type = ItemType.ACTIONABLE,
                contactName = result.contact,
                contactGroup = group,
                timestampMs = tsMs,
                sourceJobId = jobId
            )
        } + result.contextual.map { text ->
            AgentItem(
                id = UUID.randomUUID().toString(),
                text = text,
                type = ItemType.CONTEXTUAL,
                contactName = result.contact,
                contactGroup = group,
                timestampMs = tsMs,
                sourceJobId = jobId
            )
        }
    }

    // ── Main processing ───────────────────────────────────────────────────────

    private suspend fun processExtractionResult(job: PipelineJob, result: ExtractionResult) {
        val items = toAgentItems(result, job.id)
        appendLog("Agent2: ${items.size} items from job ${job.id} (${result.contact})")

        if (items.isEmpty()) {
            PipelineManager.markJobDone(job)
            return
        }

        // ── Phase 1: embed all items in one EMBEDDER session ──────────────
        PipelineManager.updateJobStatus(job, JobStatus.EMBEDDING)

        val embeddings = mutableMapOf<String, List<Double>>()
        val embedder = try {
            ModelManager.acquireLM(ModelManager.EMBEDDER, contextSize = 512)
        } catch (e: Exception) {
            appendLog("Agent2: failed to load embedder — ${e.message}")
            PipelineManager.markJobFailed(job, "Embedder load failed: ${e.message}")
            return
        }

        try {
            for (item in items) {
                val embResult = embedder.generateEmbedding(text = item.text)
                if (!embResult?.success!! || embResult.embeddings.isEmpty()) {
                    appendLog("Agent2: embedding failed for [${item.id}]: ${embResult.errorMessage}")
                    continue
                }
                embeddings[item.id] = embResult.embeddings
                InMemoryVectorDB.add(VectorEntry(item, embResult.embeddings))
                appendLog("Agent2: embedded [${item.type}] \"${item.text.take(50)}\"  dim=${embResult.dimension}")
            }
        } finally {
            ModelManager.releaseLM(embedder, ModelManager.EMBEDDER)
        }

        // ── Phase 2: search + reason per matched item ─────────────────────
        PipelineManager.updateJobStatus(job, JobStatus.REASONING)

        for (item in items) {
            val embedding = embeddings[item.id] ?: continue

            val (matches, isCrossContact) = InMemoryVectorDB.search(
                queryEmbedding = embedding,
                excludeId = item.id,
                contactName = item.contactName,
                threshold = SIMILARITY_THRESHOLD
            )

            val reasoner = try {
                ModelManager.acquireLM(ModelManager.REASONER, contextSize = 2048)
            } catch (e: Exception) {
                appendLog("Agent2: failed to load reasoner — ${e.message}")
                continue
            }

            val action = if (matches.isEmpty()) {
                appendLog("Agent2: no matches for [${item.id}] — standalone reasoning…")
                try {
                    reasonStandalone(reasoner, item)
                } catch (e: Exception) {
                    appendLog("Agent2: standalone reasoning failed — ${e.message}")
                    null
                } finally {
                    ModelManager.releaseLM(reasoner, ModelManager.REASONER)
                }
            } else {
                val stage = if (isCrossContact) "cross-contact" else "same-contact"
                appendLog("Agent2: $stage match for [${item.id}] — reasoning…")
                val (bestEntry, bestScore) = matches.first()
                try {
                    reasonAndDecide(reasoner, item, bestEntry.item, bestScore, isCrossContact)
                } catch (e: Exception) {
                    appendLog("Agent2: reasoning failed — ${e.message}")
                    null
                } finally {
                    ModelManager.releaseLM(reasoner, ModelManager.REASONER)
                }
            }

            if (action != null) {
                _actions.value = _actions.value + action
                appendLog("Agent2: ✅ ${action.actionType} — \"${action.notificationMessage}\"")
                dispatchToAgent3(action)
            }
        }

        // ── Mark fully complete ───────────────────────────────────────────
        PipelineManager.markJobDone(job)
        appendLog("Agent2: job ${job.id} complete")
    }

    // ── Two-pass reasoning ────────────────────────────────────────────────────

    private fun buildSystemPrompt(newItem: AgentItem, matchedItem: AgentItem, isCrossContact: Boolean): String {
        val ctx = if (isCrossContact)
            "${newItem.contactName} and ${matchedItem.contactName} are different people. Look for scheduling conflicts."
        else
            "Both items are about the same person: ${newItem.contactName}."
        return """
You are a proactive personal assistant monitoring a person's commitments and social context.
A CONTEXTUAL item is passive knowledge — a preference, life update, or piece of feedback.
An ACTIONABLE item is a concrete commitment — an event, task, or deadline.
$ctx
        """.trimIndent()
    }

    private suspend fun pass1Reason(
        reasoner: CactusLM,
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

What does the user need to know or do right now because of these two items?
        """.trimIndent()

        var response = ""
        reasoner.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = userPrompt)
            ),
            params = CactusCompletionParams(temperature = 0.6, maxTokens = 512),
            onToken = { token, _ -> response += token }
        )
        Log.d(TAG, "Pass1 raw: ${response.take(200)}")
        return userPrompt to response.trim()
    }

    private suspend fun pass2Extract(
        reasoner: CactusLM,
        newItem: AgentItem,
        matchedItem: AgentItem,
        isCrossContact: Boolean,
        pass1UserPrompt: String,
        pass1Response: String
    ): Triple<String, String, String> {
        val cleanedReasoning = pass1Response
            .replace(Regex("<think>[\\s\\s]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("#{1,3}\\s"), "")
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
            .trim()

        val extractPrompt = """
You have just reasoned about two related items. Now output ONLY a JSON object with exactly these three keys:
{"reasoning": "...", "action": "...", "message": "..."}

Rules:
- "reasoning": one sentence — the single most important connection
- "action": exactly one of: NOTIFY_CLASH, SUGGEST_PREP, SEND_REMINDER, SET_ALARM
- "message": what the user sees on their phone — mention ${newItem.contactName} by name, under 15 words

Output the JSON now. Nothing before it. Nothing after it.
        """.trimIndent()

        var response = ""
        reasoner.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = pass1UserPrompt),
                ChatMessage(role = "assistant", content = cleanedReasoning),
                ChatMessage(role = "user", content = extractPrompt)
            ),
            params = CactusCompletionParams(temperature = 0.1, maxTokens = 256),
            onToken = { token, _ -> response += token }
        )
        Log.d(TAG, "Pass2 raw: ${response.take(200)}")
        return parseActionJson(response.trim())
    }


    // ── Standalone reasoning (no match found) ────────────────────────────────

    private suspend fun reasonStandalone(
        reasoner: CactusLM,
        item: AgentItem
    ): ProposedAction? {
        val systemPrompt = """
You are a proactive personal assistant. You monitor a person's life — their commitments, context about people, tasks, and events.
A CONTEXTUAL item is passive knowledge about a person — a preference, a life update, something they mentioned.
An ACTIONABLE item is a concrete commitment — an event, a task, a deadline.
Your job is to decide whether this single item warrants proactively notifying the user.
        """.trimIndent()

        val userPrompt = """
You have received the following item with no related context yet in memory.

ITEM — ${item.type}
Contact: ${item.contactName} (${item.contactGroup})
Content: "${item.text}"

Does this item require the user's immediate attention on its own?
Consider: Is there a deadline? Is it time-sensitive? Is it something easy to forget?
Be specific about why the user should or should not be notified.
        """.trimIndent()

        var pass1Response = ""
        reasoner.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user",   content = userPrompt)
            ),
            params  = CactusCompletionParams(temperature = 0.61, maxTokens = 512),
            onToken = { token, _ -> pass1Response += token }
        )
        Log.d(TAG, "Standalone pass1: ${pass1Response.take(200)}")

        val cleanedPass1 = pass1Response
            .replace(Regex("<think>[\\s\\s]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("#{1,3}\\s"), "")
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
            .trim()

        val extractPrompt = """
You just evaluated a single item. Now output ONLY a JSON object:
{"notify": true/false, "reasoning": "...", "action": "...", "message": "..."}

Rules:
- "notify": true if the user needs to act or be reminded, false if it can be ignored for now
- "reasoning": one sentence explaining your decision
- "action": exactly one of: SEND_REMINDER, SET_ALARM, SUGGEST_PREP, NOTIFY_CLASH
- "message": what the user sees on their phone — mention ${item.contactName} by name, under 15 words. Only matters if notify is true.

Output the JSON now. Nothing before it. Nothing after it.
        """.trimIndent()

        var pass2Response = ""
        reasoner.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system",    content = systemPrompt),
                ChatMessage(role = "user",      content = userPrompt),
                ChatMessage(role = "assistant", content = cleanedPass1),
                ChatMessage(role = "user",      content = extractPrompt)
            ),
            params  = CactusCompletionParams(temperature = 0.1, maxTokens = 256),
            onToken = { token, _ -> pass2Response += token }
        )
        Log.d(TAG, "Standalone pass2: ${pass2Response.take(200)}")

        val cleaned = pass2Response
            .replace(Regex("<think>[\\s\\s]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        return try {
            val notify    = Regex(""""notify"\\s*:\\s*(true|false)""").find(cleaned)?.groupValues?.get(1) == "true"
            val reasoning = Regex(""""reasoning"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "Item noted."
            val action    = Regex(""""action"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "SEND_REMINDER"
            val message   = Regex(""""message"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "New item from ${item.contactName}."

            if (!notify) {
                appendLog("Agent2: standalone — model decided no notification needed")
                return null
            }

            val validActions = setOf("NOTIFY_CLASH", "SUGGEST_PREP", "SEND_REMINDER", "SET_ALARM")
            // Use a sentinel AgentItem to represent "no match" in the ProposedAction
            val noMatchItem = AgentItem(
                id           = "none",
                text         = "(no related item in memory)",
                type         = item.type,
                contactName  = item.contactName,
                contactGroup = item.contactGroup,
                timestampMs  = item.timestampMs,
                sourceJobId  = item.sourceJobId
            )
            ProposedAction(
                triggerItem         = item,
                matchedItem         = noMatchItem,
                similarity          = 0.0,
                isCrossContact      = false,
                reasoning           = reasoning,
                actionType          = if (action.uppercase() in validActions) action.uppercase() else "SEND_REMINDER",
                notificationMessage = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Standalone JSON parse failed: ${e.message}")
            null
        }
    }

    private fun parseActionJson(raw: String): Triple<String, String, String> {
        val cleaned = raw
            .replace(Regex("<think>[\\s\\s]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        return try {
            val reasoning = Regex(""""reasoning"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "Items are related."
            val action    = Regex(""""action"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "SEND_REMINDER"
            val message   = Regex(""""message"\\s*:\\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "You have related items needing attention."
            val validActions = setOf("NOTIFY_CLASH", "SUGGEST_PREP", "SEND_REMINDER", "SET_ALARM")
            Triple(reasoning, if (action.uppercase() in validActions) action.uppercase() else "SEND_REMINDER", message)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
            Triple("Items are related.", "SEND_REMINDER", "You have related items needing attention.")
        }
    }

    private suspend fun reasonAndDecide(
        reasoner: CactusLM,
        newItem: AgentItem,
        matchedItem: AgentItem,
        similarity: Double,
        isCrossContact: Boolean
    ): ProposedAction {
        val (pass1Prompt, pass1Response) = pass1Reason(reasoner, newItem, matchedItem, isCrossContact)
        val (reasoning, action, message) = pass2Extract(reasoner, newItem, matchedItem, isCrossContact, pass1Prompt, pass1Response)
        return ProposedAction(
            triggerItem = newItem,
            matchedItem = matchedItem,
            similarity = similarity,
            isCrossContact = isCrossContact,
            reasoning = reasoning,
            actionType = action,
            notificationMessage = message
        )
    }


    // ── Agent 3 dispatch ─────────────────────────────────────────────────────

    private fun dispatchToAgent3(action: ProposedAction) {
        val command = AgentCommand(
            action  = action.actionType,
            message = action.notificationMessage,
            prepSuggestion = if (action.actionType == "SUGGEST_PREP") action.notificationMessage else null
        )
        appendLog("Agent3: dispatching ${command.action}")
        ActionRouter.execute(appContext, command)
    }

    private fun appendLog(message: String) {
        val entry = "[${java.time.LocalTime.now()}] $message"
        Log.d(TAG, entry)
        _log.value = _log.value + entry
    }
}
