package com.sulaymoniy.soalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.sulaymoniy.soalarm.MainActivity
import com.sulaymoniy.soalarm.data.Alarm
import com.sulaymoniy.soalarm.data.AlarmDatabase
import java.time.ZonedDateTime

/** Talks to Android's AlarmManager. */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val ACTION_FIRE = "com.sulaymoniy.soalarm.action.FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"

    /**
     * Schedules (or cancels, if disabled) [alarm].
     * Returns the trigger time, or null when nothing was scheduled.
     */
    fun schedule(context: Context, alarm: Alarm, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return null
        }
        val trigger = AlarmTime.nextTrigger(alarm, from)
        scheduleAt(context, alarm.id, trigger.toInstant().toEpochMilli())
        return trigger
    }

    fun cancel(context: Context, alarmId: Long) {
        alarmManager(context).cancel(firePendingIntent(context, alarmId))
    }

    /** Re-creates every enabled alarm. Used after reboot, app update and clock changes. */
    suspend fun rescheduleAll(context: Context) {
        AlarmDatabase.get(context).alarmDao().getAll().forEach { schedule(context, it) }
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    private fun scheduleAt(context: Context, alarmId: Long, triggerAtMillis: Long) {
        val am = alarmManager(context)
        val operation = firePendingIntent(context, alarmId)
        if (canScheduleExact(context)) {
            try {
                // setAlarmClock = exact, works in Doze, and shows the alarm icon in the status bar.
                val showIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm not allowed, using fallback", e)
            }
        }
        // Fallback when the user switched off "Alarms & reminders": still rings, maybe a bit late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
    }

    private fun firePendingIntent(context: Context, alarmId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)
}
