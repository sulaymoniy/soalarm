package com.sulaymoniy.soalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sulaymoniy.soalarm.data.AlarmDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Called by AlarmManager when an alarm is due. Starts the ringing service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AlarmDatabase.get(context).alarmDao()
                val alarm = dao.get(alarmId)
                if (alarm == null || !alarm.enabled) return@launch

                AlarmService.start(context, alarmId)

                if (alarm.repeats) {
                    // Look one minute ahead so the alarm that is ringing now is not picked again.
                    AlarmScheduler.schedule(context, alarm, ZonedDateTime.now().plusMinutes(1))
                } else {
                    dao.update(alarm.copy(enabled = false))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
