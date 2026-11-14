package com.sulaymoniy.soalarm.ring

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A 2D point in image pixels. */
data class Pt(val x: Float, val y: Float)

enum class WashHint(val text: String) {
    ShowHand("Show your hand to the camera"),
    AddSoap("Add soap"),
    RubHand("Rub your hand"),
    KeepGoing("Keep going!"),
}

/** Live values for the test-mode overlay. */
data class WashDebug(
    val handsVisible: Int = 0,
    val skinReady: Boolean = false,
    val foamCoverage: Float = 0f,
    val foamBaseline: Float = 0f,
    val foamNow: Boolean = false,
    val rubbing: Boolean = false,
    val rubHz: Float = 0f,
    val rubAmplitude: Float = 0f,
)

data class WashState(
    val washSeconds: Float = 0f,
    val hint: WashHint = WashHint.ShowHand,
    val washingNow: Boolean = false,
    val passed: Boolean = false,
    val frames: Long = 0,
    val debug: WashDebug = WashDebug(),
) {
    val progress: Float get() = (washSeconds / WashConfig.WASH_SECONDS_REQUIRED).coerceIn(0f, 1f)
}

/**
 * Decides, frame by frame, whether the user is washing with soap.
 *
 * The ring only fills while BOTH are true at the same moment:
 * - FOAM: new lather covers a clear part of the hand (see [FoamDetector]), measured
 *   against the hand's own lowest reading, so white skin highlights or a dry hand
 *   never count.
 * - RUBBING: a sustained back-and-forth of the fingers relative to the palm at
 *   1-4 times per second. Positions are measured in the hand's own frame (centred on
 *   the palm, rotated and scaled with it), so waving the whole hand, moving or shaking
 *   the phone, or holding an object still doesn't count.
 *
 * One hand is enough. Two hands sliding against each other also count. If the hand is
 * briefly lost, progress pauses and continues later; it never resets.
 *
 * Pure Kotlin (no Android types), so it can be unit tested.
 */
class HandWashTracker {
    private var lastTs = -1L
    private var frames = 0L

    private val tracks = mutableListOf<Track>()
    private val pairRhythm = Rhythm()
    private var pairSeen = -1L
    private var prevPair: Pair<Palm, Palm>? = null

    private var foamSmoothed = -1f
    private var foamBaselineMin = Float.MAX_VALUE

    private var washSeconds = 0f

    fun update(timestampMs: Long, hands: List<List<Pt>>, foamCoverage: Float, skinReady: Boolean = true): WashState {
        frames++
        val dt = if (lastTs < 0) 0f else ((timestampMs - lastTs) / 1000f).coerceIn(0f, 0.5f)
        lastTs = timestampMs

        val palms = hands.filter { it.size >= 21 }.map { Palm.of(it) }
        val visible = palms.isNotEmpty()

        // ---- Foam: smoothed, compared with the lowest level seen ("before" washing).
        var foamNow = false
        var baseline = 0f
        if (visible && skinReady) {
            foamSmoothed = if (foamSmoothed < 0f) foamCoverage else {
                val alpha = 1f - exp(-dt / WashConfig.FOAM_SMOOTHING_SECONDS)
                foamSmoothed + (foamCoverage - foamSmoothed) * alpha
            }
            foamBaselineMin = min(foamBaselineMin, foamSmoothed)
            baseline = min(foamBaselineMin, WashConfig.FOAM_BASELINE_CAP)
            foamNow = foamSmoothed - baseline >= WashConfig.FOAM_COVERAGE_THRESHOLD
        }

        // ---- Rubbing: rhythmic finger motion (one hand) or hands sliding (two hands).
        var best = RhythmResult.NONE
        for (palm in palms) {
            val r = trackFor(timestampMs, palm).rhythm.add(timestampMs, palm.fingerSignals())
            best = RhythmResult.better(best, r)
        }
        tracks.removeAll { timestampMs - it.lastSeen > WashConfig.HAND_TRACK_TIMEOUT_MS }
        if (palms.size >= 2) {
            pairSignals(palms[0], palms[1])?.let { best = RhythmResult.better(best, pairRhythm.add(timestampMs, it)) }
            pairSeen = timestampMs
        } else {
            prevPair = null
            if (pairSeen >= 0 && timestampMs - pairSeen > WashConfig.HAND_TRACK_TIMEOUT_MS) pairRhythm.clear()
        }
        val rubbing = visible && best.ok

        val washingNow = visible && foamNow && rubbing
        if (washingNow) washSeconds = (washSeconds + dt).coerceAtMost(WashConfig.WASH_SECONDS_REQUIRED)

        val hint = when {
            !visible -> WashHint.ShowHand
            !foamNow -> WashHint.AddSoap
            !rubbing -> WashHint.RubHand
            else -> WashHint.KeepGoing
        }
        return WashState(
            washSeconds = washSeconds,
            hint = hint,
            washingNow = washingNow,
            passed = washSeconds >= WashConfig.WASH_SECONDS_REQUIRED,
            frames = frames,
            debug = WashDebug(
                handsVisible = palms.size,
                skinReady = skinReady,
                foamCoverage = max(foamSmoothed, 0f),
                foamBaseline = baseline,
                foamNow = foamNow,
                rubbing = rubbing,
                rubHz = best.hz,
                rubAmplitude = best.amplitude,
            ),
        )
    }

    // ---- Following hands across frames -----------------------------------------------

    private class Track(var center: Pt, var lastSeen: Long) {
        val rhythm = Rhythm()
    }

    private fun trackFor(ts: Long, palm: Palm): Track {
        val track = tracks
            .filter { it.lastSeen != ts && hypot(it.center.x - palm.center.x, it.center.y - palm.center.y) < 2f * palm.size }
            .minByOrNull { hypot(it.center.x - palm.center.x, it.center.y - palm.center.y) }
            ?: Track(palm.center, ts).also { tracks.add(it) }
        track.center = palm.center
        track.lastSeen = ts
        return track
    }

    /** Two hands close together: their relative position and angle, in hand-lengths. */
    private fun pairSignals(first: Palm, second: Palm): FloatArray? {
        // MediaPipe may swap the order of hands between frames: match by position.
        var a = first
        var b = second
        prevPair?.let { (pa, pb) ->
            if (dist(a.center, pa.center) + dist(b.center, pb.center) >
                dist(a.center, pb.center) + dist(b.center, pa.center)
            ) {
                a = second; b = first
            }
        }
        prevPair = a to b
        val size = (a.size + b.size) / 2f
        if (dist(a.center, b.center) >= WashConfig.HANDS_CLOSE_FACTOR * size) return null
        // Camera shake moves both hands equally, so their difference cancels it out.
        var twist = a.angle - b.angle
        while (twist > PI) twist -= (2 * PI).toFloat()
        while (twist < -PI) twist += (2 * PI).toFloat()
        return floatArrayOf((a.center.x - b.center.x) / size, (a.center.y - b.center.y) / size, twist)
    }

    // ---- Rhythm detection ---------------------------------------------------------------

    class RhythmResult(val ok: Boolean, val hz: Float, val amplitude: Float) {
        companion object {
            val NONE = RhythmResult(false, 0f, 0f)
            fun better(a: RhythmResult, b: RhythmResult): RhythmResult = when {
                a.ok != b.ok -> if (a.ok) a else b
                else -> if (a.amplitude >= b.amplitude) a else b
            }
        }
    }

    /**
     * Keeps [WashConfig.RUB_WINDOW_MS] of a few motion signals and checks whether any of
     * them goes back and forth: enough amplitude, 1-4 cycles per second, several reversals.
     */
    private class Rhythm {
        private val times = ArrayDeque<Long>()
        private val values = ArrayDeque<FloatArray>()

        fun clear() {
            times.clear(); values.clear()
        }

        fun add(ts: Long, signals: FloatArray): RhythmResult {
            times.addLast(ts)
            values.addLast(signals)
            while (times.size > 2 && ts - times.first() > WashConfig.RUB_WINDOW_MS) {
                times.removeFirst(); values.removeFirst()
            }
            return analyze()
        }

        private fun analyze(): RhythmResult {
            val span = (times.last() - times.first()) / 1000f
            if (span < WashConfig.RUB_WINDOW_MS / 1000f * 0.8f || times.size < 8) return RhythmResult.NONE
            var best = RhythmResult.NONE
            for (c in values.first().indices) {
                var mean = 0f
                for (v in values) mean += v[c]
                mean /= values.size
                var variance = 0f
                for (v in values) variance += (v[c] - mean) * (v[c] - mean)
                val std = sqrt(variance / values.size)
                // Count direction changes with a dead band, so jitter isn't counted.
                val band = max(0.35f * std, WashConfig.RUB_MIN_AMPLITUDE * 0.5f)
                var side = 0
                var reversals = 0
                for (v in values) {
                    val d = v[c] - mean
                    val now = if (d > band) 1 else if (d < -band) -1 else side
                    if (now != side && side != 0) reversals++
                    side = now
                }
                val hz = reversals / 2f / span
                val ok = std >= WashConfig.RUB_MIN_AMPLITUDE &&
                    reversals >= WashConfig.RUB_MIN_REVERSALS &&
                    hz >= WashConfig.RUB_MIN_HZ && hz <= WashConfig.RUB_MAX_HZ
                best = RhythmResult.better(best, RhythmResult(ok, hz, std))
            }
            return best
        }
    }

    // ---- Geometry -------------------------------------------------------------------------

    private class Palm(val center: Pt, val size: Float, val angle: Float, private val lm: List<Pt>) {
        /**
         * Finger positions in the hand's own frame (centred on the palm, rotated with it,
         * in hand-lengths): thumb x/y, fingers x/y, and how far the fingers reach out.
         * Whole-hand movement, tilting and zooming leave these unchanged.
         */
        fun fingerSignals(): FloatArray {
            val c = cos(-angle)
            val s = sin(-angle)
            fun local(i: Int): Pt {
                val dx = lm[i].x - center.x
                val dy = lm[i].y - center.y
                return Pt((dx * c - dy * s) / size, (dx * s + dy * c) / size)
            }
            var tx = 0f; var ty = 0f
            for (i in THUMB) { val p = local(i); tx += p.x; ty += p.y }
            var fx = 0f; var fy = 0f; var reach = 0f
            for (i in FINGERS) { val p = local(i); fx += p.x; fy += p.y; reach += hypot(p.x, p.y) }
            return floatArrayOf(
                tx / THUMB.size, ty / THUMB.size,
                fx / FINGERS.size, fy / FINGERS.size, reach / FINGERS.size,
            )
        }

        companion object {
            /** Wrist and finger bases: the stiff part of the hand. */
            private val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)
            private val THUMB = intArrayOf(3, 4)
            private val FINGERS = intArrayOf(6, 8, 10, 12, 14, 16, 18, 20)

            fun of(lm: List<Pt>): Palm {
                val cx = PALM_POINTS.map { lm[it].x }.average().toFloat()
                val cy = PALM_POINTS.map { lm[it].y }.average().toFloat()
                val size = dist(lm[0], lm[9]).coerceAtLeast(1f)
                val angle = atan2(lm[9].y - lm[0].y, lm[9].x - lm[0].x)
                return Palm(Pt(cx, cy), size, angle, lm)
            }
        }
    }

    private companion object {
        fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)
    }
}
