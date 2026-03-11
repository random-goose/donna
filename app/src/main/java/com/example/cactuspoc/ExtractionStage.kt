package com.example.cactuspoc

import android.util.Log
import com.cactus.CactusCompletionParams
import com.cactus.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "ExtractionStage"

@Serializable
private data class LlmOutput(
    val actionable: List<String> = emptyList(),
    val contextual: List<String> = emptyList()
)

object ExtractionStage {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SYSTEM_PROMPT = """
        You are an information extraction assistant.
        Given the text of a phone call transcript or notification message, extract:
        - actionable: items that require an action. Each item is a single plain sentence.
        - contextual: background facts with no required action. Each item is a single plain sentence.

        Respond ONLY with valid JSON in this exact format, no markdown, no explanation, no preamble:
        {"actionable": ["..."], "contextual": ["..."]}

        If there are no actionable items, return an empty list for "actionable".
        If there are no contextual items, return an empty list for "contextual".
    """.trimIndent()

    suspend fun extract(inputText: String): Pair<List<String>, List<String>>? {
        if (inputText.isBlank()) return Pair(emptyList(), emptyList())

        val lm = try {
            ModelManager.acquireLM(ModelManager.EXTRACTOR, contextSize = 2048)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire LM model", e)
            return null
        }

        return try {
            val response = StringBuilder()
            lm.generateCompletion(
                messages = listOf(
                    ChatMessage(content = SYSTEM_PROMPT, role = "system"),
                    ChatMessage(content = inputText,     role = "user")
                ),
                params  = CactusCompletionParams(temperature = 0.1, maxTokens = 512),
                onToken = { token, _ -> response.append(token) }
            )
            Log.d(TAG, "LLM raw response: ${response.take(200)}")
            parseResponse(response.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        } finally {
            ModelManager.releaseLM(lm, ModelManager.EXTRACTOR)
        }
    }

    private fun parseResponse(raw: String): Pair<List<String>, List<String>>? {
        val cleaned = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val parsed = json.decodeFromString<LlmOutput>(cleaned)
            Pair(parsed.actionable, parsed.contextual)
        } catch (e: Exception) {
            val start = cleaned.indexOf('{')
            val end   = cleaned.lastIndexOf('}')
            if (start != -1 && end > start) {
                try {
                    val parsed = json.decodeFromString<LlmOutput>(cleaned.substring(start, end + 1))
                    Pair(parsed.actionable, parsed.contextual)
                } catch (e2: Exception) {
                    Log.e(TAG, "JSON parse failed", e2)
                    null
                }
            } else {
                Log.e(TAG, "No JSON found in response: $cleaned")
                null
            }
        }
    }
}
