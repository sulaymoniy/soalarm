package com.sulaymoniy.soalarm.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.sulaymoniy.soalarm.alarm.AlarmScheduler

/** One thing the app needs from the user, with a plain-language reason. */
enum class Need(val title: String, val why: String) {
    Notifications(
        "Notifications",
        "Needed to show the alarm screen when an alarm rings.",
    ),
    ExactAlarms(
        "Alarms & reminders",
        "Lets SoAlarm ring at the exact minute you chose.",
    ),
    FullScreen(
        "Full-screen alarms",
        "Lets the alarm screen open over your lock screen.",
    ),
    Camera(
        "Camera",
        "Used only while an alarm rings, to check you're washing your hands. Pictures never leave your phone.",
    ),
    Battery(
        "Battery: unrestricted",
        "Stops Android from putting SoAlarm to sleep, so alarms aren't missed.",
    ),
}

object Permissions {

    fun missing(context: Context): List<Need> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            add(Need.Notifications)
        }
        if (!AlarmScheduler.canScheduleExact(context)) add(Need.ExactAlarms)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            add(Need.FullScreen)
        }
        if (!granted(context, Manifest.permission.CAMERA)) add(Need.Camera)
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            add(Need.Battery)
        }
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Settings screen for needs that can't use the normal permission pop-up. */
    @SuppressLint("BatteryLife") // An alarm clock is an accepted use case for this request.
    fun settingsIntent(context: Context, need: Need): Intent? {
        val pkg = "package:${context.packageName}".toUri()
        return when (need) {
            Need.ExactAlarms -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else {
                null
            }
            Need.FullScreen -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            } else {
                null
            }
            Need.Battery -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Need.Notifications, Need.Camera -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }
}
