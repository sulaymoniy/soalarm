package com.sulaymoniy.soalarm

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

class SoAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ALARM,
            "Ringing alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shows the alarm screen when an alarm rings"
            // The service plays the song and vibrates itself, so the channel stays quiet.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ALARM = "ringing_alarms"
    }
}
