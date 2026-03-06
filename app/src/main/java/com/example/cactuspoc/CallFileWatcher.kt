package com.example.cactuspoc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CallFileWatcher(private val context: Context) {

    companion object {
        private const val TAG = "CallFileWatcher"
        private const val RECORDINGS_DIR =
            "/storage/emulated/0/Documents/CactusRecordings/CubeCallRecorder/All"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MIN_FILE_SIZE_BYTES = 1024L

        private val FILENAME_REGEX = Regex("""^phone_(\d{8})-(\d{6})__(.+)\.amr$""", RegexOption.IGNORE_CASE)
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seenFiles = mutableSetOf<String>()

    fun startWatching() {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "startWatching() called")
        Log.d(TAG, "Target directory: $RECORDINGS_DIR")

        // ── Directory sanity checks ──
        val dir = File(RECORDINGS_DIR)
        Log.d(TAG, "Dir exists:      ${dir.exists()}")
        Log.d(TAG, "Dir isDirectory: ${dir.isDirectory}")
        Log.d(TAG, "Dir canRead:     ${dir.canRead()}")
        Log.d(TAG, "Dir absolutePath: ${dir.absolutePath}")

        if (!dir.exists()) {
            Log.e(TAG, "❌ Directory does not exist — check the path and All Files Access permission")
        } else if (!dir.canRead()) {
            Log.e(TAG, "❌ Directory exists but is NOT readable — All Files Access likely not granted")
        } else {
            // List everything in the folder right now
            val allFiles = dir.listFiles()
            if (allFiles == null) {
                Log.e(TAG, "❌ listFiles() returned null — permission issue or not a directory")
            } else if (allFiles.isEmpty()) {
                Log.w(TAG, "⚠️ Directory is empty — no files present at startup")
            } else {
                Log.d(TAG, "📂 Files found at startup (${allFiles.size} total):")
                allFiles.sortedBy { it.lastModified() }.forEach { f ->
                    Log.d(TAG, "   [${if (f.canRead()) "R" else "!"}] ${f.name}  size=${f.length()}b  modified=${java.util.Date(f.lastModified())}")
                }

                // Separate AMR vs non-AMR
                val amrFiles = allFiles.filter { it.name.endsWith(".amr", ignoreCase = true) }
                Log.d(TAG, "   → ${amrFiles.size} .amr file(s), ${allFiles.size - amrFiles.size} other(s)")

                // Check each AMR against the filename regex
                amrFiles.forEach { f ->
                    val matches = FILENAME_REGEX.matches(f.name)
                    Log.d(TAG, "   regex match [${if (matches) "✓" else "✗"}]: ${f.name}")
                }
            }

            // Seed seen-set so startup files are NOT re-processed.
            // Comment these two lines out to process ALL existing files on launch.
            dir.listFiles()?.forEach { seenFiles.add(it.name) }
            Log.d(TAG, "Seeded ${seenFiles.size} existing filenames into seen-set (won't be re-processed)")
        }

        Log.d(TAG, "Starting poll loop every ${POLL_INTERVAL_MS}ms")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        scope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        scope.cancel()
        Log.d(TAG, "Polling stopped")
    }

    private fun poll() {
        val dir = File(RECORDINGS_DIR)

        if (!dir.exists()) {
            Log.e(TAG, "poll(): directory missing — $RECORDINGS_DIR")
            return
        }
        if (!dir.canRead()) {
            Log.e(TAG, "poll(): directory not readable — check All Files Access permission")
            return
        }

        val allFiles = dir.listFiles()
        if (allFiles == null) {
            Log.e(TAG, "poll(): listFiles() returned null")
            return
        }

        val amrFiles = allFiles.filter { it.name.endsWith(".amr", ignoreCase = true) }
        Log.d(TAG, "poll(): ${allFiles.size} total file(s), ${amrFiles.size} AMR(s), ${seenFiles.size} already seen")

        val candidates = amrFiles.filter { it.name !in seenFiles }
        Log.d(TAG, "poll(): ${candidates.size} unseen AMR candidate(s)")

        candidates.forEach { file ->
            Log.d(TAG, "  candidate: ${file.name}  size=${file.length()}b  canRead=${file.canRead()}")

            if (file.length() < MIN_FILE_SIZE_BYTES) {
                Log.w(TAG, "  ⚠️ Skipping — too small (${file.length()}b < ${MIN_FILE_SIZE_BYTES}b), may still be recording")
                return@forEach
            }

            if (!FILENAME_REGEX.matches(file.name)) {
                Log.w(TAG, "  ⚠️ Skipping — filename does not match pattern: ${file.name}")
                Log.w(TAG, "     Expected: phone_YYYYMMDD_HHMMSS_<number>.amr")
                seenFiles.add(file.name) // don't spam the log on every poll
                return@forEach
            }

            Log.d(TAG, "  ✓ Enqueueing: ${file.name}")
            seenFiles.add(file.name)
            handleFile(file)
        }
    }

    private fun handleFile(file: File) {
        Log.d(TAG, "handleFile(): ${file.name}")

        val match = FILENAME_REGEX.matchEntire(file.name)!!
        val dateStr = match.groupValues[1]
        val timeStr = match.groupValues[2]
        val phone   = match.groupValues[3]

        Log.d(TAG, "  parsed → date=$dateStr  time=$timeStr  phone=$phone")

        val isoTimestamp = try {
            val date = LocalDate.parse(dateStr, DATE_FMT)
            val time = LocalTime.parse(timeStr, TIME_FMT)
            val ts = LocalDateTime.of(date, time)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString()
            Log.d(TAG, "  timestamp: $ts")
            ts
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Failed to parse date/time", e)
            java.time.Instant.now().toString()
        }

        val contactName  = ContactResolver.resolve(context, phone)
        val contactGroup = ContactResolver.resolveGroup(context, phone)
        Log.d(TAG, "  contact: $contactName  group: $contactGroup")

        Log.d(TAG, "  → calling PipelineManager.enqueueCall()")
        PipelineManager.enqueueCall(
            contact      = contactName,
            contactGroup = contactGroup,
            timestamp    = isoTimestamp,
            filePath     = file.absolutePath
        )
        Log.d(TAG, "  ✓ enqueued successfully")
    }
}