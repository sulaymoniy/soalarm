package com.sulaymoniy.soalarm.alarm

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Which alarm is ringing right now. Kept in memory for the UI and on disk so a
 * ring survives the app process being killed and restarted (but not a reboot).
 */
object RingState {
    private const val PREFS = "ring_state"
    private const val KEY_ID = "ringing_alarm_id"
    private const val KEY_BOOT = "boot_time"

    private val _ringingId = MutableStateFlow<Long?>(null)
    val ringingId: StateFlow<Long?> = _ringingId.asStateFlow()

    fun set(context: Context, alarmId: Long) {
        _ringingId.value = alarmId
        prefs(context).edit {
            putLong(KEY_ID, alarmId)
            putLong(KEY_BOOT, bootTime())
        }
    }

    fun clear(context: Context) {
        _ringingId.value = null
        prefs(context).edit { remove(KEY_ID); remove(KEY_BOOT) }
    }

    /** Ringing alarm id from memory, or from disk after a process restart in this boot. */
    fun current(context: Context): Long? {
        _ringingId.value?.let { return it }
        val prefs = prefs(context)
        val id = prefs.getLong(KEY_ID, -1L).takeIf { it >= 0 } ?: return null
        // Saved before the last reboot? Then that ring is over.
        if (abs(prefs.getLong(KEY_BOOT, 0L) - bootTime()) > BOOT_TOLERANCE_MS) {
            clear(context)
            return null
        }
        return id
    }

    /**
     * If the app process was killed mid-ring (so nothing is playing), start the
     * ringing service again. Called when the user opens the app.
     */
    fun resumeIfInterrupted(context: Context): Boolean {
        val id = current(context) ?: return false
        if (_ringingId.value == null) AlarmService.start(context, id)
        return true
    }

    /** Wall-clock time of the last boot. Same value for the whole boot (within clock changes). */
    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Allows for small clock adjustments without treating them as a reboot. */
    private const val BOOT_TOLERANCE_MS = 10 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
