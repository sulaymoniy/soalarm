package com.sulaymoniy.soalarm.ring

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Foam on the hand in one frame. */
class FoamResult(
    /** Share (0..1) of the hand mask covered by foam. */
    val coverage: Float,
    /** True once this hand's skin colour has been learned. */
    val skinReady: Boolean,
    val maskPixels: Int,
    val debug: FoamDebug? = null,
)

/** Per-pixel maps for the test-mode overlay, on a grid placed at [originX],[originY] with [scale]. */
class FoamDebug(
    val originX: Int,
    val originY: Int,
    val scale: Int,
    val w: Int,
    val h: Int,
    val mask: BooleanArray,
    val foam: BooleanArray,
    val bubbles: BooleanArray,
)

/**
 * Detects soap lather ON the hand, not white things around it.
 *
 * 1. Only pixels inside the [HandMask] are looked at.
 * 2. The hand's own skin colour is learned during the first frames (from colourful,
 *    skin-like pixels), and foam must be clearly brighter and greyer than that skin.
 * 3. Foam must look bubbly: many small bright spots with darker gaps (high local
 *    contrast). A smooth white sink, tile, towel or soap bar doesn't qualify.
 *
 * Pure Kotlin on ARGB int pixels, so it can be unit tested without a phone.
 */
class FoamDetector {
    private val vHist = IntArray(BINS)
    private val sHist = IntArray(BINS)
    private var sampledFrames = 0
    private var framesWithHand = 0

    var skinV = 0f
        private set
    var skinS = 0f
        private set
    var skinReady = false
        private set

    fun analyze(argb: IntArray, imgW: Int, imgH: Int, hands: List<List<Pt>>, wantDebug: Boolean = false): FoamResult {
        val valid = hands.filter { it.size >= 21 }
        if (valid.isEmpty()) return FoamResult(0f, skinReady, 0)

        // Region of interest: the hands plus a margin, clipped to the image.
        val pts = valid.flatten()
        val handLength = valid.maxOf { hypot(it[9].x - it[0].x, it[9].y - it[0].y) }
        val margin = 0.3f * handLength
        val rx0 = max(0, floor(pts.minOf { it.x } - margin).toInt())
        val ry0 = max(0, floor(pts.minOf { it.y } - margin).toInt())
        val rx1 = min(imgW, ceil(pts.maxOf { it.x } + margin).toInt())
        val ry1 = min(imgH, ceil(pts.maxOf { it.y } + margin).toInt())
        if (rx1 - rx0 < 8 || ry1 - ry0 < 8) return FoamResult(0f, skinReady, 0)

        // Work on a grid of at most FOAM_GRID_MAX_SIZE, averaging scale x scale pixels.
        val scale = max(1, ceil(max(rx1 - rx0, ry1 - ry0) / WashConfig.FOAM_GRID_MAX_SIZE.toFloat()).toInt())
        val w = (rx1 - rx0) / scale
        val h = (ry1 - ry0) / scale
        if (w < 8 || h < 8) return FoamResult(0f, skinReady, 0)

        val lum = FloatArray(w * h)
        val value = FloatArray(w * h)
        val sat = FloatArray(w * h)
        val inv = 1f / (255f * scale * scale)
        for (gy in 0 until h) {
            for (gx in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (sy in 0 until scale) {
                    val row = (ry0 + gy * scale + sy) * imgW + rx0 + gx * scale
                    for (sx in 0 until scale) {
                        val c = argb[row + sx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val rf = r * inv
                val gf = g * inv
                val bf = b * inv
                val hi = max(rf, max(gf, bf))
                val lo = min(rf, min(gf, bf))
                val i = gy * w + gx
                lum[i] = 0.299f * rf + 0.587f * gf + 0.114f * bf
                value[i] = hi
                sat[i] = if (hi <= 0f) 0f else (hi - lo) / hi
            }
        }

        val gridHands = valid.map { lm -> lm.map { Pt((it.x - rx0) / scale, (it.y - ry0) / scale) } }
        val mask = HandMask.build(gridHands, w, h)
        val maskCount = mask.count { it }
        if (maskCount == 0) return FoamResult(0f, skinReady, 0)

        if (!skinReady) learnSkin(mask, value, sat)
        if (!skinReady) {
            return FoamResult(0f, false, maskCount, if (wantDebug) FoamDebug(rx0, ry0, scale, w, h, mask, BooleanArray(w * h), BooleanArray(w * h)) else null)
        }

        // Whitish = clearly brighter and greyer than this person's skin.
        val minV = max(WashConfig.FOAM_MIN_BRIGHTNESS, skinV + WashConfig.FOAM_BRIGHTER_THAN_SKIN)
        val maxS = min(WashConfig.FOAM_MAX_SATURATION, skinS * WashConfig.FOAM_SATURATION_VS_SKIN)
        val whitish = BooleanArray(w * h) { mask[it] && value[it] >= minV && sat[it] <= maxS }

        // Bubble highlights: whitish pixels brighter than all 8 neighbours, with real
        // contrast, surrounded mostly by grey (lather gaps), not by skin.
        val greyS = min(1f, maxS * 1.5f)
        val bubbles = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!whitish[i]) continue
                val l = lum[i]
                var sum = 0f
                var isMax = true
                var grey = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val j = i + dy * w + dx
                    if (lum[j] > l) isMax = false
                    if (sat[j] <= greyS) grey++
                    sum += lum[j]
                }
                if (isMax && grey >= WashConfig.BUBBLE_MIN_GREY_NEIGHBOURS &&
                    l - sum / 8f >= WashConfig.BUBBLE_MIN_CONTRAST
                ) {
                    bubbles[i] = true
                }
            }
        }

        // Foam = whitish pixels with enough bubble highlights around them (integral image).
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                if (bubbles[y * w + x]) rowSum++
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        val r = WashConfig.FOAM_TEXTURE_RADIUS
        val foam = BooleanArray(w * h)
        var foamCount = 0
        for (y in 0 until h) {
            val ya = max(0, y - r)
            val yb = min(h, y + r + 1)
            for (x in 0 until w) {
                val i = y * w + x
                if (!whitish[i]) continue
                val xa = max(0, x - r)
                val xb = min(w, x + r + 1)
                val count = integral[yb * (w + 1) + xb] - integral[ya * (w + 1) + xb] -
                    integral[yb * (w + 1) + xa] + integral[ya * (w + 1) + xa]
                if (count >= WashConfig.FOAM_MIN_BUBBLES) {
                    foam[i] = true
                    foamCount++
                }
            }
        }

        return FoamResult(
            coverage = foamCount / maskCount.toFloat(),
            skinReady = true,
            maskPixels = maskCount,
            debug = if (wantDebug) FoamDebug(rx0, ry0, scale, w, h, mask, foam, bubbles) else null,
        )
    }

    /** Learns the median brightness and saturation of skin-like pixels on the hand. */
    private fun learnSkin(mask: BooleanArray, value: FloatArray, sat: FloatArray) {
        framesWithHand++
        var samples = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            val v = value[i]
            val s = sat[i]
            if (s < WashConfig.SKIN_MIN_SATURATION || v < 0.12f || v > 0.98f) continue
            vHist[(v * (BINS - 1)).toInt()]++
            sHist[(s * (BINS - 1)).toInt()]++
            samples++
        }
        if (samples >= 50) sampledFrames++
        when {
            sampledFrames >= WashConfig.SKIN_SAMPLE_FRAMES -> {
                skinV = median(vHist)
                skinS = median(sHist)
                skinReady = true
            }
            // Strange light and no skin-like pixels at all: use typical values instead of waiting forever.
            framesWithHand >= WashConfig.SKIN_SAMPLE_FRAMES * 3 -> {
                skinV = if (vHist.sum() > 0) median(vHist) else DEFAULT_SKIN_V
                skinS = if (sHist.sum() > 0) median(sHist) else DEFAULT_SKIN_S
                skinReady = true
            }
        }
    }

    private fun median(hist: IntArray): Float {
        val total = hist.sum()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc * 2 >= total) return (i + 0.5f) / (BINS - 1)
        }
        return 0.5f
    }

    private companion object {
        const val BINS = 101
        const val DEFAULT_SKIN_V = 0.6f
        const val DEFAULT_SKIN_S = 0.35f
    }
}
