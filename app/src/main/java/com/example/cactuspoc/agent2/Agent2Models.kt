package com.example.cactuspoc

// ── Agent 2 data models ───────────────────────────────────────────────────────
// Kept separate from Agent 1 models (PipelineJob / ExtractionResult)

enum class ItemType { ACTIONABLE, CONTEXTUAL }

enum class ContactGroup { WORK, PERSONAL }

data class AgentItem(
    val id: String,
    val text: String,
    val type: ItemType,
    val contactName: String,
    val contactGroup: ContactGroup,
    val timestampMs: Long,
    val sourceJobId: String      // which PipelineJob this came from
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
    val actionType: String,       // NOTIFY_CLASH | SUGGEST_PREP | SEND_REMINDER | SET_ALARM
    val notificationMessage: String
)
