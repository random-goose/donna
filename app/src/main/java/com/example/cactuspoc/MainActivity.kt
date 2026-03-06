package com.example.cactuspoc

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: DonnaViewModel by viewModels()
    private lateinit var watcher: CactusCallWatcher

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            Log.d("Donna", "$perm granted=$granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DonnaScreen(vm = vm)
                }
            }
        }

        requestPermissions()
        startRecordingWatcher()
        vm.initModels()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
        }

        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startRecordingWatcher() {
        // ── Change this path to match your call recorder's folder ─────────────
        val folderPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)}/CactusRecordings/CubeCallRecorder/All"
        // ─────────────────────────────────────────────────────────────────────

        File(folderPath).mkdirs()

        // Transcribe anything already sitting in the folder right now
        transcribeExistingFiles(folderPath)

        // Then watch for any new files that arrive
        watcher = CactusCallWatcher(folderPath) { filePath ->
            Log.d("Donna", "New recording detected: $filePath")
            transcribeFile(filePath)
        }
        watcher.startWatching()
    }

    /** Scans the folder and queues every existing audio file for transcription. */
    private fun transcribeExistingFiles(folderPath: String) {
        val folder = File(folderPath)
        if (!folder.exists()) {
            Log.w("Donna", "Folder does not exist: $folderPath")
            return
        }

        val audioFiles = folder.listFiles { file ->
            file.extension.lowercase() in listOf("amr", "m4a", "wav", "mp3", "ogg")
        } ?: return

        if (audioFiles.isEmpty()) {
            Log.d("Donna", "No existing recordings found in $folderPath")
            return
        }

        Log.d("Donna", "Found ${audioFiles.size} existing recording(s) — queuing transcription")

        // Sort oldest-first so they process in chronological order
        audioFiles.sortedBy { it.lastModified() }.forEach { file ->
            Log.d("Donna", "Queuing existing file: ${file.name}")
            vm.addChatMessage(Message("assistant", "📂 Found existing recording: ${file.name}"))
            transcribeFile(file.absolutePath)
        }
    }

    private fun transcribeFile(filePath: String) {
        SpeechToTextManager.transcribe(
            context = this,
            audioFile = File(filePath),
            viewModel = vm,
            contactName = "Unknown",
            contactGroup = ContactGroup.PERSONAL
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::watcher.isInitialized) watcher.stopWatching()
    }
}