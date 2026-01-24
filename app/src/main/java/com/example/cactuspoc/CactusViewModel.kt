package com.example.cactuspoc

import androidx.compose.runtime.*
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

    var availableModels by mutableStateOf<List<CactusModel>>(emptyList())
        private set

    var currentModel by mutableStateOf<CactusModel?>(null)
        private set

    var isStreaming by mutableStateOf(false)
        private set

    var maxTokens by mutableStateOf(1024)
        private set

    var systemPrompt by mutableStateOf("You are a helpful assistant, your name is Donna, you only respond in English.")
        private set

    var useGpu by mutableStateOf(true)
        private set

    private var lm: CactusLM? = null

    init {
        viewModelScope.launch {
            lm = CactusLM()
            availableModels = lm!!.getModels()

            val default =
                availableModels.firstOrNull { it.slug == "qwen3-0.6" }
                    ?: availableModels.first()

            loadModel(default)
        }
    }

    fun loadModel(model: CactusModel) {
        viewModelScope.launch {
            isStreaming = false
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
        }
    }

    fun sendMessage(text: String) {
        messages = messages + Message("user", text)
        messages = messages + Message("assistant", "")

        val assistantIndex = messages.lastIndex

        viewModelScope.launch {
            isStreaming = true

            lm?.generateCompletion(
                messages = buildList {
                    add(ChatMessage(systemPrompt, "system"))
                    addAll(messages.map { ChatMessage(it.content, it.role) })
                },
                params = CactusCompletionParams(
                    maxTokens = maxTokens
                ),
                onToken = { token, _ ->
                    if (!isStreaming) return@generateCompletion
                    val updated = messages.toMutableList()
                    val cur = updated[assistantIndex]
                    updated[assistantIndex] =
                        cur.copy(content = cur.content + token)
                    messages = updated
                }
            )

            isStreaming = false
        }
    }

    fun stopGeneration() {
        isStreaming = false
        lm?.unload()
    }

    fun updateMaxTokens(v: Int) { maxTokens = v }
    fun updateSystemPrompt(v: String) { systemPrompt = v }
    fun toggleGpu(v: Boolean) { useGpu = v }

    override fun onCleared() {
        lm?.unload()
        super.onCleared()
    }
}
