package com.sulaymoniy.soalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek

/**
 * One alarm as stored in the database.
 *
 * [days] is a bit mask of repeat days: bit 0 = Monday ... bit 6 = Sunday.
 * 0 means "ring once".
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
) {
    val repeats: Boolean get() = days != 0
}

object Days {
    /** Monday first, the way most of the world reads a week. */
    val ORDER: List<DayOfWeek> = DayOfWeek.entries

    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERY_DAY = 0b1111111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun has(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)
}
