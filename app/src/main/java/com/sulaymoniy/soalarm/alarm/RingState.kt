package com.sulaymoniy.soalarm.alarm

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which alarm is ringing right now. Kept in memory for the UI and on disk so a
 * ring survives the app process being killed and restarted.
 */
object RingState {
    private const val PREFS = "ring_state"
    private const val KEY_ID = "ringing_alarm_id"

    private val _ringingId = MutableStateFlow<Long?>(null)
    val ringingId: StateFlow<Long?> = _ringingId.asStateFlow()

    fun set(context: Context, alarmId: Long) {
        _ringingId.value = alarmId
        prefs(context).edit { putLong(KEY_ID, alarmId) }
    }

    fun clear(context: Context) {
        _ringingId.value = null
        prefs(context).edit { remove(KEY_ID) }
    }

    /** Ringing alarm id from memory, or from disk after a process restart. */
    fun current(context: Context): Long? =
        _ringingId.value ?: prefs(context).getLong(KEY_ID, -1L).takeIf { it >= 0 }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
