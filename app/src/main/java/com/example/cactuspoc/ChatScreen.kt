package com.example.cactuspoc

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs

@Composable
fun ChatScreen(vm: CactusViewModel = viewModel()) {

    val EDGE_WIDTH = 80.dp
    val EDGE_PX = with(LocalDensity.current) { EDGE_WIDTH.toPx() }

    var leftOpen by remember { mutableStateOf(false) }
    var rightOpen by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
            .pointerInput(Unit) {
                detectTapGestures {
                    // Tap anywhere closes panels
                    leftOpen = false
                    rightOpen = false
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        // handled in onDrag
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        val x = change.position.x

                        // LEFT EDGE gestures
                        if (x < EDGE_PX) {
                            if (dragAmount > 20) {
                                // swipe right → open left
                                leftOpen = true
                                rightOpen = false
                            } else if (dragAmount < -20) {
                                // swipe left → close left
                                leftOpen = false
                            }
                        }

                        // RIGHT EDGE gestures
                        if (x > size.width - EDGE_PX) {
                            if (dragAmount < -20) {
                                // swipe left → open right
                                rightOpen = true
                                leftOpen = false
                            } else if (dragAmount > 20) {
                                // swipe right → close right
                                rightOpen = false
                            }
                        }
                    }
                )
            }
    ) {

        if (leftOpen) {
            LeftSidebar()
        }

        // ===== CENTER CHAT COLUMN =====
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {

            // 🔹 Top bar / logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cactus Chat",
                    fontSize = 20.sp,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Divider()

            // 🔹 Chat messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(vm.messages) {
                    ChatBubble(it)
                }
            }

            Divider()

            // 🔹 Input bar (fixed + clean)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(50),
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(Modifier.width(8.dp))

                if (vm.isStreaming) {
                    IconButton(onClick = vm::stopGeneration) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            vm.sendMessage(input)
                            input = ""
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }

        if (rightOpen) {
            RightSidebar(vm)
        }
    }
}
