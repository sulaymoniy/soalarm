package com.sulaymoniy.soalarm.ring

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import com.sulaymoniy.soalarm.alarm.RingState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sulaymoniy.soalarm.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Camera screen for the hand-washing check: live preview, a progress ring that
 * fills only while washing is detected, a soap meter and simple hints.
 */
@Composable
fun HandWashScreen(onPassed: () -> Unit, onCameraFailed: (String) -> Unit, onCantWash: () -> Unit) {
    val context = LocalContext.current
    // After a few minutes of ringing, offer a harder math way out (no sink nearby, etc.).
    val showCantWash by produceState(
        RingState.ringingForMs(context) >= WashConfig.MANUAL_FALLBACK_AFTER_MS,
    ) {
        while (!value) {
            delay(1_000)
            value = RingState.ringingForMs(context) >= WashConfig.MANUAL_FALLBACK_AFTER_MS
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPassed by rememberUpdatedState(onPassed)
    val currentOnFailed by rememberUpdatedState(onCameraFailed)

    var useFrontCamera by rememberSaveable { mutableStateOf(true) }
    val stateFlow = remember { MutableStateFlow(WashState()) }
    val state by stateFlow.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val analyzer: HandWashAnalyzer? = remember {
        try {
            HandWashAnalyzer(context) { stateFlow.value = it }
        } catch (e: Exception) {
            Log.e("HandWashScreen", "Hand tracker could not start", e)
            null
        }
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }

    if (analyzer == null) {
        LaunchedEffect(Unit) { currentOnFailed("The hand tracker could not start, so solve 3 quick problems instead.") }
        return
    }

    DisposableEffect(Unit) {
        analysis.setAnalyzer(executor, analyzer)
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysis.clearAnalyzer()
            executor.execute { analyzer.close() }
            executor.shutdown()
        }
    }

    LaunchedEffect(useFrontCamera) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            val preferred = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val other = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val selector = when {
                provider.hasCamera(preferred) -> preferred
                provider.hasCamera(other) -> other
                else -> {
                    currentOnFailed("No camera was found, so solve 3 quick problems instead.")
                    return@LaunchedEffect
                }
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Exception) {
            Log.e("HandWashScreen", "Camera failed", e)
            currentOnFailed("The camera could not start, so solve 3 quick problems instead.")
            return@LaunchedEffect
        }
        // Safety net: a camera that opens but never sends pictures counts as broken.
        val framesBefore = stateFlow.value.frames
        delay(WashConfig.CAMERA_NO_FRAMES_TIMEOUT_MS)
        if (stateFlow.value.frames == framesBefore) {
            currentOnFailed("The camera is not sending pictures, so solve 3 quick problems instead.")
        }
    }

    LaunchedEffect(state.passed) {
        if (state.passed) currentOnPassed()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Wash your hands to stop the alarm",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                    Icon(painterResource(R.drawable.ic_switch_camera), contentDescription = "Switch camera")
                }
            }

            Spacer(Modifier.weight(1f))
            ProgressRing(state)
            Spacer(Modifier.weight(1f))

            if (showCantWash) {
                TextButton(
                    onClick = onCantWash,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                ) {
                    Text("Can't wash right now? Solve math instead")
                }
            }

            HintCard(state)
        }
    }
}

@Composable
private fun ProgressRing(state: WashState) {
    val progress by animateFloatAsState(state.washProgress, label = "wash")
    val ringColor = if (state.washingNow) Color(0xFF4FC3F7) else Color(0xFFB0BEC5)
    val secondsLeft = ceil(WashConfig.WASH_SECONDS_REQUIRED - state.washSeconds).toInt().coerceAtLeast(0)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = GSize(size.width - stroke, size.height - stroke)
            drawCircle(Color.Black.copy(alpha = 0.35f))
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$secondsLeft", style = MaterialTheme.typography.displayLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Text("seconds left", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

@Composable
private fun HintCard(state: WashState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val emoji = when (state.hint) {
            WashHint.ShowBothHands -> "🙌"
            WashHint.RubHands -> "👐"
            WashHint.AddSoap -> "🧼"
            WashHint.KeepGoing -> "💧"
        }
        Text(
            "$emoji  ${state.hint.text}",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Soap", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { state.foamProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.foamProgress >= 1f) "✓" else "${(state.foamProgress * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
