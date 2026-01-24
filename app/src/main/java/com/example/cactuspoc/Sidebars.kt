package com.example.cactuspoc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun LeftSidebar() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
        ) {
            Text("Chats", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {}) {
                Text("New Chat")
            }
        }
    }
}

private val TOKEN_OPTIONS = listOf(
    216,
    1024,
    2048,
    3072,
    4096
)


@Composable
fun RightSidebar(vm: CactusViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(12.dp))



            // 🔹 Max tokens
            val sliderPosition = TOKEN_OPTIONS.indexOf(vm.maxTokens)
                .coerceAtLeast(0)

            Spacer(Modifier.height(12.dp))

            Text("Max tokens: ${vm.maxTokens}")

            Slider(
                value = sliderPosition.toFloat(),
                onValueChange = { index ->
                    val snappedIndex = index.roundToInt()
                        .coerceIn(0, TOKEN_OPTIONS.lastIndex)
                    vm.updateMaxTokens(TOKEN_OPTIONS[snappedIndex])
                },
                valueRange = 0f..(TOKEN_OPTIONS.size - 1).toFloat(),
                steps = TOKEN_OPTIONS.size - 2   // number of dots between ends
            )

            Spacer(Modifier.height(12.dp))



            // 🔹 Model selection
            Text("Model")
            vm.availableModels.forEach { model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = vm.currentModel?.slug == model.slug,
                        onClick = { vm.loadModel(model) }
                    )
                    Text(
                        text = model.name,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 🔹 System prompt
            Text("System prompt")
            TextField(
                value = vm.systemPrompt,
                onValueChange = vm::updateSystemPrompt,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            Spacer(Modifier.height(16.dp))

            // 🔹 GPU toggle
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = vm.useGpu,
                    onCheckedChange = vm::toggleGpu
                )
                Spacer(Modifier.width(8.dp))
                Text("Use GPU")
            }

            // Bottom padding so last item isn't cramped
            Spacer(Modifier.height(24.dp))
        }
    }
}
