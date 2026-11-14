package com.sulaymoniy.soalarm.alarm

import android.content.Context
import android.text.format.DateFormat
import com.sulaymoniy.soalarm.data.Alarm
import com.sulaymoniy.soalarm.data.Days
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Time math and human-friendly text for alarms. */
object AlarmTime {

    /** The next moment strictly after [from] when [alarm] should ring. */
    fun nextTrigger(alarm: Alarm, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val time = LocalTime.of(alarm.hour, alarm.minute)
        for (offset in 0L..7L) {
            val date = from.toLocalDate().plusDays(offset)
            val candidate = ZonedDateTime.of(date, time, from.zone)
            if (!candidate.isAfter(from)) continue
            if (!alarm.repeats || Days.has(alarm.days, candidate.dayOfWeek)) return candidate
        }
        // Unreachable: within 8 days every weekday appears.
        return from.plusDays(1)
    }

    /** "Rings in 7 h 30 min" */
    fun ringsInText(trigger: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): String {
        val totalMinutes = Duration.between(now, trigger).toMinutes().let {
            // Round up so 7 h 29 min 30 s reads as "7 h 30 min".
            if (Duration.between(now, trigger).seconds % 60 > 0) it + 1 else it
        }
        if (totalMinutes < 1) return "Rings in less than a minute"
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        val parts = buildList {
            if (days > 0) add("$days d")
            if (hours > 0 || days > 0) add("$hours h")
            add("$minutes min")
        }
        return "Rings in " + parts.joinToString(" ")
    }

    fun formatTime(context: Context, hour: Int, minute: Int): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun daysText(mask: Int): String = when (mask) {
        0 -> "Once"
        Days.EVERY_DAY -> "Every day"
        Days.WEEKDAYS -> "Weekdays"
        Days.WEEKEND -> "Weekends"
        else -> Days.ORDER.filter { Days.has(mask, it) }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}
