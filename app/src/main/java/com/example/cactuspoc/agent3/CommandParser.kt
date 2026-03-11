package com.example.cactuspoc.agent3

import kotlinx.serialization.json.Json

object CommandParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(jsonString: String): AgentCommand? {
        return try {
            // Strip markdown fences if the LLM wrapped it
            val cleaned = jsonString
                .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
                .replace("```", "")
                .trim()
            json.decodeFromString<AgentCommand>(cleaned)
        } catch (e: Exception) {
            android.util.Log.e("CommandParser", "Failed to parse command: ${e.message}")
            null
        }
    }
}
