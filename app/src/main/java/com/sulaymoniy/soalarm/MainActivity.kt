package com.sulaymoniy.soalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sulaymoniy.soalarm.alarm.RingState
import com.sulaymoniy.soalarm.ring.AlarmRingActivity
import com.sulaymoniy.soalarm.ui.SoAlarmRoot
import com.sulaymoniy.soalarm.ui.theme.SoAlarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SoAlarmTheme {
                SoAlarmRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If an alarm is ringing, the only place to go is the washing check.
        if (RingState.current(this) != null) {
            startActivity(AlarmRingActivity.intent(this))
        }
    }
}
