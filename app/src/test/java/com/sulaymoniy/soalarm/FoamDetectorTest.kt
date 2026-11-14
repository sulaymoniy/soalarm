package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.ring.FoamDetector
import com.sulaymoniy.soalarm.ring.FoamResult
import com.sulaymoniy.soalarm.ring.HandMask
import com.sulaymoniy.soalarm.ring.Pt
import com.sulaymoniy.soalarm.ring.WashConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Synthetic camera frames: a skin-coloured hand on different backgrounds, with or
 * without a soap bar or lather. Soap must be found only on the hand, only when it
 * looks bubbly, and only on sharp frames.
 */
class FoamDetectorTest {
    private val w = 640
    private val h = 480
    private val hand = fakeHand(320f, 270f, curl = 0.2f, size = 120f)
    private val palm = Pt(320f, 270f - 0.1f * 120f)

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Builds a frame: [background], then skin on (and a bit around) the hand, then [extra] layers. */
    private fun frame(
        seed: Int,
        background: (x: Int, y: Int, rnd: Random) -> Int,
        vararg extra: (px: IntArray, rnd: Random) -> Unit,
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
        extra.forEach { it(px, rnd) }
        return px
    }

    private val whiteSink: (Int, Int, Random) -> Int = { _, _, rnd ->
        val n = rnd.nextInt(-2, 3)
        rgb(236 + n, 236 + n, 234 + n)
    }

    private fun rect(px: IntArray, rnd: Random, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        for (y in y0..y1) for (x in x0..x1) {
            val n = rnd.nextInt(-2, 3)
            px[y * w + x] = rgb(r + n, g + n, b + n)
        }
    }

    private val whiteSoapBar: (IntArray, Random) -> Unit = { px, rnd -> rect(px, rnd, 280, 360, 230, 285, 242, 240, 236) }
    private val orangeSoapBar: (IntArray, Random) -> Unit = { px, rnd -> rect(px, rnd, 280, 360, 230, 285, 235, 120, 40) }
    private val greenSoapBar: (IntArray, Random) -> Unit = { px, rnd -> rect(px, rnd, 280, 360, 230, 285, 60, 170, 80) }
    private val creamSoapBar: (IntArray, Random) -> Unit = { px, rnd -> rect(px, rnd, 280, 360, 230, 285, 240, 228, 200) }

    /** Small bubbles: a bright centre with a light ring, ~3 px across. */
    private fun bubble(px: IntArray, x: Int, y: Int) {
        for (dy in -1..1) for (dx in -1..1) px[(y + dy) * w + x + dx] = rgb(232, 229, 227)
        px[y * w + x] = rgb(252, 251, 250)
    }

    /** Bar-soap lather: a thin soap film on the skin with SPARSE small bubbles (~1 per 8x8 px). */
    private val thinLather: (IntArray, Random) -> Unit = { px, rnd ->
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x - palm.x, y - palm.y) > 60f) continue
            val n = rnd.nextInt(-4, 5)
            px[y * w + x] = rgb(205 + n, 172 + n, 152 + n) // skin seen through a soapy film
        }
        var y = (palm.y - 56).toInt()
        while (y < palm.y + 56) {
            var x = (palm.x - 56).toInt()
            while (x < palm.x + 56) {
                val bx = x + rnd.nextInt(0, 3)
                val by = y + rnd.nextInt(0, 3)
                if (hypot(bx - palm.x, by - palm.y) < 56f) bubble(px, bx, by)
                x += 8
            }
            y += 8
        }
    }

    /** Tinted lather (pink liquid soap): pinkish film with pink-white bubbles. */
    private val tintedLather: (IntArray, Random) -> Unit = { px, rnd ->
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x - palm.x, y - palm.y) > 60f) continue
            val n = rnd.nextInt(-4, 5)
            px[y * w + x] = rgb(215 + n, 175 + n, 180 + n)
        }
        var y = (palm.y - 56).toInt()
        while (y < palm.y + 56) {
            var x = (palm.x - 56).toInt()
            while (x < palm.x + 56) {
                val bx = x + rnd.nextInt(0, 3)
                val by = y + rnd.nextInt(0, 3)
                if (hypot(bx - palm.x, by - palm.y) < 56f) {
                    for (dy in -1..1) for (dx in -1..1) px[(by + dy) * w + bx + dx] = rgb(228, 168, 182)
                    px[by * w + bx] = rgb(255, 185, 205)
                }
                x += 8
            }
            y += 8
        }
    }

    /** Thick white foam: grey-white film packed with bubbles (~1 per 5x5 px). */
    private val thickLather: (IntArray, Random) -> Unit = { px, rnd ->
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x - palm.x, y - palm.y) > 60f) continue
            val n = rnd.nextInt(-3, 4)
            px[y * w + x] = rgb(212 + n, 212 + n, 216 + n)
        }
        var y = (palm.y - 56).toInt()
        while (y < palm.y + 56) {
            var x = (palm.x - 56).toInt()
            while (x < palm.x + 56) {
                if (hypot(x - palm.x, y - palm.y) < 56f) bubble(px, x + rnd.nextInt(0, 2), y + rnd.nextInt(0, 2))
                x += 5
            }
            y += 5
        }
    }

    private fun blur(px: IntArray, r: Int = 2): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val c = px[yy * w + xx]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++
            }
            out[y * w + x] = rgb(rs / n, gs / n, bs / n)
        }
        return out
    }

    /** Feeds [dryFrames] sharp skin-only frames (learning skin colour), then analyses [last]. */
    private fun analyze(dryFrames: Int, last: IntArray, detector: FoamDetector = FoamDetector()): FoamResult {
        val dry = frame(1, whiteSink)
        repeat(dryFrames) { detector.analyze(dry, w, h, listOf(hand)) }
        var result = detector.analyze(last, w, h, listOf(hand))
        var guard = 0
        while (!result.skinReady && guard++ < 40) result = detector.analyze(last, w, h, listOf(hand))
        assertTrue("skin colour learned", result.skinReady)
        return result
    }

    // ---- Must NOT count as soap ---------------------------------------------------------

    @Test
    fun dryHandOverWhiteSinkIsNotSoap() {
        val r = analyze(12, frame(2, whiteSink))
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
    }

    @Test
    fun dryHandHoldingWhiteSoapBarIsNotSoap() {
        val r = analyze(12, frame(3, whiteSink, whiteSoapBar))
        assertTrue("white bar cut out: ${r.barShare}", r.barShare > 0.1f)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
    }

    @Test
    fun dryHandHoldingGreenSoapBarIsNotSoap() {
        val r = analyze(12, frame(12, whiteSink, greenSoapBar))
        assertTrue("green bar cut out: ${r.barShare}", r.barShare > 0.1f)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
    }

    @Test
    fun dryHandHoldingCreamSoapBarIsNotSoap() {
        val r = analyze(12, frame(13, whiteSink, creamSoapBar))
        assertTrue("cream bar cut out: ${r.barShare}", r.barShare > 0.1f)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
    }

    @Test
    fun orangeSoapBarIsCutOutAndDoesNotSpoilSkinColour() {
        val detector = FoamDetector()
        // The bar is in the hand from the very first frame, while skin colour is learned.
        val r = analyze(0, frame(4, whiteSink, orangeSoapBar), detector)
        assertTrue("bar share=${r.barShare}", r.barShare > 0.1f)
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
        assertTrue("skin saturation=${detector.skinS}", detector.skinS in 0.3f..0.5f)
    }

    @Test
    fun bubblyWhiteTowelBehindTheHandIsIgnored() {
        val towel: (Int, Int, Random) -> Int = { x, y, rnd ->
            val v = if ((x % 5 == 0) && (y % 5 == 0)) 252 else 220 + rnd.nextInt(-3, 4)
            rgb(v, v, v)
        }
        val r = analyze(12, frame(5, towel))
        assertTrue("coverage=${r.coverage}", r.coverage < 0.01f)
    }

    // ---- MUST count as soap -----------------------------------------------------------

    @Test
    fun thinBarSoapLatherIsSoap() {
        val r = analyze(12, frame(6, whiteSink, thinLather))
        assertTrue("sharp", r.sharp)
        assertTrue("coverage=${r.coverage}", r.coverage > WashConfig.FOAM_COVERAGE_THRESHOLD + WashConfig.FOAM_BASELINE_CAP)
    }

    @Test
    fun tintedLatherIsSoap() {
        val r = analyze(12, frame(14, whiteSink, tintedLather))
        assertTrue("lather is not mistaken for a bar: ${r.barShare}", r.barShare < 0.05f)
        assertTrue("coverage=${r.coverage}", r.coverage > WashConfig.FOAM_COVERAGE_THRESHOLD + WashConfig.FOAM_BASELINE_CAP)
    }

    @Test
    fun thickFoamIsSoap() {
        val r = analyze(12, frame(7, whiteSink, thickLather))
        assertTrue("coverage=${r.coverage}", r.coverage > 0.15f)
    }

    @Test
    fun latherWhileHoldingOrangeBarIsStillSoap() {
        val r = analyze(12, frame(8, whiteSink, thinLather, orangeSoapBar))
        assertTrue("coverage=${r.coverage}", r.coverage > WashConfig.FOAM_COVERAGE_THRESHOLD)
    }

    @Test
    fun latherFromTheFirstFrameIsStillFound() {
        val r = analyze(0, frame(9, whiteSink, thinLather))
        assertTrue("coverage=${r.coverage}", r.coverage > WashConfig.FOAM_COVERAGE_THRESHOLD)
    }

    // ---- Blur ---------------------------------------------------------------------------

    @Test
    fun motionBlurredFrameIsFlaggedNotJudged() {
        val detector = FoamDetector()
        analyze(12, frame(10, whiteSink, thinLather), detector) // sharp frames set the reference
        val blurred = detector.analyze(blur(frame(11, whiteSink, thinLather)), w, h, listOf(hand))
        assertFalse("sharpness=${blurred.sharpness} ref=${blurred.sharpnessRef}", blurred.sharp)
    }
}
