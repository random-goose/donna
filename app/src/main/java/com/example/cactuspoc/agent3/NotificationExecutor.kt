package com.example.cactuspoc.agent3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationExecutor {

    private val notifId = java.util.concurrent.atomic.AtomicInteger(100)

    private const val CHANNEL_ID = "DONNA_CHANNEL"

    private fun createChannel(context: Context) {

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Donna Notifications",
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(channel)
    }

    fun notifyClash(context: Context, message: String) {

        createChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Schedule Conflict")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(notifId.getAndIncrement(), notification)
    }

    fun suggestPrep(context: Context, suggestion: String) {

        createChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Donna Suggestion")
            .setContentText(suggestion)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(notifId.getAndIncrement(), notification)
    }
}