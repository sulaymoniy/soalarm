package com.sulaymoniy.soalarm.ring

import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sulaymoniy.soalarm.R
import com.sulaymoniy.soalarm.alarm.RingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max

/**
 * Camera screen for the hand-washing check: live preview, a progress ring that fills
 * only while a soapy hand is being rubbed, and one simple hint.
 *
 * [testMode] = "Test wash check" from the menu: no alarm, plus a debug overlay with the
 * hand mask, foam pixels and live values, for tuning [WashConfig].
 * [onCantWash] = show the "Can't wash right now?" button after a few minutes of ringing.
 */
@Composable
fun HandWashScreen(
    onPassed: () -> Unit,
    onCameraFailed: (String) -> Unit,
    onCantWash: (() -> Unit)? = null,
    testMode: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPassed by rememberUpdatedState(onPassed)
    val currentOnFailed by rememberUpdatedState(onCameraFailed)

    // After a few minutes of ringing, offer a harder math way out (no sink nearby, etc.).
    val showCantWash by produceState(
        onCantWash != null && RingState.ringingForMs(context) >= WashConfig.MANUAL_FALLBACK_AFTER_MS,
    ) {
        while (onCantWash != null && !value) {
            delay(1_000)
            value = RingState.ringingForMs(context) >= WashConfig.MANUAL_FALLBACK_AFTER_MS
        }
    }

    var useFrontCamera by rememberSaveable { mutableStateOf(true) }
    var boundToFront by remember { mutableStateOf(true) }
    val stateFlow = remember { MutableStateFlow(WashState()) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val debugFlow = remember { MutableStateFlow<DebugFrame?>(null) }
    val debugFrame by debugFlow.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val analyzer: HandWashAnalyzer? = remember {
        try {
            HandWashAnalyzer(
                context,
                debug = testMode,
                onState = { stateFlow.value = it },
                onDebugFrame = if (testMode) ({ debugFlow.value = it }) else null,
            )
        } catch (e: Exception) {
            Log.e("HandWashScreen", "Hand tracker could not start", e)
            null
        }
    }
    // Preview and analysis both use 4:3, so they see the same picture (the overlay lines up).
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
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
            boundToFront = selector == CameraSelector.DEFAULT_FRONT_CAMERA
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO),
                        )
                        .build(),
                )
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
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

        if (testMode) DebugOverlay(debugFrame, mirrored = boundToFront)

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (testMode) "Test wash check (no alarm)" else "Wash your hand with soap to stop the alarm",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "One hand is enough 🖐️",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                    Icon(painterResource(R.drawable.ic_switch_camera), contentDescription = "Switch camera")
                }
            }

            if (testMode) {
                Spacer(Modifier.padding(top = 8.dp))
                DebugValues(state)
            }

            Spacer(Modifier.weight(1f))
            ProgressRing(state)
            Spacer(Modifier.weight(1f))

            if (showCantWash && onCantWash != null) {
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
    val progress by animateFloatAsState(state.progress, label = "wash")
    val ringColor = if (state.washingNow) Color(0xFF4FC3F7) else Color(0xFFB0BEC5)
    val secondsLeft = ceil(WashConfig.WASH_SECONDS_REQUIRED - state.washSeconds).toInt().coerceAtLeast(0)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
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
    val emoji = when (state.hint) {
        WashHint.ShowHand -> "🖐️"
        WashHint.AddSoap -> "🧼"
        WashHint.RubHand -> "👋"
        WashHint.KeepGoing -> "💧"
    }
    Text(
        "$emoji  ${state.hint.text}",
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(16.dp),
    )
}

/** Live numbers and pass/fail per condition (test mode only). */
@Composable
private fun DebugValues(state: WashState) {
    val d = state.debug
    fun pct(v: Float) = String.format(Locale.US, "%.0f%%", v * 100)
    fun mark(ok: Boolean) = if (ok) "✅" else "❌"
    val lines = listOf(
        "${mark(d.handsVisible > 0)} Hand: ${d.handsVisible} visible",
        if (d.skinReady) "✅ Skin colour learned" else "⏳ Learning skin colour…",
        "${mark(d.foamNow)} Foam ${pct(d.foamCoverage)}  (before ${pct(d.foamBaseline)}, " +
            "needs +${pct(WashConfig.FOAM_COVERAGE_THRESHOLD)})",
        "${mark(d.rubbing)} Rubbing " + String.format(Locale.US, "%.1f Hz, amp %.3f", d.rubHz, d.rubAmplitude) +
            "  (needs ${WashConfig.RUB_MIN_HZ}–${WashConfig.RUB_MAX_HZ} Hz, ≥${WashConfig.RUB_MIN_AMPLITUDE})",
        "${mark(state.washingNow)} Counting: " + String.format(Locale.US, "%.1f / %.0f s", state.washSeconds, WashConfig.WASH_SECONDS_REQUIRED),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach {
            Text(it, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            "Blue = hand mask · Green = foam · Yellow = bubbles",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )
    }
}

/**
 * Draws the hand mask, foam pixels and landmarks on top of the preview.
 * The preview fills the screen (FILL_CENTER) and is mirrored for the front camera.
 */
@Composable
private fun DebugOverlay(frame: DebugFrame?, mirrored: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val f = frame ?: return@Canvas
        val s = max(size.width / f.imageW, size.height / f.imageH)
        val ox = (size.width - f.imageW * s) / 2f
        val oy = (size.height - f.imageH * s) / 2f
        scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f, pivot = center) {
            f.overlay?.let { bmp ->
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset((ox + f.overlayX * s).toInt(), (oy + f.overlayY * s).toInt()),
                    dstSize = IntSize((bmp.width * f.overlayScale * s).toInt(), (bmp.height * f.overlayScale * s).toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            for (hand in f.hands) {
                for (p in hand) {
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(ox + p.x * s, oy + p.y * s))
                }
            }
        }
    }
}
