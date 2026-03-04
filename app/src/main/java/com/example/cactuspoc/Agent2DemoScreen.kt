package com.example.cactuspoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun Agent2DemoScreen(vm: Agent2ViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom as log grows
    val logLines = when (val s = state) {
        is DemoState.Processing -> s.log
        is DemoState.Done       -> s.log
        else                    -> emptyList()
    }
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Text("Agent 2 Demo", style = MaterialTheme.typography.titleLarge)
        Text("Vectorise → Store → Retrieve → Reason → Act",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))

        when (val s = state) {

            is DemoState.Idle -> {
                Button(
                    onClick = vm::runDemo,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶  Run Agent 2 Demo") }
            }

            is DemoState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Text(s.message, modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            is DemoState.Processing -> LogPanel(logLines = s.log, listState = listState, modifier = Modifier.weight(1f))

            is DemoState.Done -> {
                LogPanel(logLines = s.log, listState = listState, modifier = Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                Text("✅ Done — ${s.actions.size} action(s) proposed",
                    color = MaterialTheme.colorScheme.primary)
            }

            is DemoState.Error -> {
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::runDemo) { Text("Retry") }
            }
        }
    }
}

@Composable
fun LogPanel(
    logLines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(logLines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFD4D4D4),
                    lineHeight = 18.sp
                )
            }
        }
    }
}
