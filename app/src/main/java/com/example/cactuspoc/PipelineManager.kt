package com.example.cactuspoc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.cactuspoc.Agent2Processor
import com.example.cactuspoc.ExtractionStage
import com.example.cactuspoc.TranscriptionStage
import java.time.Instant
import java.util.UUID
import com.example.cactuspoc.agent1.*

//import com.example.cactuspoc.agent2.*

object PipelineManager {

    private const val TAG = "PipelineManager"

    // ---------- State exposed to UI / Agent 2 ----------

    private val _jobs = MutableStateFlow<List<PipelineJob>>(emptyList())
    val jobs: StateFlow<List<PipelineJob>> = _jobs.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // ---------- Internal ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<PipelineJob>(capacity = Channel.UNLIMITED)

    /** Must be called once, typically from MainActivity.onCreate (after CactusContextInitializer). */
    fun start(context: Context) {
        scope.launch {
            for (job in queue) {
                processJob(context, job)
            }
        }
        appendLog("PipelineManager started")
    }

    // ---------- Enqueue helpers ----------

    fun enqueueCall(
        contact: String,
        contactGroup: String,
        timestamp: String,
        filePath: String
    ): PipelineJob {
        val job = PipelineJob(
            id = UUID.randomUUID().toString(),
            type = "call",
            contact = contact,
            contactGroup = contactGroup,
            timestamp = timestamp,
            rawInput = "",          // will be populated after transcription
            filePath = filePath,
            status = JobStatus.QUEUED
        )
        addJob(job)
        queue.trySend(job)
        appendLog("Enqueued call job ${job.id} — $contact")
        return job
    }

    fun enqueueNotification(
        contact: String,
        contactGroup: String,
        notificationText: String
    ): PipelineJob {
        val job = PipelineJob(
            id = UUID.randomUUID().toString(),
            type = "notification",
            contact = contact,
            contactGroup = contactGroup,
            timestamp = Instant.now().toString(),
            rawInput = notificationText,
            filePath = null,
            status = JobStatus.QUEUED
        )
        addJob(job)
        queue.trySend(job)
        appendLog("Enqueued notification job ${job.id} — $contact")
        return job
    }

    // ---------- Processing ----------

    private suspend fun processJob(context: Context, initialJob: PipelineJob) {
        appendLog("Processing job ${initialJob.id} [${initialJob.type}]")

        // var so we always operate on whichever copy is actually in the list
        var job = initialJob

        try {
            // ── Step 1: Transcription (calls only) ──────────────────────
            val transcript: String
            if (job.type == "call") {
                updateStatus(job, JobStatus.TRANSCRIBING)
                appendLog("Transcribing call for ${job.contact}…")

                val amrPath = job.filePath
                    ?: throw IllegalStateException("Call job has no filePath")

                val text = TranscriptionStage.transcribe(context, amrPath)
                    ?: throw RuntimeException("Transcription returned null")

                // Replace in list AND update local ref so all subsequent calls use the new copy
                val updated = job.copy(rawInput = text)
                replaceJob(updated)
                job = updated
                transcript = text
                appendLog("Transcription done (${text.length} chars)")
            } else {
                transcript = job.rawInput
            }

            // ── Step 2: Extraction ───────────────────────────────────────
            updateStatus(job, JobStatus.EXTRACTING)
            appendLog("Extracting info for ${job.contact}…")

            val extracted = ExtractionStage.extract(transcript)
                ?: throw RuntimeException("Extraction returned null")

            val result = ExtractionResult(
                contact = job.contact,
                contactGroup = job.contactGroup,
                timestamp = job.timestamp,
                type = job.type,
                actionable = extracted.first,
                contextual = extracted.second
            )

            // ── Step 3: Mark done ────────────────────────────────────────
            updateJobResult(job, result)
            appendLog("Job ${job.id} handed to Agent2 — ${result.actionable.size} actionable, ${result.contextual.size} contextual")

        } catch (e: Exception) {
            Log.e(TAG, "Job ${job.id} failed", e)
            updateJobFailed(job, e.message ?: "Unknown error")
            appendLog("Job ${job.id} FAILED: ${e.message}")
        }
    }

    // ---------- Public API for Agent 2 ----------

    fun updateJobStatus(job: PipelineJob, status: JobStatus) = updateStatus(job, status)

    fun markJobDone(job: PipelineJob) {
        job.status = JobStatus.DONE
        _jobs.value = _jobs.value.toList()
        appendLog("Job ${job.id} DONE (Agent2 complete)")
    }

    fun markJobFailed(job: PipelineJob, message: String) = updateJobFailed(job, message)

    // ---------- Mutators (thread-safe via MutableStateFlow copy) ----------

    private fun addJob(job: PipelineJob) {
        _jobs.value = _jobs.value + job
    }

    private fun updateStatus(job: PipelineJob, status: JobStatus) {
        job.status = status
        // Trigger recomposition by replacing the list
        _jobs.value = _jobs.value.toList()
    }

    private fun replaceJob(updated: PipelineJob) {
        _jobs.value = _jobs.value.map { if (it.id == updated.id) updated else it }
    }

    private fun updateJobResult(job: PipelineJob, result: ExtractionResult) {
        job.status = JobStatus.EMBEDDING  // Agent 2 will advance to DONE
        job.result = result
        _jobs.value = _jobs.value.toList()
        Agent2Processor.onJobDone(job)
    }

    private fun updateJobFailed(job: PipelineJob, message: String) {
        job.status = JobStatus.FAILED
        job.errorMessage = message
        _jobs.value = _jobs.value.toList()
    }

    private fun appendLog(message: String) {
        val entry = "[${java.time.LocalTime.now()}] $message"
        Log.d(TAG, entry)
        _log.value = _log.value + entry
    }
}