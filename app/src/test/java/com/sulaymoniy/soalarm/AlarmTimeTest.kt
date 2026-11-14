package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.alarm.AlarmTime
import com.sulaymoniy.soalarm.data.Alarm
import com.sulaymoniy.soalarm.data.Days
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTimeTest {
    private val zone = ZoneId.of("Asia/Tashkent")

    // Saturday, 14 Nov 2026, 21:53
    private val now = ZonedDateTime.of(2026, 11, 14, 21, 53, 0, 0, zone)

    @Test
    fun onceLaterToday() {
        val next = AlarmTime.nextTrigger(Alarm(hour = 22, minute = 0), now)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 22, 0, 0, 0, zone), next)
        assertEquals("Rings in 7 min", AlarmTime.ringsInText(next, now))
    }

    @Test
    fun onceEarlierMovesToTomorrow() {
        val next = AlarmTime.nextTrigger(Alarm(hour = 7, minute = 30), now)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 7, 30, 0, 0, zone), next)
        assertEquals("Rings in 9 h 37 min", AlarmTime.ringsInText(next, now))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val next = AlarmTime.nextTrigger(Alarm(hour = 7, minute = 0, days = Days.WEEKDAYS), now)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(16, next.dayOfMonth)
    }

    @Test
    fun ringingAlarmIsNotPickedAgain() {
        val ringingNow = now.withHour(22).withMinute(0)
        val next = AlarmTime.nextTrigger(
            Alarm(hour = 22, minute = 0, days = Days.EVERY_DAY),
            ringingNow.plusMinutes(1),
        )
        assertEquals(15, next.dayOfMonth)
    }
}
