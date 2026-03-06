package com.example.cactuspoc

import android.content.Context

object ActionRouter {

    fun execute(context: Context, command: AgentCommand) {
        when (command.action) {

            "SEND_REMINDER" ->
                ReminderExecutor.sendReminder(context, command.message ?: "Reminder")

            "NOTIFY_CLASH" ->
                NotificationExecutor.notifyClash(context, command.message ?: "Schedule conflict detected")

            "SUGGEST_PREP" ->
                NotificationExecutor.suggestPrep(context, command.prepSuggestion ?: command.message ?: "Preparation suggestion")

            "SET_ALARM" ->
                AlarmExecutor.setAlarm(context, command.hour ?: 8, command.minute ?: 0)

            "CREATE_EVENT" ->
                CalendarExecutor.createEvent(context, command.title ?: "Event", command.day ?: 1, command.month ?: 1)

            else ->
                android.util.Log.w("Donna", "Unknown action: ${command.action}")
        }
    }
}
