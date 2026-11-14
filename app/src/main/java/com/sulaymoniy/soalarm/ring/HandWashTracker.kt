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
    CheckingSoap("Soap found, keep lathering…"),
    RubHand("Rub your hand"),
    KeepGoing("Keep going!"),
}

/** Live values for the test-mode overlay. */
data class WashDebug(
    val handsVisible: Int = 0,
    val skinReady: Boolean = false,
    val sharp: Boolean = false,
    val foamCoverage: Float = 0f,
    val foamBaseline: Float = 0f,
    val foamNow: Boolean = false,
    val foamSeconds: Float = 0f,
    val rubbing: Boolean = false,
    val rubHz: Float = 0f,
    val rubAmplitude: Float = 0f,
    /** Frames analysed per second (rubbing needs a few per back-and-forth). */
    val fps: Float = 0f,
)

data class WashState(
    val washSeconds: Float = 0f,
    val hint: WashHint = WashHint.ShowHand,
    val washingNow: Boolean = false,
    /** Soap has been confirmed on the hand; from now on only rubbing is needed. */
    val soaped: Boolean = false,
    val passed: Boolean = false,
    val frames: Long = 0,
    val debug: WashDebug = WashDebug(),
) {
    val progress: Float get() = (washSeconds / WashConfig.WASH_SECONDS_REQUIRED).coerceIn(0f, 1f)
}

/**
 * Decides, frame by frame, whether the user is washing with soap.
 *
 * 1. SOAP must be CONFIRMED once: soap (see [FoamDetector]) seen on sharp frames for
 *    [WashConfig.FOAM_CONFIRM_SECONDS] in total, measured against the hand's own lowest
 *    reading. Blurry frames are skipped, not counted as "no soap". After that the hand is
 *    "soaped" for the rest of the alarm. (The soap reading keeps updating for the test view.)
 * 2. Then only RUBBING fills the ring: a back-and-forth of the hand's parts against each
 *    other (fingers vs. palm, thumb vs. fingers) at 0.7-5 times per second. Positions are
 *    measured in the hand's own frame (centred on the palm, rotated and scaled with it), so
 *    waving the whole hand, moving or shaking the phone, or holding an object still doesn't
 *    count. A short gap keeps counting for [WashConfig.RUB_HOLD_MS].
 *
 * One hand is enough. Two hands sliding against each other also count. Hands are followed
 * by position, so MediaPipe changing the order of the hands doesn't matter. A hand that is
 * gone for [WashConfig.HAND_TRACK_TIMEOUT_MS] is forgotten and starts fresh when it comes
 * back. Progress pauses while no hand is visible; it never resets.
 *
 * Pure Kotlin (no Android types), so it can be unit tested.
 */
class HandWashTracker(initiallySoaped: Boolean = false) {
    private var lastTs = -1L
    private var frames = 0L
    private var fps = 0f

    private val tracks = mutableListOf<Track>()
    private val pairRhythm = Rhythm()
    private var pairSeen = -1L
    private var prevPair: Pair<Palm, Palm>? = null
    private var lastRubOkTs = -1L
    private var lastHandTs = -1L

    private var foamSmoothed = -1f
    private var lastFoamTs = -1L
    private var foamBaselineMin = Float.MAX_VALUE
    private var foamNow = false
    private var foamSeconds = 0f
    private var soaped = initiallySoaped

    private var washSeconds = 0f

    /** Soap has been confirmed (also true if confirmed earlier in this alarm). */
    val isSoaped: Boolean get() = soaped

    /**
     * [sharp] = false means the frame is too blurry to judge soap: the soap check skips it
     * (rubbing is still measured). [foamCoverage] < 0 means "not measured this frame".
     */
    fun update(
        timestampMs: Long,
        hands: List<List<Pt>>,
        foamCoverage: Float,
        skinReady: Boolean = true,
        sharp: Boolean = true,
    ): WashState {
        frames++
        val dt = if (lastTs < 0) 0f else ((timestampMs - lastTs) / 1000f).coerceIn(0f, 0.5f)
        if (dt > 0f) fps = if (fps == 0f) 1f / dt else fps + (1f / dt - fps) * 0.1f
        lastTs = timestampMs

        val palms = hands.filter { it.size >= 21 }.map { Palm.of(it) }
        val visible = palms.isNotEmpty()

        // A hand that has been gone long enough starts completely fresh.
        if (visible) {
            if (lastHandTs >= 0 && timestampMs - lastHandTs > WashConfig.HAND_TRACK_TIMEOUT_MS) resetHands()
            lastHandTs = timestampMs
        }

        // ---- Soap: only on sharp frames; smoothed; compared with the lowest level ("before").
        if (visible && skinReady && sharp && foamCoverage >= 0f) {
            val fdt = if (lastFoamTs < 0) 0f else ((timestampMs - lastFoamTs) / 1000f).coerceIn(0f, 0.5f)
            lastFoamTs = timestampMs
            foamSmoothed = if (foamSmoothed < 0f) foamCoverage else {
                val alpha = 1f - exp(-fdt / WashConfig.FOAM_SMOOTHING_SECONDS)
                foamSmoothed + (foamCoverage - foamSmoothed) * alpha
            }
            foamBaselineMin = min(foamBaselineMin, foamSmoothed)
            foamNow = foamSmoothed - foamBaseline() >= WashConfig.FOAM_COVERAGE_THRESHOLD
            if (!soaped) {
                foamSeconds = if (foamNow) {
                    foamSeconds + fdt
                } else {
                    max(0f, foamSeconds - fdt * WashConfig.FOAM_CONFIRM_LEAK_PER_SECOND)
                }
                if (foamSeconds >= WashConfig.FOAM_CONFIRM_SECONDS) soaped = true
            }
        } else if (!visible) {
            foamNow = false
        }

        // ---- Rubbing: rhythmic motion of the hand's parts (one hand) or hands sliding (two).
        var best = RhythmResult.NONE
        for (palm in palms) {
            best = RhythmResult.better(best, trackFor(timestampMs, palm).rhythm.add(timestampMs, palm.signals()))
        }
        tracks.removeAll { timestampMs - it.lastSeen > WashConfig.HAND_TRACK_TIMEOUT_MS }
        if (palms.size >= 2) {
            pairSignals(palms[0], palms[1])?.let { best = RhythmResult.better(best, pairRhythm.add(timestampMs, it)) }
            pairSeen = timestampMs
        } else {
            prevPair = null
            if (pairSeen >= 0 && timestampMs - pairSeen > WashConfig.HAND_TRACK_TIMEOUT_MS) pairRhythm.clear()
        }
        if (visible && best.ok) lastRubOkTs = timestampMs
        // Short gaps between valid readings don't stop the count.
        val rubbing = visible && lastRubOkTs >= 0 && timestampMs - lastRubOkTs <= WashConfig.RUB_HOLD_MS

        // The ring only fills once soap is confirmed, and only while rubbing.
        val washingNow = visible && soaped && rubbing
        if (washingNow) washSeconds = (washSeconds + dt).coerceAtMost(WashConfig.WASH_SECONDS_REQUIRED)

        val hint = when {
            !visible -> WashHint.ShowHand
            !soaped && foamSeconds > 0f -> WashHint.CheckingSoap
            !soaped -> WashHint.AddSoap
            !rubbing -> WashHint.RubHand
            else -> WashHint.KeepGoing
        }
        return WashState(
            washSeconds = washSeconds,
            hint = hint,
            washingNow = washingNow,
            soaped = soaped,
            passed = washSeconds >= WashConfig.WASH_SECONDS_REQUIRED,
            frames = frames,
            debug = WashDebug(
                handsVisible = palms.size,
                skinReady = skinReady,
                sharp = sharp,
                foamCoverage = max(foamSmoothed, 0f),
                foamBaseline = if (foamBaselineMin == Float.MAX_VALUE) 0f else foamBaseline(),
                foamNow = foamNow,
                foamSeconds = if (soaped) WashConfig.FOAM_CONFIRM_SECONDS else foamSeconds,
                rubbing = rubbing,
                rubHz = best.hz,
                rubAmplitude = best.amplitude,
                fps = fps,
            ),
        )
    }

    private fun foamBaseline() = min(foamBaselineMin, WashConfig.FOAM_BASELINE_CAP)

    private fun resetHands() {
        tracks.clear()
        pairRhythm.clear()
        prevPair = null
        pairSeen = -1L
    }

    // ---- Following hands across frames -----------------------------------------------

    private class Track(var center: Pt, var lastSeen: Long) {
        val rhythm = Rhythm()
    }

    /** The same hand as before = the nearest recent track; otherwise a fresh one. */
    private fun trackFor(ts: Long, palm: Palm): Track {
        val track = tracks
            .filter { it.lastSeen != ts && dist(it.center, palm.center) < 2f * palm.size }
            .minByOrNull { dist(it.center, palm.center) }
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
     * them goes back and forth: enough amplitude, the right speed, enough reversals.
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
            if (times.size < WashConfig.RUB_MIN_SAMPLES) return RhythmResult.NONE
            if (times.last() - times.first() < WashConfig.RUB_WINDOW_MS / 2) return RhythmResult.NONE
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
                var firstRev = -1L
                var lastRev = -1L
                for ((k, v) in values.withIndex()) {
                    val d = v[c] - mean
                    val now = if (d > band) 1 else if (d < -band) -1 else side
                    if (now != side && side != 0) {
                        reversals++
                        if (firstRev < 0) firstRev = times[k]
                        lastRev = times[k]
                    }
                    side = now
                }
                // Frequency from the time between reversals (two reversals = one cycle).
                val hz = if (reversals >= 2 && lastRev > firstRev) {
                    (reversals - 1) / 2f / ((lastRev - firstRev) / 1000f)
                } else {
                    0f
                }
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
         * Motion signals in the hand's own frame (centred on the palm, rotated with it, in
         * hand-lengths). Whole-hand movement, tilting and zooming leave all of them unchanged:
         * thumb x/y, fingers x/y, finger reach, thumb vs. fingers x/y, and fingertips vs.
         * their own knuckles (curl), so palm, thumb and finger rubbing all show up.
         */
        fun signals(): FloatArray {
            val c = cos(-angle)
            val s = sin(-angle)
            fun local(i: Int): Pt {
                val dx = lm[i].x - center.x
                val dy = lm[i].y - center.y
                return Pt((dx * c - dy * s) / size, (dx * s + dy * c) / size)
            }
            var tx = 0f; var ty = 0f
            for (i in THUMB) { val p = local(i); tx += p.x; ty += p.y }
            tx /= THUMB.size; ty /= THUMB.size
            var fx = 0f; var fy = 0f; var reach = 0f
            for (i in FINGERS) { val p = local(i); fx += p.x; fy += p.y; reach += hypot(p.x, p.y) }
            fx /= FINGERS.size; fy /= FINGERS.size; reach /= FINGERS.size
            var curl = 0f
            for (k in TIPS.indices) curl += dist(lm[TIPS[k]], lm[KNUCKLES[k]]) / size
            curl /= TIPS.size
            return floatArrayOf(tx, ty, fx, fy, reach, tx - fx, ty - fy, curl)
        }

        companion object {
            /** Wrist and finger bases: the stiff part of the hand. */
            private val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)
            private val THUMB = intArrayOf(3, 4)
            private val FINGERS = intArrayOf(6, 8, 10, 12, 14, 16, 18, 20)
            private val TIPS = intArrayOf(8, 12, 16, 20)
            private val KNUCKLES = intArrayOf(5, 9, 13, 17)

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
