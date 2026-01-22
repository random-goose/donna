package com.example.cactuspoc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cactus.*
import kotlinx.coroutines.launch

data class Message(
    val role: String,
    val content: String
)

class CactusViewModel : ViewModel() {

    var messages by mutableStateOf(listOf<Message>())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var currentModel by mutableStateOf<CactusModel?>(null)
        private set

    var availableModels by mutableStateOf<List<CactusModel>>(emptyList())
        private set

    private var lm: CactusLM? = null

    init {
        viewModelScope.launch {
            lm = CactusLM()

            // 🔹 fetch available models dynamically
            availableModels = lm!!.getModels()

            // pick a sensible default
            val defaultModel =
                availableModels.firstOrNull { it.slug == "qwen3-0.6" }
                    ?: availableModels.first()

            loadModel(defaultModel)
        }
    }

    fun loadModel(model: CactusModel) {
        viewModelScope.launch {
            isLoading = true

            lm?.unload()

            lm = CactusLM()

            if (!model.isDownloaded) {
                lm?.downloadModel(model.slug)
            }

            lm?.initializeModel(
                CactusInitParams(
                    model = model.slug,
                    contextSize = 2048
                )
            )

            currentModel = model
            isLoading = false
        }
    }

    fun sendMessage(text: String) {
        messages = messages + Message("user", text)
        messages = messages + Message("assistant", "")

        val assistantIndex = messages.lastIndex

        viewModelScope.launch {
            isLoading = true

            lm?.generateCompletion(
                messages = messages.map {
                    ChatMessage(it.content, it.role)
                },
                onToken = { token, _ ->
                    val updated = messages.toMutableList()
                    val current = updated[assistantIndex]
                    updated[assistantIndex] =
                        current.copy(content = current.content + token)
                    messages = updated
                }
            )

            isLoading = false
        }
    }

    override fun onCleared() {
        lm?.unload()
        super.onCleared()
    }
}


@Composable
fun ChatBubble(message: Message) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
