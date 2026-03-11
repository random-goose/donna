package com.example.cactuspoc

import android.content.Context
import android.util.Log
import com.cactus.CactusTranscriptionParams
import com.example.cactuspoc.agent1.AmrToWavConverter

object TranscriptionStage {

    private const val TAG = "TranscriptionStage"

    suspend fun transcribe(context: Context, amrPath: String): String? {
        val wavPath = AmrToWavConverter.convert(context, amrPath)
        if (wavPath == null) {
            Log.e(TAG, "AMR→WAV conversion failed for: $amrPath")
            return null
        }

        val stt = try {
            ModelManager.acquireSTT(ModelManager.WHISPER)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire STT model", e)
            AmrToWavConverter.deleteWav(wavPath)
            return null
        }

        return try {
            Log.d(TAG, "Transcribing: $wavPath")
            val result = stt.transcribe(filePath = wavPath, params = CactusTranscriptionParams())
            Log.d(TAG, "Transcription complete: ${result?.text?.take(80)}…")
            result?.text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        } finally {
            ModelManager.releaseSTT(stt, ModelManager.WHISPER)
            AmrToWavConverter.deleteWav(wavPath)
        }
    }
}