package com.example.cactuspoc

import com.google.gson.Gson

object CommandParser {

    private val gson = Gson()

    fun parse(json: String): AgentCommand {
        return gson.fromJson(json, AgentCommand::class.java)
    }
}