package com.example.cactuspoc

import java.util.Calendar

object TimeUtils {

    fun calculateAlarmTime(hour: Int, minute: Int, dayOffset: Int): Long {

        val calendar = Calendar.getInstance()

        calendar.add(Calendar.DAY_OF_YEAR, dayOffset)

        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }
}