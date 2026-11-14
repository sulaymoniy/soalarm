package com.sulaymoniy.soalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android forgets all alarms on reboot, and clock/time-zone changes move them.
 * This receiver puts every alarm back in place.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // A ring that was interrupted by a reboot is over.
            RingState.clear(context)
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
