package com.example.cactuspoc

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cactus.CactusLM
import com.cactus.CactusInitParams
import com.cactus.CactusCompletionParams
import com.cactus.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * DonnaViewModel — orchestrates the full three-agent pipeline:
 *
 *   Agent 1: Audio file → Whisper transcription → LLM extraction → AgentItems
 *   Agent 2: AgentItems → embed → store → retrieve → reason → ProposedActions
 *   Agent 3: ProposedActions → AgentCommands → ActionRouter (alarms / notifications / calendar)
 */
class DonnaViewModel : ViewModel() {

    companion object {
        private const val TAG = "Donna"
        const val SIMILARITY_THRESHOLD = 0.70
        const val EMBEDDING_MODEL = "nomic2-embed-300m"
        const val REASONING_MODEL = "qwen3-1.7-pro"
    }

    // ── LLM instances (Agent 2) ───────────────────────────────────────────────
    private val embeddingLM = CactusLM()
    private val reasoningLM = CactusLM()

    // ── Vector store (Agent 2) ────────────────────────────────────────────────
    private val vectorDB = InMemoryVectorDB()

    // ── Pipeline state (drives UI) ────────────────────────────────────────────
    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    // ── Chat messages (Agent 1 UI) ────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private var modelsReady = false

    // ─────────────────────────────────────────────────────────────────────────
    // MODEL INITIALISATION
    // ─────────────────────────────────────────────────────────────────────────

    fun initModels() {
        if (modelsReady) return
        viewModelScope.launch {
            try {
                addLog("⏳ Loading $EMBEDDING_MODEL…")
                embeddingLM.downloadModel(EMBEDDING_MODEL)
                embeddingLM.initializeModel(
                    CactusInitParams(model = EMBEDDING_MODEL, contextSize = 512)
                )
                addLog("✅ Embedding model ready")

                addLog("⏳ Loading $REASONING_MODEL…")
                reasoningLM.downloadModel(REASONING_MODEL)
                reasoningLM.initializeModel(
                    CactusInitParams(model = REASONING_MODEL, contextSize = 2048)
                )
                addLog("✅ Reasoning model ready\n")
                modelsReady = true
            } catch (e: Exception) {
                _pipelineState.value = PipelineState.Error("Model init failed: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENT 1 ENTRY POINT — called by SpeechToTextManager after transcription
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives a raw transcript from Agent 1 and drives the full pipeline.
     * Also posts the transcript as a chat message for the UI.
     */
    fun onTranscriptReceived(context: Context, transcript: String, contactName: String, contactGroup: ContactGroup) {
        addChatMessage(Message(role = "assistant", content = "📞 Transcript: $transcript"))
        _pipelineState.value = PipelineState.Extracting(transcript)

        viewModelScope.launch {
            try {
                if (!modelsReady) {
                    initModels()
                    // Wait for models — simple retry loop (max 60s)
                    var waited = 0
                    while (!modelsReady && waited < 60) {
                        kotlinx.coroutines.delay(1000)
                        waited++
                    }
                    if (!modelsReady) {
                        _pipelineState.value = PipelineState.Error("Models not ready")
                        return@launch
                    }
                }

                // ── Extract structured items from transcript ──────────────────
                val items = extractItems(transcript, contactName, contactGroup)
                if (items.isEmpty()) {
                    addChatMessage(Message("assistant", "ℹ️ No actionable or contextual items found."))
                    _pipelineState.value = PipelineState.Idle
                    return@launch
                }

                addChatMessage(Message("assistant", "🔍 Extracted ${items.size} item(s) from call with $contactName"))

                // ── Run Agent 2: vectorise, store, retrieve, reason ───────────
                val proposedActions = runAgent2(items)

                if (proposedActions.isEmpty()) {
                    _pipelineState.value = PipelineState.Idle
                    return@launch
                }

                // ── Run Agent 3: execute actions ──────────────────────────────
                runAgent3(context, proposedActions)

                _pipelineState.value = PipelineState.ActionsReady(
                    log = currentLog(),
                    actions = proposedActions
                )

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                _pipelineState.value = PipelineState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENT 1 — LLM-based extraction of AgentItems from transcript
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun extractItems(
        transcript: String,
        contactName: String,
        contactGroup: ContactGroup
    ): List<AgentItem> {

        val prompt = """
You are an assistant that extracts structured information from call transcripts.

Given the transcript below from a call with $contactName (${contactGroup.name}), extract ALL:
- ACTIONABLE items: concrete commitments, events, tasks, deadlines (e.g. "meeting Friday 6pm", "send report by Monday")
- CONTEXTUAL items: facts, preferences, life updates, feedback (e.g. "Sarah is vegetarian", "Alex started new job")

Respond ONLY with a JSON array. Each item:
{"id":"<unique>","text":"<one sentence>","type":"ACTIONABLE|CONTEXTUAL","tags":["tag1","tag2"]}

Transcript:
"$transcript"

Output the JSON array now. Nothing before it. Nothing after it.
        """.trimIndent()

        val result = reasoningLM.generateCompletion(
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            params = CactusCompletionParams(maxTokens = 1024, temperature = 0.1)
        )

        val raw = result?.response?.trim() ?: return emptyList()
        return parseItemsJson(raw, contactName, contactGroup)
    }

    private fun parseItemsJson(
        raw: String,
        contactName: String,
        contactGroup: ContactGroup
    ): List<AgentItem> {
        return try {
            val cleaned = raw
                .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            // Simple regex-based extraction (avoids needing Gson for this)
            val items = mutableListOf<AgentItem>()
            val objectRegex = Regex("""\{[^{}]+\}""")
            objectRegex.findAll(cleaned).forEach { match ->
                val obj = match.value
                val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                    ?: "item-${System.currentTimeMillis()}"
                val text = Regex(""""text"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                    ?: return@forEach
                val typeStr = Regex(""""type"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                    ?: "CONTEXTUAL"
                val tagsRaw = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(obj)?.groupValues?.get(1) ?: ""
                val tags = Regex(""""([^"]+)"""").findAll(tagsRaw).map { it.groupValues[1] }.toList()

                items.add(
                    AgentItem(
                        id = id,
                        text = text,
                        type = if (typeStr == "ACTIONABLE") ItemType.ACTIONABLE else ItemType.CONTEXTUAL,
                        contactName = contactName,
                        contactGroup = contactGroup,
                        timestampMs = System.currentTimeMillis(),
                        tags = tags
                    )
                )
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "Item parse error: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENT 2 — Vectorise → Store → Retrieve → Reason
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runAgent2(items: List<AgentItem>): List<ProposedAction> {
        val log = mutableListOf<String>()
        fun emit(line: String) {
            log.add(line)
            _pipelineState.value = PipelineState.Vectorising(log.toList())
        }

        val proposedActions = mutableListOf<ProposedAction>()

        for (item in items) {
            emit("▶ [${item.id}] ${item.type} • ${item.contactName}")
            emit("  \"${item.text}\"")

            val embResult = embeddingLM.generateEmbedding(text = item.text)
            if (embResult == null || !embResult.success) {
                emit("  ⚠ Embedding failed\n"); continue
            }
            emit("  📐 dim=${embResult.dimension}")

            vectorDB.add(VectorEntry(item, embResult.embeddings))
            emit("  💾 Stored (DB size: ${vectorDB.size()})")

            val (matches, isCrossContact) = vectorDB.search(
                queryEmbedding = embResult.embeddings,
                topK = 3,
                excludeId = item.id,
                contactName = item.contactName,
                threshold = SIMILARITY_THRESHOLD
            )

            if (matches.isEmpty()) { emit("  🔍 No strong matches yet\n"); continue }

            val stage = if (isCrossContact) "⚡ cross-contact" else "👤 same-contact"
            emit("  🔍 $stage — ${matches.size} match(es) ≥ $SIMILARITY_THRESHOLD")
            matches.forEach { (entry, score) ->
                emit("     [${entry.item.id}] ${"%.3f".format(score)} \"${entry.item.text}\"")
            }

            emit("  🤔 Reasoning…")
            _pipelineState.value = PipelineState.Reasoning(log.toList())

            val (bestEntry, bestScore) = matches.first()
            val action = reasonAndDecide(item, bestEntry.item, bestScore, isCrossContact)
            proposedActions.add(action)

            emit("  ✅ ${action.actionType}")
            emit("  💬 \"${action.notificationMessage}\"\n")
        }

        return proposedActions
    }

    private fun buildSystemPrompt(newItem: AgentItem, matchedItem: AgentItem, isCrossContact: Boolean): String {
        val ctx = if (isCrossContact)
            "${newItem.contactName} and ${matchedItem.contactName} are different people. Look for scheduling conflicts."
        else
            "Both items are about the same person: ${newItem.contactName}."
        return """
You are a proactive personal assistant monitoring a person's life — commitments, events, and knowledge about the people around them.
A CONTEXTUAL item is passive knowledge — a preference, life update, feedback.
An ACTIONABLE item is a concrete commitment — an event, task, or deadline.
$ctx
        """.trimIndent()
    }

    private suspend fun pass1Reason(
        newItem: AgentItem, matchedItem: AgentItem, isCrossContact: Boolean
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

        val result = reasoningLM.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = userPrompt)
            ),
            params = CactusCompletionParams(maxTokens = 2048, temperature = 0.6)
        )
        return userPrompt to (result?.response?.trim() ?: "")
    }

    private suspend fun pass2Extract(
        newItem: AgentItem, matchedItem: AgentItem, isCrossContact: Boolean,
        pass1UserPrompt: String, pass1Response: String
    ): Triple<String, String, String> {
        val cleanedReasoning = pass1Response
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("#{1,3}\\s"), "")
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
            .replace(Regex("-{3,}"), "")
            .trim()

        val extractPrompt = """
You have just reasoned about two related items. Now output only a JSON object:
{"reasoning": "...", "action": "...", "message": "..."}

Rules:
- "reasoning": one sentence — the key connection between the two items
- "action": exactly one of: NOTIFY_CLASH, SUGGEST_PREP, SEND_REMINDER, SET_ALARM
- "message": what the user sees on their phone — mention ${newItem.contactName} by name, under 15 words

Output the JSON now. Nothing before it. Nothing after it.
        """.trimIndent()

        val result = reasoningLM.generateCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(newItem, matchedItem, isCrossContact)),
                ChatMessage(role = "user", content = pass1UserPrompt),
                ChatMessage(role = "assistant", content = cleanedReasoning),
                ChatMessage(role = "user", content = extractPrompt)
            ),
            params = CactusCompletionParams(maxTokens = 512, temperature = 0.1)
        )

        return parseActionJson(result?.response?.trim() ?: "")
    }

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
            Triple(reasoning, if (action.uppercase() in validActions) action.uppercase() else "SEND_REMINDER", message)
        } catch (e: Exception) {
            Triple("Items are related.", "SEND_REMINDER", "You have related items needing attention.")
        }
    }

    private suspend fun reasonAndDecide(
        newItem: AgentItem, matchedItem: AgentItem, similarity: Double, isCrossContact: Boolean
    ): ProposedAction {
        val (p1Prompt, p1Response) = pass1Reason(newItem, matchedItem, isCrossContact)
        val (reasoning, action, message) = pass2Extract(newItem, matchedItem, isCrossContact, p1Prompt, p1Response)
        return ProposedAction(
            triggerItem = newItem, matchedItem = matchedItem,
            similarity = similarity, isCrossContact = isCrossContact,
            reasoning = reasoning, actionType = action, notificationMessage = message
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGENT 3 — Execute proposed actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun runAgent3(context: Context, actions: List<ProposedAction>) {
        for (action in actions) {
            val command = AgentCommand(
                action = action.actionType,
                message = action.notificationMessage
            )
            ActionRouter.execute(context, command)
            addChatMessage(Message("assistant", "⚡ ${action.actionType}: ${action.notificationMessage}"))
            Log.d(TAG, "Executed: ${action.actionType} — ${action.notificationMessage}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MANUAL COMMAND ENTRY — from chat UI (also goes through Agent 3)
    // ─────────────────────────────────────────────────────────────────────────

    fun sendChatCommand(context: Context, text: String) {
        addChatMessage(Message("user", text))

        // Try text command parser first (Agent 3's TextCommandParser)
        val parsedCommand = TextCommandParser.parse(text)
        if (parsedCommand != null) {
            ActionRouter.execute(context, parsedCommand)
            addChatMessage(Message("assistant", "✅ Done: ${parsedCommand.action}"))
            return
        }

        // Otherwise run through reasoning LM for a conversational response
        viewModelScope.launch {
            if (!modelsReady) {
                addChatMessage(Message("assistant", "⏳ Models still loading, please wait…"))
                return@launch
            }
            val result = reasoningLM.generateCompletion(
                messages = listOf(
                    ChatMessage(role = "system", content = "You are Donna, a proactive personal assistant. Be concise."),
                    ChatMessage(role = "user", content = text)
                ),
                params = CactusCompletionParams(maxTokens = 512, temperature = 0.7)
            )
            val response = result?.response?.trim() ?: "Sorry, I couldn't process that."
            addChatMessage(Message("assistant", response))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private var logLines = mutableListOf<String>()

    private fun addLog(line: String) {
        logLines.add(line)
    }

    private fun currentLog() = logLines.toList()

    fun addChatMessage(message: Message) {
        _messages.value = _messages.value + message
    }

    override fun onCleared() {
        super.onCleared()
        embeddingLM.unload()
        reasoningLM.unload()
    }
}
