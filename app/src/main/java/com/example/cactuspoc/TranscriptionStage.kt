package com.example.cactuspoc

import android.content.Context
import android.util.Log
import com.cactus.CactusInitParams
import com.cactus.CactusSTT
import com.cactus.CactusTranscriptionParams
import com.example.cactuspoc.AmrToWavConverter

object TranscriptionStage {

    private const val TAG = "TranscriptionStage"
    private const val WHISPER_MODEL = "whisper-small"

    /**
     * Transcribes an AMR call recording.
     * 1. Converts AMR → WAV (16kHz mono) via FFmpegKit
     * 2. Downloads + initialises Whisper model
     * 3. Transcribes the WAV
     * 4. Unloads the model and deletes the temp WAV
     *
     * Returns the transcript string, or null on failure.
     */
    suspend fun transcribe(context: Context, amrPath: String): String? {
        // Step 1: AMR → WAV
        val wavPath = AmrToWavConverter.convert(context, amrPath)
        if (wavPath == null) {
            Log.e(TAG, "AMR→WAV conversion failed for: $amrPath")
            return null
        }

        val stt = CactusSTT()
        return try {
            // Step 2: Ensure model is available
            Log.d(TAG, "Downloading/verifying Whisper model...")
            stt.downloadModel(WHISPER_MODEL)

            // Step 3: Initialise
            stt.initializeModel(CactusInitParams(model = WHISPER_MODEL))

            // Step 4: Transcribe
            Log.d(TAG, "Transcribing: $wavPath")
            val result = stt.transcribe(
                filePath = wavPath,
                params = CactusTranscriptionParams()
            )

            val text = result?.text
            Log.d(TAG, "Transcription complete: ${text?.take(80)}...")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        } finally {
            // Step 5: Always unload before returning (so LLM can load next)
//            try { stt.unload()
//            } catch (_: Exception) {}
            // Delete temp WAV
            AmrToWavConverter.deleteWav(wavPath)
        }
    }
}
