package com.sulaymoniy.soalarm.ring

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Soap on the hand in one frame. */
class FoamResult(
    /** Share (0..1) of the hand that looks soapy. Only meaningful when [sharp]. */
    val coverage: Float,
    /** True once this hand's skin colour has been learned. */
    val skinReady: Boolean,
    /** False = too blurry to judge soap; the tracker skips this frame for the soap check. */
    val sharp: Boolean = true,
    val maskPixels: Int = 0,
    /** Sharpness (Laplacian variance) of this frame and the recent best, for the debug view. */
    val sharpness: Float = 0f,
    val sharpnessRef: Float = 0f,
    /** Share (0..1) of the hand mask cut out as "soap bar". */
    val barShare: Float = 0f,
    val skinV: Float = 0f,
    val skinS: Float = 0f,
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
    val bar: BooleanArray,
    val foam: BooleanArray,
    val bubbles: BooleanArray,
)

/**
 * Detects soap ON the hand, not white things around it.
 *
 * 1. Only pixels inside the [HandMask] are looked at. Strongly coloured, smooth areas
 *    (a soap bar held in the palm) are cut out too.
 * 2. Blurry frames (rubbing, shaky phone) are flagged and skipped by the tracker, because
 *    blur wipes out small bubbles.
 * 3. The hand's own skin colour is learned during the first frames.
 * 4. Soap = small bright "bubble highlights" spread over the skin: brighter and greyer
 *    than this skin, brighter than their neighbours, and falling off in almost every
 *    direction (a small spot, not the edge of something white). This catches thick foam,
 *    thin bar-soap lather and wet, shiny, soap-filmed skin. Smooth white sinks, tiles,
 *    towels and soap bars have none.
 * 5. Coverage = share of the hand that has enough bubble highlights nearby.
 *
 * Pure Kotlin on ARGB int pixels, so it can be unit tested without a phone.
 */
class FoamDetector {
    private val vHist = IntArray(BINS)
    private val sHist = IntArray(BINS)
    private var sampledFrames = 0
    private var framesWithHand = 0
    private var sharpRef = 0f

    var skinV = 0f
        private set
    var skinS = 0f
        private set
    var skinReady = false
        private set

    fun analyze(argb: IntArray, imgW: Int, imgH: Int, hands: List<List<Pt>>, wantDebug: Boolean = false): FoamResult {
        val valid = hands.filter { it.size >= 21 }
        if (valid.isEmpty()) return FoamResult(0f, skinReady, sharp = false)

        // Region of interest: the hands plus a margin, clipped to the image.
        val pts = valid.flatten()
        val handLength = valid.maxOf { hypot(it[9].x - it[0].x, it[9].y - it[0].y) }
        val margin = 0.3f * handLength
        val rx0 = max(0, floor(pts.minOf { it.x } - margin).toInt())
        val ry0 = max(0, floor(pts.minOf { it.y } - margin).toInt())
        val rx1 = min(imgW, ceil(pts.maxOf { it.x } + margin).toInt())
        val ry1 = min(imgH, ceil(pts.maxOf { it.y } + margin).toInt())
        if (rx1 - rx0 < 8 || ry1 - ry0 < 8) return FoamResult(0f, skinReady, sharp = false)

        // Work on a grid of at most FOAM_GRID_MAX_SIZE, averaging scale x scale pixels.
        val scale = max(1, ceil(max(rx1 - rx0, ry1 - ry0) / WashConfig.FOAM_GRID_MAX_SIZE.toFloat()).toInt())
        val w = (rx1 - rx0) / scale
        val h = (ry1 - ry0) / scale
        if (w < 8 || h < 8) return FoamResult(0f, skinReady, sharp = false)

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
        val handMask = HandMask.build(gridHands, w, h)
        val handCount = handMask.count { it }
        if (handCount == 0) return FoamResult(0f, skinReady, sharp = false)

        // ---- Cut out a soap bar held in the hand: much more colourful than the hand, and smooth.
        val bar = soapBar(handMask, lum, sat, w, h)
        val mask = BooleanArray(w * h) { handMask[it] && !bar[it] }
        val maskCount = mask.count { it }
        val barShare = 1f - maskCount / handCount.toFloat()
        if (maskCount == 0) return FoamResult(0f, skinReady, sharp = false, barShare = barShare)

        // ---- Sharpness: variance of the Laplacian over the hand. Compared with recent frames.
        val sharpness = laplacianVariance(mask, lum, w, h)
        sharpRef = max(sharpRef * WashConfig.SHARPNESS_MEMORY, sharpness)
        val sharp = sharpness >= max(WashConfig.SHARPNESS_MIN, WashConfig.SHARPNESS_RELATIVE * sharpRef)

        if (!skinReady && sharp) learnSkin(mask, value, sat)
        fun result(coverage: Float, foam: BooleanArray?, bubbles: BooleanArray?) = FoamResult(
            coverage = coverage,
            skinReady = skinReady,
            sharp = sharp,
            maskPixels = maskCount,
            sharpness = sharpness,
            sharpnessRef = sharpRef,
            barShare = barShare,
            skinV = skinV,
            skinS = skinS,
            debug = if (wantDebug) {
                FoamDebug(rx0, ry0, scale, w, h, mask, bar, foam ?: BooleanArray(w * h), bubbles ?: BooleanArray(w * h))
            } else {
                null
            },
        )
        if (!skinReady || !sharp) return result(0f, null, null)

        // ---- Bubble highlights.
        val minV = max(WashConfig.FOAM_MIN_BRIGHTNESS, skinV + WashConfig.FOAM_BRIGHTER_THAN_SKIN)
        val maxS = min(WashConfig.FOAM_MAX_SATURATION, skinS * WashConfig.FOAM_SATURATION_VS_SKIN)
        val greyS = min(1f, maxS * 1.5f)
        val c = WashConfig.BUBBLE_MIN_CONTRAST
        val bubbles = BooleanArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                if (!mask[i] || value[i] < minV || sat[i] > maxS) continue
                val l = lum[i]
                // Brighter than all 8 neighbours, with real contrast, mostly grey around it.
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
                if (!isMax || grey < WashConfig.BUBBLE_MIN_GREY_NEIGHBOURS || l - sum / 8f < c) continue
                // A small spot: 2 px away it is darker in (almost) every direction.
                var falloff = 0
                for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                    if (dx == 0 && dy == 0) continue
                    if (l - lum[i + dy * w + dx] >= c) falloff++
                }
                if (falloff >= WashConfig.BUBBLE_MIN_FALLOFF_DIRECTIONS) bubbles[i] = true
            }
        }

        // ---- Soapy = hand pixels with enough bubble highlights nearby (integral image).
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
                if (!mask[i]) continue
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
        return result(foamCount / maskCount.toFloat(), foam, bubbles)
    }

    /** Strongly coloured (vs. the hand's median), smooth pixels inside the hand, grown a little. */
    private fun soapBar(handMask: BooleanArray, lum: FloatArray, sat: FloatArray, w: Int, h: Int): BooleanArray {
        val hist = IntArray(BINS)
        for (i in handMask.indices) if (handMask[i]) hist[(sat[i] * (BINS - 1)).toInt()]++
        val minSat = median(hist) + WashConfig.SOAP_BAR_SATURATION_ABOVE_HAND

        // Local brightness spread in 5x5 patches via integral images of L and L².
        val s1 = DoubleArray((w + 1) * (h + 1))
        val s2 = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var a = 0.0
            var b = 0.0
            for (x in 0 until w) {
                val l = lum[y * w + x].toDouble()
                a += l
                b += l * l
                s1[(y + 1) * (w + 1) + x + 1] = s1[y * (w + 1) + x + 1] + a
                s2[(y + 1) * (w + 1) + x + 1] = s2[y * (w + 1) + x + 1] + b
            }
        }
        val core = BooleanArray(w * h)
        for (y in 0 until h) {
            val ya = max(0, y - 2)
            val yb = min(h, y + 3)
            for (x in 0 until w) {
                val i = y * w + x
                if (!handMask[i] || sat[i] < minSat) continue
                val xa = max(0, x - 2)
                val xb = min(w, x + 3)
                val n = ((yb - ya) * (xb - xa)).toDouble()
                fun box(s: DoubleArray) = s[yb * (w + 1) + xb] - s[ya * (w + 1) + xb] - s[yb * (w + 1) + xa] + s[ya * (w + 1) + xa]
                val mean = box(s1) / n
                val std = sqrt(max(0.0, box(s2) / n - mean * mean))
                if (std <= WashConfig.SOAP_BAR_MAX_TEXTURE) core[i] = true
            }
        }
        // Grow the cut-out so the bar's outline isn't checked for bubbles either.
        val g = WashConfig.SOAP_BAR_GROW
        val bar = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!core[y * w + x]) continue
            for (dy in -g..g) for (dx in -g..g) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) bar[yy * w + xx] = true
            }
        }
        return bar
    }

    private fun laplacianVariance(mask: BooleanArray, lum: FloatArray, w: Int, h: Int): Float {
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!mask[i]) continue
            val lap = 4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]
            sum += lap
            sum2 += lap * lap
            n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sum2 / n - mean * mean).toFloat()
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
        if (total == 0) return 0.5f
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
