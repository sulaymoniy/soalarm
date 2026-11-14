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

/**
 * Runs MediaPipe Hand Landmarker on each camera frame, fully on the phone.
 * Frames are analysed in memory and thrown away: nothing is saved or sent.
 *
 * All calls happen on one background thread (the analysis executor), including [close].
 */
class HandWashAnalyzer(
    context: Context,
    private val onState: (WashState) -> Unit,
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
            val foam = if (hands.isEmpty()) 0f else FoamDetector.foamRatio(bitmap, hands)
            onState(tracker.update(ts, hands, foam))
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
