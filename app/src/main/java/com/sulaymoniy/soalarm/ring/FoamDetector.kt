package com.sulaymoniy.soalarm.ring

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Rough soap-foam estimate: foam is bright and nearly colourless (white/grey),
 * while skin is clearly coloured. We look at a box around the hands and return
 * the share of pixels that look like foam (0..1).
 */
object FoamDetector {

    fun foamRatio(bitmap: Bitmap, hands: List<List<Pt>>): Float {
        val points = hands.flatten()
        if (points.isEmpty()) return 0f

        var minX = points.minOf { it.x }
        var maxX = points.maxOf { it.x }
        var minY = points.minOf { it.y }
        var maxY = points.maxOf { it.y }
        // Foam sits around and between the hands, so look a little wider.
        val padX = (maxX - minX) * 0.2f
        val padY = (maxY - minY) * 0.2f
        minX -= padX; maxX += padX; minY -= padY; maxY += padY

        val left = minX.toInt().coerceIn(0, bitmap.width - 1)
        val right = maxX.toInt().coerceIn(left + 1, bitmap.width)
        val top = minY.toInt().coerceIn(0, bitmap.height - 1)
        val bottom = maxY.toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return 0f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)

        // About 4000 samples is plenty and keeps this fast.
        val step = max(1, kotlin.math.sqrt((w * h) / 4000.0).toInt())
        var foam = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))
                val brightness = hi / 255f
                val saturation = if (hi == 0) 0f else (hi - lo) / hi.toFloat()
                if (brightness >= WashConfig.FOAM_MIN_BRIGHTNESS && saturation <= WashConfig.FOAM_MAX_SATURATION) {
                    foam++
                }
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else foam / total.toFloat()
    }
}
