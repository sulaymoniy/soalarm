package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.ring.FoamDetector
import com.sulaymoniy.soalarm.ring.HandMask
import com.sulaymoniy.soalarm.ring.Pt
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Synthetic camera frames: a skin-coloured hand on different backgrounds, with or
 * without a white soap bar or bubbly lather. Foam must be found only on the hand and
 * only when it looks bubbly.
 */
class FoamDetectorTest {
    private val w = 640
    private val h = 480
    private val hand = fakeHand(320f, 270f, curl = 0.2f, size = 120f)
    private val palm = Pt(320f, 270f - 0.1f * 120f)

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Builds a frame: [background], then skin on (and a bit around) the hand, then [extra]. */
    private fun frame(
        seed: Int,
        background: (x: Int, y: Int, rnd: Random) -> Int,
        extra: ((x: Int, y: Int, rnd: Random) -> Int?)? = null,
    ): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h) { background(it % w, it / w, rnd) }
        // Skin: the hand mask grown by 6 px, so the mask never touches the background.
        val mask = HandMask.build(listOf(hand), w, h)
        val grown = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            for (dy in -6..6) for (dx in -6..6) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) grown[yy * w + xx] = true
            }
        }
        for (i in px.indices) {
            if (grown[i]) {
                val n = rnd.nextInt(-6, 7)
                px[i] = rgb(190 + n, 140 + n, 112 + n)
            }
        }
        if (extra != null) {
            for (y in 0 until h) for (x in 0 until w) extra(x, y, rnd)?.let { px[y * w + x] = it }
        }
        return px
    }

    private val whiteSink: (Int, Int, Random) -> Int = { _, _, rnd ->
        val n = rnd.nextInt(-2, 3)
        rgb(236 + n, 236 + n, 234 + n)
    }

    private val soapBar: (Int, Int, Random) -> Int? = { x, y, rnd ->
        if (x in 280..360 && y in 230..285) {
            val n = rnd.nextInt(-2, 3)
            rgb(242 + n, 240 + n, 236 + n)
        } else {
            null
        }
    }

    /** Lather: bright grey-white with a dense pattern of small bubbles and darker gaps. */
    private val lather: (Int, Int, Random) -> Int? = { x, y, rnd ->
        if (hypot(x - palm.x, y - palm.y) <= 60f) {
            val v = (235 + 20 * cos(x * 1.6) * cos(y * 1.6)).toInt() + rnd.nextInt(-3, 4)
            rgb(v, v, v + 3)
        } else {
            null
        }
    }

    /** Feeds [dryFrames] skin-only frames (learning skin colour), then returns coverage of [last]. */
    private fun coverage(dryFrames: Int, last: IntArray): Float {
        val detector = FoamDetector()
        val dry = frame(1, whiteSink)
        repeat(dryFrames) { detector.analyze(dry, w, h, listOf(hand)) }
        var result = detector.analyze(last, w, h, listOf(hand))
        // If skin wasn't learned yet (no dry frames), keep showing the same frame.
        var guard = 0
        while (!result.skinReady && guard++ < 40) result = detector.analyze(last, w, h, listOf(hand))
        assertTrue("skin colour learned", result.skinReady)
        return result.coverage
    }

    @Test
    fun dryHandOverWhiteSinkIsNotFoam() {
        val c = coverage(12, frame(2, whiteSink))
        assertTrue("coverage=$c", c < 0.02f)
    }

    @Test
    fun dryHandHoldingWhiteSoapBarIsNotFoam() {
        val c = coverage(12, frame(3, whiteSink, soapBar))
        assertTrue("coverage=$c", c < 0.03f)
    }

    @Test
    fun whiteTowelOrTilesBehindTheHandAreIgnored() {
        // Bubbly-looking white texture everywhere EXCEPT on the hand.
        val towel: (Int, Int, Random) -> Int = { x, y, rnd ->
            val v = (235 + 20 * cos(x * 1.6) * cos(y * 1.6)).toInt() + rnd.nextInt(-3, 4)
            rgb(v, v, v)
        }
        val c = coverage(12, frame(4, towel))
        assertTrue("coverage=$c", c < 0.02f)
    }

    @Test
    fun latherOnTheHandIsFoam() {
        val c = coverage(12, frame(5, whiteSink, lather))
        assertTrue("coverage=$c", c > 0.2f)
    }

    @Test
    fun latherFromTheFirstFrameIsStillFound() {
        // Skin colour is learned from the visible skin around the lather.
        val c = coverage(0, frame(6, whiteSink, lather))
        assertTrue("coverage=$c", c > 0.2f)
    }
}
