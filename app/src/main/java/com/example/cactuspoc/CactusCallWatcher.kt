package com.example.cactuspoc

import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * Watches a folder for new audio recordings.
 * Fires [onNewRecording] with the full path when a .amr / .m4a / .wav appears.
 */
class CactusCallWatcher(
    private val folderPath: String,
    private val onNewRecording: (String) -> Unit
) {

    private var observer: FileObserver? = null

    fun startWatching() {
        val folder = File(folderPath)
        if (!folder.exists()) {
            Log.e("CactusWatcher", "Folder does not exist: $folderPath")
            return
        }

        observer = object : FileObserver(folderPath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if ((event == CLOSE_WRITE || event == MOVED_TO) && path != null) {
                    val fullPath = "$folderPath/$path"
                    Log.d("CactusWatcher", "New file: $fullPath")
                    if (path.endsWith(".amr") || path.endsWith(".m4a") || path.endsWith(".wav")) {
                        onNewRecording(fullPath)
                    }
                }
            }
        }

        observer?.startWatching()
        Log.d("CactusWatcher", "Watching: $folderPath")
    }

    fun stopWatching() {
        observer?.stopWatching()
    }
}
