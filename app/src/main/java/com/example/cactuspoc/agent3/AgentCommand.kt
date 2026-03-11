package com.example.cactuspoc.agent3

import kotlinx.serialization.Serializable

@Serializable
data class AgentCommand(
    val action: String,
    val message: String? = null,
    val prepSuggestion: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val day: Int? = null,
    val month: Int? = null,
    val title: String? = null
)
