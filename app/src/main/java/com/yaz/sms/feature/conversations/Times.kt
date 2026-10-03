package com.yaz.sms.feature.conversations

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Today the time, this week the day, else the date. */
fun timeLabel(context: Context, millis: Long, today: LocalDate = LocalDate.now()): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day == today -> DateFormat.getTimeFormat(context).format(Date(millis))
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}

/** The day above its messages: Today, Yesterday, Monday, 12 September. */
fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
    else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
}

/** A time ahead: Today, 8:00 PM; Tomorrow, 8:00 AM; else the date and the hour. */
fun aheadLabel(context: Context, millis: Long, today: LocalDate = LocalDate.now()): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val time = DateFormat.getTimeFormat(context).format(Date(millis))
    return when (day) {
        today -> "Today, $time"
        today.plusDays(1) -> "Tomorrow, $time"
        else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + ", $time"
    }
}
