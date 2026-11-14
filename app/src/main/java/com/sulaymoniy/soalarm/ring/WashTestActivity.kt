package com.sulaymoniy.soalarm.ring

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sulaymoniy.soalarm.ui.theme.SoAlarmTheme

/**
 * "Test wash check": the camera check without an alarm, with a debug overlay, so the
 * thresholds in [WashConfig] can be tuned at the sink.
 */
class WashTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SoAlarmTheme {
                WashTestScreen(onClose = ::finish)
            }
        }
    }
}

private const val PASSED = "passed"

@Composable
private fun WashTestScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    LaunchedEffect(Unit) { if (!cameraAllowed) launcher.launch(Manifest.permission.CAMERA) }

    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }

    when {
        !cameraAllowed -> Message(
            title = "Camera needed",
            text = "The wash check uses the camera. Pictures never leave your phone.",
            button = "Allow camera",
            onButton = { launcher.launch(Manifest.permission.CAMERA) },
            onClose = onClose,
        )
        result == PASSED -> Message(
            title = "✅ Passed",
            text = "This would have stopped the alarm.",
            button = "Test again",
            onButton = { result = null; attempt++ },
            onClose = onClose,
        )
        result != null -> Message(
            title = "Camera problem",
            text = result.orEmpty(),
            button = "Try again",
            onButton = { result = null; attempt++ },
            onClose = onClose,
        )
        else -> key(attempt) {
            HandWashScreen(
                onPassed = { result = PASSED },
                onCameraFailed = { result = it },
                testMode = true,
            )
        }
    }
}

@Composable
private fun Message(title: String, text: String, button: String, onButton: () -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onButton, modifier = Modifier.fillMaxWidth()) { Text(button) }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
