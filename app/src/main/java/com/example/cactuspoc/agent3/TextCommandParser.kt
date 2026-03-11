package com.example.cactuspoc.agent3

import java.util.regex.Pattern

object TextCommandParser {

    fun parse(text: String): AgentCommand? {

        val lower = text.lowercase()

        // Match "set an alarm for 5:30pm"
        val pattern = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(am|pm)")
        val matcher = pattern.matcher(lower)

        if (lower.contains("alarm") && matcher.find()) {

            var hour = matcher.group(1).toInt()
            val minute = matcher.group(2).toInt()
            val period = matcher.group(3)

            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0

            return AgentCommand(
                action = "SET_ALARM",
                hour = hour,
                minute = minute
            )
        }

        return null
    }
}