package com.sulaymoniy.soalarm.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sulaymoniy.soalarm.R
import com.sulaymoniy.soalarm.SoAlarmApp
import com.sulaymoniy.soalarm.data.AlarmDatabase
import com.sulaymoniy.soalarm.ring.AlarmRingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that plays the alarm song (looping, fading in) and vibrates
 * until the hand-washing check is passed.
 */
class AlarmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var ringingId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted us after killing the process mid-ring.
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L)?.takeIf { it >= 0 }
            ?: RingState.current(this)
        if (alarmId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!goForeground(alarmId, label = "")) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ringingId == null) {
            ringingId = alarmId
            RingState.set(this, alarmId)
            acquireWakeLock()
            startSound()
            startVibration()
            openRingScreen()
            scope.launch {
                val label = withContext(Dispatchers.IO) {
                    AlarmDatabase.get(this@AlarmService).alarmDao().get(alarmId)?.label.orEmpty()
                }
                if (label.isNotBlank()) goForeground(alarmId, label)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        fadeJob = null
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator?.cancel()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wakeLock?.takeIf { it.isHeld }?.release()
        RingState.clear(this)
        super.onDestroy()
    }

    // ---- Foreground notification -----------------------------------------------------------

    private fun goForeground(alarmId: Long, label: String): Boolean {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(alarmId, label), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground service", e)
            false
        }
    }

    private fun buildNotification(alarmId: Long, label: String): Notification {
        val ringScreen = PendingIntent.getActivity(
            this,
            alarmId.toInt(),
            AlarmRingActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SoAlarmApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label.ifBlank { "Wake up!" })
            .setContentText("Wash your hands with soap to stop the alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(ringScreen)
            .setFullScreenIntent(ringScreen, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openRingScreen() {
        // Usually the full-screen notification opens the screen. This direct start
        // helps when Android allows it (for example when the app is already open).
        try {
            startActivity(AlarmRingActivity.intent(this))
        } catch (e: Exception) {
            Log.i(TAG, "Relying on the full-screen notification to open the alarm screen")
        }
    }

    // ---- Sound -------------------------------------------------------------------------------

    private fun startSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioManager = getSystemService(AudioManager::class.java)

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
            .also { audioManager.requestAudioFocus(it) }

        player = MediaPlayer.create(this, R.raw.orkney, attributes, audioManager.generateAudioSessionId())
            ?.apply {
                isLooping = true
                setVolume(START_VOLUME, START_VOLUME)
                start()
            }
        if (player == null) Log.e(TAG, "Could not load the alarm song")

        fadeJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val progress = ((SystemClock.elapsedRealtime() - start) / FADE_IN_MS.toFloat()).coerceIn(0f, 1f)
                // Squared curve: our ears hear loudness logarithmically, so this feels like a smooth rise.
                val volume = START_VOLUME + (1f - START_VOLUME) * progress * progress
                player?.setVolume(volume, volume)
                if (progress >= 1f) break
                delay(200)
            }
        }
    }

    // ---- Vibration ---------------------------------------------------------------------------

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vib == null || !vib.hasVibrator()) return
        vibrator = vib
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoAlarm:ringing")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val NOTIFICATION_ID = 1001

        /** Volume starts at 5 % and rises to 100 % over 60 seconds. */
        private const val START_VOLUME = 0.05f
        private const val FADE_IN_MS = 60_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmService::class.java).putExtra(EXTRA_ALARM_ID, alarmId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops sound, vibration and the service. Call only after the check is passed. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
            RingState.clear(context)
        }
    }
}
