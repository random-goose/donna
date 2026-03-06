package com.example.cactuspoc

import android.util.Log
import com.cactus.CactusLM
import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "ExtractionStage"
private const val LM_MODEL = "qwen3-1.7"

/** Intermediate JSON shape returned by the LLM. */
@Serializable
private data class LlmOutput(
    val actionable: List<String> = emptyList(),
    val contextual: List<String> = emptyList()
)

object ExtractionStage {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val SYSTEM_PROMPT = """
        You are an information extraction assistant. 
        Given the text of a phone call transcript or notification message, extract:
        - actionable: items that require an action (e.g. meetings, deadlines, tasks, follow-ups). Each item is a single plain sentence.
        - contextual: background facts with no required action (e.g. plans someone mentioned, preferences, updates). Each item is a single plain sentence.
        
        Respond ONLY with valid JSON in this exact format, no markdown, no explanation, no preamble:
        {"actionable": ["..."], "contextual": ["..."]}
        
        If there are no actionable items, return an empty list for "actionable".
        If there are no contextual items, return an empty list for "contextual".
    """.trimIndent()

    /**
     * Runs the LLM extraction stage on a transcript or notification body.
     * Returns a pair of (actionable, contextual) lists, or null on failure.
     */
    suspend fun extract(inputText: String): Pair<List<String>, List<String>>? {
        if (inputText.isBlank()) {
            Log.w(TAG, "Empty input text, skipping extraction")
            return Pair(emptyList(), emptyList())
        }

        val lm = CactusLM()
        return try {
            Log.d(TAG, "Downloading/verifying LM model...")
            lm.downloadModel(LM_MODEL)
            lm.initializeModel(CactusInitParams(model = LM_MODEL, contextSize = 2048))

            val response = StringBuilder()

            lm.generateCompletion(
                messages = listOf(
                    ChatMessage(content = SYSTEM_PROMPT, role = "system"),
                    ChatMessage(content = inputText, role = "user")
                ),
                params = CactusCompletionParams(
                    temperature = 0.1,
                    maxTokens = 512
                ),
                onToken = { token, _ -> response.append(token) }
            )

            val rawResponse = response.toString().trim()
            Log.d(TAG, "LLM raw response: $rawResponse")

            parseResponse(rawResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        } finally {
            try { lm.unload() } catch (_: Exception) {}
        }
    }

    private fun parseResponse(raw: String): Pair<List<String>, List<String>>? {
        // Strip any accidental markdown fences
        val cleaned = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val parsed = json.decodeFromString<LlmOutput>(cleaned)
            Pair(parsed.actionable, parsed.contextual)
        } catch (e: Exception) {
            // Attempt to locate JSON object within a larger string
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start != -1 && end > start) {
                try {
                    val jsonSubstring = cleaned.substring(start, end + 1)
                    val parsed = json.decodeFromString<LlmOutput>(jsonSubstring)
                    Pair(parsed.actionable, parsed.contextual)
                } catch (e2: Exception) {
                    Log.e(TAG, "JSON parse failed after fallback extraction", e2)
                    null
                }
            } else {
                Log.e(TAG, "Could not find JSON in LLM response: $cleaned", e)
                null
            }
        }
    }
}
