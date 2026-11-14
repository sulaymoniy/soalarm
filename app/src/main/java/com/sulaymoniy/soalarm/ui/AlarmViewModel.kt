package com.sulaymoniy.soalarm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sulaymoniy.soalarm.alarm.AlarmScheduler
import com.sulaymoniy.soalarm.data.Alarm
import com.sulaymoniy.soalarm.data.AlarmDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class AlarmViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AlarmDatabase.get(app).alarmDao()

    /** null until the database has answered once (so we don't flash "no alarms"). */
    val alarms: StateFlow<List<Alarm>?> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Saves the alarm and calls [onScheduled] with the next ring time. */
    fun save(alarm: Alarm, onScheduled: (ZonedDateTime?) -> Unit) {
        viewModelScope.launch {
            val saved = if (alarm.id == 0L) {
                alarm.copy(id = dao.insert(alarm))
            } else {
                alarm.also { dao.update(it) }
            }
            onScheduled(AlarmScheduler.schedule(getApplication(), saved))
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean, onScheduled: (ZonedDateTime?) -> Unit) {
        save(alarm.copy(enabled = enabled), onScheduled)
    }

    fun delete(alarm: Alarm) {
        viewModelScope.launch {
            AlarmScheduler.cancel(getApplication(), alarm.id)
            dao.delete(alarm)
        }
    }
}
