package com.example.cactuspoc.agent3

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.*

object CalendarExecutor {

    fun createEvent(context: Context, title: String, day: Int, month: Int) {

        val calendar = Calendar.getInstance()

        calendar.set(Calendar.MONTH, month - 1)
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, 9)
        calendar.set(Calendar.MINUTE, 0)

        val startMillis = calendar.timeInMillis
        val endMillis = startMillis + 60 * 60 * 1000

        val values = ContentValues().apply {

            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        context.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            values
        )
    }
}