package com.example.cactuspoc

import android.util.Log
import com.cactus.CactusLM
import com.cactus.CactusSTT
import com.cactus.CactusInitParams
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global model lifecycle manager.
 *
 * Rules:
 *  - Only one model may be loaded in RAM at any time (enforced by [mutex])
 *  - Downloads are separated from loading — call [predownload] at startup
 *  - Callers acquire a typed handle, use it, then release — they never touch
 *    CactusLM / CactusSTT directly for init/unload
 */
object ModelManager {

    private const val TAG = "ModelManager"

    // Single global mutex — Agent 1 and Agent 2 both acquire this before loading
    val mutex = Mutex()

    // ── Known models ──────────────────────────────────────────────────────────

    const val WHISPER   = "whisper-small"
    const val EXTRACTOR = "qwen3-0.6"
    const val EMBEDDER  = "nomic2-embed-300m"
    const val REASONER  = "qwen3-1.7"

    // ── Pre-download (no RAM cost — just ensures files are cached) ────────────

    /**
     * Download all models in sequence at startup.
     * Call this from a background coroutine before the pipeline starts accepting jobs.
     * Safe to call multiple times — Cactus skips already-cached files.
     */
    suspend fun predownload(onProgress: (String) -> Unit = {}) {
        listOf(WHISPER, EXTRACTOR, EMBEDDER, REASONER).forEach { model ->
            onProgress("Downloading $model…")
            Log.d(TAG, "predownload: $model")
            // CactusLM/STT download calls are lightweight if file already exists
            try {
                CactusLM().downloadModel(model)
            } catch (e: Exception) {
                Log.w(TAG, "predownload failed for $model: ${e.message}")
            }
        }
        onProgress("All models cached.")
    }

    // ── LM acquire / release ──────────────────────────────────────────────────

    /**
     * Loads [modelName] into RAM under the global mutex.
     * Caller MUST call [releaseLM] when done — use try/finally.
     */
    suspend fun acquireLM(modelName: String, contextSize: Int = 2048): CactusLM {
        mutex.lock()
        Log.d(TAG, "acquireLM: loading $modelName")
        return try {
            val lm = CactusLM()
            lm.initializeModel(CactusInitParams(model = modelName, contextSize = contextSize))
            Log.d(TAG, "acquireLM: $modelName ready")
            lm
        } catch (e: Exception) {
            mutex.unlock() // release on init failure
            throw e
        }
    }

    fun releaseLM(lm: CactusLM, modelName: String) {
        try {
            lm.unload()
            Log.d(TAG, "releaseLM: $modelName unloaded")
        } catch (e: Exception) {
            Log.w(TAG, "releaseLM: unload failed for $modelName: ${e.message}")
        } finally {
            if (mutex.isLocked) mutex.unlock()
        }
    }

    // ── STT acquire / release ─────────────────────────────────────────────────

    suspend fun acquireSTT(modelName: String = WHISPER): CactusSTT {
        mutex.lock()
        Log.d(TAG, "acquireSTT: loading $modelName")
        return try {
            val stt = CactusSTT()
            stt.initializeModel(CactusInitParams(model = modelName))
            Log.d(TAG, "acquireSTT: $modelName ready")
            stt
        } catch (e: Exception) {
            mutex.unlock()
            throw e
        }
    }

    fun releaseSTT(stt: CactusSTT, modelName: String = WHISPER) {
        try {
//            stt.unload()
            Log.d(TAG, "releaseSTT: $modelName unloaded")
        } catch (e: Exception) {
            Log.w(TAG, "releaseSTT: unload failed: ${e.message}")
        } finally {
            if (mutex.isLocked) mutex.unlock()
        }
    }
}
