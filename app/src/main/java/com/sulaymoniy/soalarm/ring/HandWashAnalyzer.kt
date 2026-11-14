package com.sulaymoniy.soalarm.ring

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** What the test-mode overlay draws for one frame (all in analysis-image pixels). */
class DebugFrame(
    val imageW: Int,
    val imageH: Int,
    val hands: List<List<Pt>>,
    /** Mask / foam / bubble colours on the foam grid, or null when no hand is visible. */
    val overlay: Bitmap?,
    val overlayX: Int,
    val overlayY: Int,
    val overlayScale: Int,
)

/**
 * Runs MediaPipe Hand Landmarker on each camera frame, fully on the phone, then
 * measures foam on the hand and feeds the [HandWashTracker].
 * Frames are analysed in memory and thrown away: nothing is saved or sent.
 *
 * All calls happen on one background thread (the analysis executor), including [close].
 */
class HandWashAnalyzer(
    context: Context,
    private val debug: Boolean,
    private val onState: (WashState) -> Unit,
    private val onDebugFrame: ((DebugFrame) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .build(),
    )

    private val tracker = HandWashTracker()
    private val foamDetector = FoamDetector()
    private var pixels = IntArray(0)
    private var lastTimestamp = 0L
    private var closed = false

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            val bitmap = image.toBitmap().rotated(image.imageInfo.rotationDegrees)

            // VIDEO mode needs strictly increasing timestamps.
            val ts = maxOf(image.imageInfo.timestamp / 1_000_000, lastTimestamp + 1)
            lastTimestamp = ts

            val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
            val hands = result.landmarks().map { hand ->
                hand.map { Pt(it.x() * bitmap.width, it.y() * bitmap.height) }
            }

            val foam = if (hands.isEmpty()) {
                FoamResult(0f, foamDetector.skinReady, 0)
            } else {
                val n = bitmap.width * bitmap.height
                if (pixels.size != n) pixels = IntArray(n)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                foamDetector.analyze(pixels, bitmap.width, bitmap.height, hands, wantDebug = debug)
            }
            onState(tracker.update(ts, hands, foam.coverage, foam.skinReady))

            if (debug && onDebugFrame != null) {
                val d = foam.debug
                onDebugFrame(
                    DebugFrame(
                        imageW = bitmap.width,
                        imageH = bitmap.height,
                        hands = hands,
                        overlay = d?.let { overlayBitmap(it) },
                        overlayX = d?.originX ?: 0,
                        overlayY = d?.originY ?: 0,
                        overlayScale = d?.scale ?: 1,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    fun close() {
        closed = true
        landmarker.close()
    }

    /** Blue tint = hand mask, green = foam, yellow = bubble highlights. */
    private fun overlayBitmap(d: FoamDebug): Bitmap {
        val colors = IntArray(d.w * d.h) { i ->
            when {
                d.bubbles[i] -> 0xFFFFEB3B.toInt()
                d.foam[i] -> 0xCC00E676.toInt()
                d.mask[i] -> 0x553D8BFF
                else -> 0
            }
        }
        return Bitmap.createBitmap(colors, d.w, d.h, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { recycle() }
    }

    companion object {
        private const val TAG = "HandWashAnalyzer"
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
