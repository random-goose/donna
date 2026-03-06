package com.example.cactuspoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object ReminderExecutor {

    private const val CHANNEL_ID = "DONNA_REMINDER"

    fun sendReminder(context: Context, message: String) {

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Donna Reminders",
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Reminder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        manager.notify(3, notification)
    }
}