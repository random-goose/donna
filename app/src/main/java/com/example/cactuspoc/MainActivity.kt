package com.example.cactuspoc

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import com.cactus.CactusContextInitializer
import com.example.cactuspoc.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Permissions ────────────────────────────────────────────────────────────

private val RUNTIME_PERMISSIONS = buildList {
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        @Suppress("DEPRECATION")
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

// ─── Activity ────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private lateinit var callFileWatcher: CallFileWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be first — initialises native Cactus context
        CactusContextInitializer.initialize(this)
        PipelineManager.start(this)
        callFileWatcher = CallFileWatcher(this)   // init first
        callFileWatcher.startWatching()           // then call


        callFileWatcher = CallFileWatcher(this)

        setContent {
            CactusTheme {
                MainScreen(
                    onWatcherStart = { callFileWatcher.startWatching() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callFileWatcher.stopWatching()
    }
}

// ─── Theme ───────────────────────────────────────────────────────────────────

private val BgDeep     = Color(0xFF050A0E)
private val BgCard     = Color(0xFF0D1318)
private val BgCardAlt  = Color(0xFF111820)
private val AccentGreen = Color(0xFF00E5A0)
private val AccentAmber = Color(0xFFFFB547)
private val AccentRed   = Color(0xFFFF5C5C)
private val AccentBlue  = Color(0xFF4FC3F7)
private val TextPrimary = Color(0xFFE8F0F5)
private val TextMuted   = Color(0xFF5C7A8A)
private val BorderColor = Color(0xFF1A2830)

@Composable
fun CactusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgDeep,
            surface = BgCard,
            primary = AccentGreen,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        ),
        content = content
    )
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen(onWatcherStart: () -> Unit) {
    val context = LocalContext.current
    val jobs by PipelineManager.jobs.collectAsState()
    val logLines by PipelineManager.log.collectAsState()
    val scope = rememberCoroutineScope()

    // Permission states
    var runtimeGranted by remember { mutableStateOf(checkRuntimePermissions(context)) }
    var notifListenerGranted by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var storageGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.os.Environment.isExternalStorageManager()
            else true
        )
    }

    // Start watcher once all permissions in
    LaunchedEffect(runtimeGranted, notifListenerGranted, storageGranted) {
        if (runtimeGranted && notifListenerGranted && storageGranted) {
            onWatcherStart()
        }
    }

    // Re-check on resume
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val runnable = Runnable {
            runtimeGranted = checkRuntimePermissions(context)
            notifListenerGranted = isNotificationListenerEnabled(context)
            storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.os.Environment.isExternalStorageManager() else true
        }
        // Poll every 2s while composable is active (simple resume-check alternative)
        val job = scope.launch {
            while (true) { delay(2000); runnable.run() }
        }
        onDispose { job.cancel() }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { runtimeGranted = it.values.all { v -> v } }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        // Subtle grid background
        Canvas(Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            val cols = (size.width / step).toInt() + 1
            val rows = (size.height / step).toInt() + 1
            for (c in 0..cols) drawLine(BorderColor.copy(alpha = 0.3f), Offset(c * step, 0f), Offset(c * step, size.height), 0.5f)
            for (r in 0..rows) drawLine(BorderColor.copy(alpha = 0.3f), Offset(0f, r * step), Offset(size.width, r * step), 0.5f)
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // ── Header ──
            Header()

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Permissions ──
                item {
                    PermissionsCard(
                        runtimeGranted = runtimeGranted,
                        notifListenerGranted = notifListenerGranted,
                        storageGranted = storageGranted,
                        onGrantRuntime = { runtimeLauncher.launch(RUNTIME_PERMISSIONS.toTypedArray()) },
                        onGrantNotifListener = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onGrantStorage = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"))
                                )
                            }
                        }
                    )
                }

                // ── Pipeline Jobs ──
                item {
                    SectionLabel("PIPELINE  ·  ${jobs.size} jobs")
                }

                if (jobs.isEmpty()) {
                    item { EmptyState("Waiting for calls & notifications…") }
                } else {
                    items(jobs.asReversed(), key = { it.id }) { job ->
                        JobCard(job)
                    }
                }

                // ── Log ──
                item {
                    SectionLabel("SYSTEM LOG")
                }

                if (logLines.isEmpty()) {
                    item { EmptyState("No log entries yet.") }
                } else {
                    items(logLines.asReversed().take(60)) { line ->
                        LogLine(line)
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
fun Header() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(AccentGreen.copy(alpha = pulse), shape = RoundedCornerShape(50))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "DONNA_PROTOTYPE",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Agent 1",
            color = AccentGreen,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )
    }
    HorizontalDivider(color = BorderColor, thickness = 1.dp)
}

// ─── Permissions Card ────────────────────────────────────────────────────────

@Composable
fun PermissionsCard(
    runtimeGranted: Boolean,
    notifListenerGranted: Boolean,
    storageGranted: Boolean,
    onGrantRuntime: () -> Unit,
    onGrantNotifListener: () -> Unit,
    onGrantStorage: () -> Unit
) {
    val allGranted = runtimeGranted && notifListenerGranted && storageGranted
    val borderColor = if (allGranted) AccentGreen.copy(alpha = 0.4f) else AccentAmber.copy(alpha = 0.4f)

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PERMISSIONS",
                    color = TextMuted,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (allGranted) {
                    Text("ALL CLEAR", color = AccentGreen, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }

            PermissionRow(
                label = "Contacts / Audio / Storage",
                subLabel = "READ_CONTACTS, RECORD_AUDIO, media access",
                granted = runtimeGranted,
                onGrant = onGrantRuntime
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionRow(
                    label = "All Files Access",
                    subLabel = "Required to read call recordings",
                    granted = storageGranted,
                    onGrant = onGrantStorage
                )
            }

            PermissionRow(
                label = "Notification Listener",
                subLabel = "WhatsApp, Telegram, Slack, Gmail, Teams",
                granted = notifListenerGranted,
                onGrant = onGrantNotifListener
            )
        }
    }
}

@Composable
fun PermissionRow(
    label: String,
    subLabel: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(BgCardAlt, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (granted) AccentGreen else AccentAmber,
                    RoundedCornerShape(50)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subLabel, color = TextMuted, fontSize = 11.sp)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("GRANT", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        } else {
            Text("✓", color = AccentGreen, fontSize = 14.sp)
        }
    }
}

// ─── Job Card ────────────────────────────────────────────────────────────────

@Composable
fun JobCard(job: PipelineJob) {
    val statusColor = when (job.status) {
        JobStatus.QUEUED       -> TextMuted
        JobStatus.TRANSCRIBING -> AccentBlue
        JobStatus.EXTRACTING   -> AccentAmber
        JobStatus.DONE         -> AccentGreen
        JobStatus.FAILED       -> AccentRed
    }

    val isActive = job.status == JobStatus.TRANSCRIBING || job.status == JobStatus.EXTRACTING
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, statusColor.copy(alpha = if (isActive) blinkAlpha * 0.5f else 0.2f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ── Top row ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(job.type)
                Spacer(Modifier.width(8.dp))
                Text(
                    job.contact,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(job.status, statusColor)
            }

            // ── Metadata row ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(job.contactGroup)
                Chip(job.timestamp.take(10)) // date portion
            }

            // ── Results ──
            val result = job.result
            if (result != null) {
                if (result.actionable.isNotEmpty()) {
                    ResultSection("⚡ ACTIONABLE", result.actionable, AccentAmber)
                }
                if (result.contextual.isNotEmpty()) {
                    ResultSection("◈ CONTEXTUAL", result.contextual, AccentBlue)
                }
            }

            if (job.status == JobStatus.FAILED) {
                Text(
                    "✗  ${job.errorMessage}",
                    color = AccentRed,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentRed.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun ResultSection(label: String, items: List<String>, accent: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("·", color = accent, fontSize = 13.sp)
                Text(item, color = TextPrimary.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun TypeBadge(type: String) {
    val (label, color) = if (type == "call") "CALL" to AccentGreen else "NOTIF" to AccentBlue
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun StatusBadge(status: JobStatus, color: Color) {
    val label = status.name
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun Chip(text: String) {
    Box(
        Modifier
            .background(BgCardAlt, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = TextMuted, fontSize = 10.sp)
    }
}

// ─── Log ────────────────────────────────────────────────────────────────────

@Composable
fun LogLine(line: String) {
    Text(
        line,
        color = TextMuted.copy(alpha = 0.8f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun EmptyState(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(BgCard, RoundedCornerShape(10.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}

// ─── Permission helpers ──────────────────────────────────────────────────────

private fun checkRuntimePermissions(context: android.content.Context): Boolean =
    RUNTIME_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (flat.isNullOrEmpty()) return false
    val componentName = ComponentName(context, NotificationIngester::class.java)
    return flat.split(":").any {
        try { ComponentName.unflattenFromString(it) == componentName } catch (_: Exception) { false }
    }
}