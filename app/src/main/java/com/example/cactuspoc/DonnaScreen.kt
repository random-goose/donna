package com.example.cactuspoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DonnaScreen(vm: DonnaViewModel) {
    val context = LocalContext.current
    val pipelineState by vm.pipelineState.collectAsState()
    val messages by vm.messages.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Donna", fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
        }

        // ── Pipeline status banner ────────────────────────────────────────────
        PipelineBanner(pipelineState)

        Divider()

        // ── Chat messages ─────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message)
            }

            // Inline pipeline log when reasoning is happening
            when (val s = pipelineState) {
                is PipelineState.Vectorising -> {
                    item { PipelineLogCard(s.log) }
                }
                is PipelineState.Reasoning -> {
                    item { PipelineLogCard(s.log, accent = Color(0xFF7B61FF)) }
                }
                is PipelineState.ActionsReady -> {
                    item { ActionsCard(s.actions) }
                }
                else -> {}
            }
        }

        Divider()

        // ── Input bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Donna…") },
                shape = RoundedCornerShape(50),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = input.isNotBlank(),
                onClick = {
                    vm.sendChatCommand(context, input)
                    input = ""
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun PipelineBanner(state: PipelineState) {
    val (text, color) = when (state) {
        is PipelineState.Idle          -> "Listening for recordings…" to Color(0xFF4CAF50)
        is PipelineState.Transcribing  -> "🎙️ Transcribing ${state.fileName}…" to Color(0xFF2196F3)
        is PipelineState.Extracting    -> "🔍 Extracting items…" to Color(0xFF2196F3)
        is PipelineState.Vectorising   -> "📐 Vectorising & retrieving…" to Color(0xFF9C27B0)
        is PipelineState.Reasoning     -> "🤔 Reasoning…" to Color(0xFF7B61FF)
        is PipelineState.ActionsReady  -> "✅ ${state.actions.size} action(s) ready" to Color(0xFF4CAF50)
        is PipelineState.Error         -> "❌ ${state.message}" to Color(0xFFF44336)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = color
        )
    }
}

@Composable
fun PipelineLogCard(log: List<String>, accent: Color = Color(0xFF9C27B0)) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E)
    ) {
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .padding(10.dp),
            userScrollEnabled = true
        ) {
            items(log) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFD4D4D4),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun ActionsCard(actions: List<ProposedAction>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("⚡ ${actions.size} action(s) executed", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            actions.forEach { action ->
                Text(
                    text = "• ${action.actionType}${if (action.isCrossContact) " ⚡" else ""}: ${action.notificationMessage}",
                    fontSize = 13.sp
                )
                Text(
                    text = "  ↳ ${action.reasoning}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                message.content.startsWith("⚡") -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.padding(4.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}
