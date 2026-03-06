package com.example.cactuspoc

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.llamatik.library.platform.WhisperBridge
import java.io.File

object SpeechToTextManager {

    private const val TAG = "DonnaSTT"

    /**
     * Transcribes [audioFile] using Whisper, then hands the transcript
     * to [DonnaViewModel] to drive the full Agent 2 + 3 pipeline.
     *
     * @param contactName  name of the person the call was with
     * @param contactGroup WORK or PERSONAL
     */
    fun transcribe(
        context: Context,
        audioFile: File,
        viewModel: DonnaViewModel,
        contactName: String = "Unknown",
        contactGroup: ContactGroup = ContactGroup.PERSONAL
    ) {
        Thread {
            try {
                Log.d(TAG, "Transcribing: ${audioFile.absolutePath} (${audioFile.length()} bytes)")

                if (audioFile.length() == 0L) {
                    Log.e(TAG, "Audio file is empty"); return@Thread
                }

                // Step 1 — Convert to 16kHz mono PCM WAV (required by Whisper)
                val wavFile = File(context.cacheDir, audioFile.nameWithoutExtension + ".wav")
                val cmd = "-y -i \"${audioFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le " +
                        "-af \"highpass=f=200,lowpass=f=3000,loudnorm\" \"${wavFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                if (!session.returnCode.isValueSuccess) {
                    Log.e(TAG, "FFmpeg failed: ${session.allLogsAsString}"); return@Thread
                }

                if (wavFile.length() == 0L) {
                    Log.e(TAG, "Converted WAV is empty"); return@Thread
                }

                // Step 2 — Load Whisper model
                val modelPath = copyModel(context)
                WhisperBridge.initModel(modelPath)

                // Step 3 — Transcribe
                val transcript = WhisperBridge.transcribeWav(wavFile.absolutePath, "en")?.trim() ?: ""
                Log.d(TAG, "TRANSCRIPT: $transcript")

                if (transcript.isEmpty() || transcript == "[BLANK_AUDIO]") {
                    Log.w(TAG, "Empty transcript — skipping pipeline"); return@Thread
                }

                // Step 4 — Hand off to full pipeline (Agents 2 + 3)
                viewModel.onTranscriptReceived(context, transcript, contactName, contactGroup)

            } catch (e: Exception) {
                Log.e(TAG, "STT error: ${e.message}", e)
            }
        }.start()
    }

    private fun copyModel(context: Context): String {
        val modelFile = File(context.filesDir, "whisper-tiny.bin")
        if (!modelFile.exists()) {
            context.assets.open("whisper-tiny.bin").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return modelFile.absolutePath
    }
}
