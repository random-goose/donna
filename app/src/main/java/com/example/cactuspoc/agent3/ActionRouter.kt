package com.example.cactuspoc.agent3

import android.content.Context

object ActionRouter {

    fun execute(context: Context, command: AgentCommand) {

        when (command.action) {

            "SEND_REMINDER" -> {

                ReminderExecutor.sendReminder(
                    context,
                    command.message ?: "Reminder"
                )
            }

            "NOTIFY_CLASH" -> {

                NotificationExecutor.notifyClash(
                    context,
                    command.message ?: "Schedule conflict detected"
                )
            }

            "SUGGEST_PREP" -> {

                val suggestion =
                    command.prepSuggestion
                        ?: command.message
                        ?: "Preparation suggestion"

                NotificationExecutor.suggestPrep(
                    context,
                    suggestion
                )
            }

            "SET_ALARM" -> {

                val hour = command.hour ?: 8
                val minute = command.minute ?: 0

                AlarmExecutor.setAlarm(context, hour, minute)
            }
            "CREATE_EVENT" -> {

                CalendarExecutor.createEvent(
                    context,
                    command.title ?: "Event",
                    command.day ?: 1,
                    command.month ?: 1
                )
            }

            else -> {
                println("Unknown action: ${command.action}")
            }
        }
    }
}