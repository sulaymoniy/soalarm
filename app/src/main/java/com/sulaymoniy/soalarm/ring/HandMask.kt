package com.sulaymoniy.soalarm.ring

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Which pixels belong to the hand: a polygon over the palm plus a rounded "capsule"
 * along every finger bone, built from MediaPipe's 21 landmarks.
 * Anything outside (sink, tiles, towel, the soap bar's surroundings) is ignored.
 */
object HandMask {
    /** Palm outline: wrist, thumb base, then the knuckles. */
    private val PALM = intArrayOf(0, 1, 2, 5, 9, 13, 17)

    /** Thumb and finger bones (landmark pairs). */
    private val BONES = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
    )

    /**
     * Builds a [w] x [h] mask. [hands] are landmark lists in the mask's own pixel
     * coordinates (pixel (x, y) covers x..x+1, y..y+1).
     */
    fun build(hands: List<List<Pt>>, w: Int, h: Int): BooleanArray {
        val mask = BooleanArray(w * h)
        for (lm in hands) {
            if (lm.size < 21) continue
            val handLength = hypot(lm[9].x - lm[0].x, lm[9].y - lm[0].y)
            val radius = WashConfig.FINGER_MASK_RADIUS * handLength
            fillPolygon(mask, w, h, PALM.map { lm[it] })
            for (bone in BONES) fillCapsule(mask, w, h, lm[bone[0]], lm[bone[1]], radius)
        }
        return mask
    }

    private fun fillPolygon(mask: BooleanArray, w: Int, h: Int, poly: List<Pt>) {
        val y0 = max(0, floor(poly.minOf { it.y }).toInt())
        val y1 = min(h - 1, ceil(poly.maxOf { it.y }).toInt())
        val xs = FloatArray(poly.size)
        for (y in y0..y1) {
            val cy = y + 0.5f
            var n = 0
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                if ((a.y <= cy && b.y > cy) || (b.y <= cy && a.y > cy)) {
                    xs[n++] = a.x + (cy - a.y) / (b.y - a.y) * (b.x - a.x)
                }
            }
            xs.sort(0, n)
            var k = 0
            while (k + 1 < n) {
                val x0 = max(0, ceil(xs[k] - 0.5f).toInt())
                val x1 = min(w - 1, floor(xs[k + 1] - 0.5f).toInt())
                for (x in x0..x1) mask[y * w + x] = true
                k += 2
            }
        }
    }

    private fun fillCapsule(mask: BooleanArray, w: Int, h: Int, a: Pt, b: Pt, r: Float) {
        val x0 = max(0, floor(min(a.x, b.x) - r).toInt())
        val x1 = min(w - 1, ceil(max(a.x, b.x) + r).toInt())
        val y0 = max(0, floor(min(a.y, b.y) - r).toInt())
        val y1 = min(h - 1, ceil(max(a.y, b.y) + r).toInt())
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val r2 = r * r
        for (y in y0..y1) {
            val py = y + 0.5f
            for (x in x0..x1) {
                val px = x + 0.5f
                val t = if (len2 <= 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
                val ex = a.x + t * dx - px
                val ey = a.y + t * dy - py
                if (ex * ex + ey * ey <= r2) mask[y * w + x] = true
            }
        }
    }
}
