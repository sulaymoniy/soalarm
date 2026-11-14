package com.sulaymoniy.soalarm.ring

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sulaymoniy.soalarm.alarm.AlarmService
import com.sulaymoniy.soalarm.alarm.RingState
import com.sulaymoniy.soalarm.ui.theme.SoAlarmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Full-screen alarm screen. Shows over the lock screen and turns the screen on.
 * There is no snooze and no dismiss: the only way out is the hand-washing check
 * (or 3 math problems if the camera is not available).
 */
class AlarmRingActivity : ComponentActivity() {

    private var passed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        showOverLockScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (RingState.current(this) == null) {
            // Opened from an old notification after the alarm was already stopped.
            finish()
            return
        }

        // If the ring is stopped some other way, close this screen too.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RingState.ringingId.drop(1).collect { if (it == null && !passed) finish() }
            }
        }

        setContent {
            SoAlarmTheme {
                RingScreen(onPassed = ::onPassed)
            }
        }
    }

    private fun onPassed() {
        passed = true
        AlarmService.stop(this)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** After the alarm is stopped, unlock (if needed) so the user lands on their phone. */
    fun finishAfterSuccess() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, null)
        }
        Toast.makeText(this, "Good morning! ☀️", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private enum class RingMode { Camera, Math, Done }

@Composable
private fun RingScreen(onPassed: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as AlarmRingActivity
    val cameraAllowed = ContextCompat.checkSelfPermission(
        activity, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    var mode by rememberSaveable { mutableStateOf(if (cameraAllowed) RingMode.Camera else RingMode.Math) }
    var mathReason by rememberSaveable {
        mutableStateOf(if (cameraAllowed) null else "Camera access is off, so solve 3 quick problems instead.")
    }

    // No way back out of the alarm screen.
    BackHandler(enabled = true) {}

    val pass = {
        mode = RingMode.Done
        onPassed()
    }

    when (mode) {
        RingMode.Camera -> HandWashScreen(
            onPassed = pass,
            onCameraFailed = { reason ->
                mathReason = reason
                mode = RingMode.Math
            },
        )
        RingMode.Math -> MathChallengeScreen(reason = mathReason, onSolved = pass)
        RingMode.Done -> GoodMorning(onFinished = { activity.finishAfterSuccess() })
    }
}

@Composable
private fun GoodMorning(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800)
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("☀️", fontSize = 72.sp)
            Text(
                "Good morning!",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                "Clean hands, fresh start.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
