package com.example.cactuspoc

// ─────────────────────────────────────────────────────────────────────────────
// AGENT 1 — Chat UI model
// ─────────────────────────────────────────────────────────────────────────────

data class Message(
    val role: String,   // "user" | "assistant" | "system"
    val content: String
)

// ─────────────────────────────────────────────────────────────────────────────
// AGENT 2 — Vector store models
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
    val actionType: String,          // NOTIFY_CLASH | SUGGEST_PREP | SEND_REMINDER | SET_ALARM
    val notificationMessage: String
)

// ─────────────────────────────────────────────────────────────────────────────
// AGENT 3 — Executable command model
// ─────────────────────────────────────────────────────────────────────────────

data class AgentCommand(
    val action: String,
    val message: String? = null,
    val prepSuggestion: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val day: Int? = null,
    val month: Int? = null,
    val title: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// PIPELINE STATE — drives the main UI
// ─────────────────────────────────────────────────────────────────────────────

sealed class PipelineState {
    object Idle : PipelineState()
    data class Transcribing(val fileName: String) : PipelineState()
    data class Extracting(val transcript: String) : PipelineState()
    data class Vectorising(val log: List<String>) : PipelineState()
    data class Reasoning(val log: List<String>) : PipelineState()
    data class ActionsReady(val log: List<String>, val actions: List<ProposedAction>) : PipelineState()
    data class Error(val message: String) : PipelineState()
}
