package com.example.cactuspoc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: CactusViewModel = viewModel()) {

    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cactus Chat") },
                actions = {
                    Box {
                        TextButton(
                            enabled = !vm.isLoading && vm.currentModel != null,
                            onClick = { expanded = true }
                        ) {
                            Text(vm.currentModel?.name ?: "Loading…")
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            vm.availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.name)
                                            Text(
                                                "${model.size_mb} MB" +
                                                        if (model.supports_vision) " • Vision" else "",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    },
                                    onClick = {
                                        expanded = false
                                        vm.loadModel(model)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                items(vm.messages) { msg ->
                    ChatBubble(msg)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            vm.sendMessage(input)
                            input = ""
                        }
                    },
                    enabled = !vm.isLoading
                ) {
                    Text("Send")
                }
            }
        }
    }
}
