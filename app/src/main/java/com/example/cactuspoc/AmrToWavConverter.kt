package com.example.cactuspoc

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

object AmrToWavConverter {

    private const val TAG = "AmrToWavConverter"

    /**
     * Converts an AMR file to a 16kHz mono WAV file in the app's cache directory.
     * Returns the path to the WAV file, or null on failure.
     */
    fun convert(context: Context, amrPath: String): String? {
        val amrFile = File(amrPath)
        if (!amrFile.exists()) {
            Log.e(TAG, "AMR file not found: $amrPath")
            return null
        }

        val outputWavPath = File(context.cacheDir, "${amrFile.nameWithoutExtension}_${System.currentTimeMillis()}.wav").absolutePath

        val cmd = "-y -i \"$amrPath\" -ar 16000 -ac 1 -c:a pcm_s16le \"$outputWavPath\""
        Log.d(TAG, "Running FFmpeg: $cmd")

        val session = FFmpegKit.execute(cmd)

        return if (session.returnCode.isValueSuccess) {
            Log.d(TAG, "Conversion successful: $outputWavPath")
            outputWavPath
        } else {
            Log.e(TAG, "FFmpeg failed: ${session.allLogsAsString}")
            null
        }
    }

    /** Deletes the WAV file after transcription is complete. */
    fun deleteWav(wavPath: String) {
        val file = File(wavPath)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted WAV: $wavPath")
        }
    }
}
