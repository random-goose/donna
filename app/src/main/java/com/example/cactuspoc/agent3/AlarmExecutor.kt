package com.example.cactuspoc.agent3

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object AlarmExecutor {

    fun setAlarm(context: Context, hour: Int, minute: Int) {

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {

            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Donna Alarm")

            // ensures alarm screen appears
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        context.startActivity(intent)
    }
}