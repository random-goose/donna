package com.example.cactuspoc.agent1

import kotlinx.serialization.Serializable

enum class JobStatus {
    QUEUED, TRANSCRIBING, EXTRACTING, EMBEDDING, REASONING, DONE, FAILED
}

@Serializable
data class ExtractionResult(
    val contact: String,
    val contactGroup: String,   // "personal" | "work"
    val timestamp: String,      // ISO-8601
    val type: String,           // "call" | "notification"
    val actionable: List<String>,
    val contextual: List<String>
)

data class PipelineJob(
    val id: String,
    val type: String,               // "call" | "notification"
    val contact: String,
    val contactGroup: String,
    val timestamp: String,          // ISO-8601
    val rawInput: String,           // transcript text or notification text (populated after transcription)
    val filePath: String? = null,   // AMR file path for call jobs
    @Volatile var status: JobStatus = JobStatus.QUEUED,
    @Volatile var result: ExtractionResult? = null,
    @Volatile var errorMessage: String? = null
)