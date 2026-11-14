package com.sulaymoniy.soalarm.ring

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** A 2D point in image pixels. */
data class Pt(val x: Float, val y: Float)

enum class WashHint(val text: String) {
    ShowHand("Show your hand to the camera"),
    RubHand("Rub your hand"),
    AddSoap("Add soap"),
    KeepGoing("Keep going!"),
}

data class WashState(
    val washSeconds: Float = 0f,
    val foamSeconds: Float = 0f,
    val hint: WashHint = WashHint.ShowHand,
    val washingNow: Boolean = false,
    val foamNow: Boolean = false,
    val passed: Boolean = false,
    val frames: Long = 0,
) {
    val washProgress: Float get() = (washSeconds / WashConfig.WASH_SECONDS_REQUIRED).coerceIn(0f, 1f)
    val foamProgress: Float get() = (foamSeconds / WashConfig.FOAM_SECONDS_REQUIRED).coerceIn(0f, 1f)
}

/**
 * Decides, frame by frame, whether the user is washing.
 *
 * ONE hand is enough (the other may be holding the phone):
 * - One hand: its fingers must move relative to its own palm (rubbing, opening and
 *   closing, thumb rubbing the palm). Positions are measured in the hand's own frame
 *   (centred on the palm, rotated and scaled with it), so moving, tilting or zooming
 *   the phone does not count as washing.
 * - Two hands close together sliding against each other also count.
 *
 * Progress only grows while washing is detected. If the hand is briefly lost,
 * progress pauses and continues later; it never resets. Soap is counted while
 * washing and the foam share around the visible hand(s) is above
 * [WashConfig.FOAM_PIXEL_THRESHOLD].
 *
 * Pure Kotlin (no Android types), so it can be unit tested.
 */
class HandWashTracker {
    private var lastTs = -1L
    private var frames = 0L

    private val tracks = mutableListOf<Track>()
    private var prevPair: Pair<Palm, Palm>? = null

    /** Smoothed "rub score": 1.0 = exactly at the threshold. */
    private var smoothedScore = 0f

    private var washSeconds = 0f
    private var foamSeconds = 0f

    fun update(timestampMs: Long, hands: List<List<Pt>>, foamRatio: Float): WashState {
        frames++
        val dt = if (lastTs < 0) 0f else ((timestampMs - lastTs) / 1000f).coerceIn(0f, 0.5f)
        lastTs = timestampMs

        val palms = hands.filter { it.size >= 21 }.map { Palm.of(it) }
        val handVisible = palms.isNotEmpty()

        // Signal 1: fingers moving relative to their own palm (works with one hand).
        val fingerSpeed = updateTracks(timestampMs, palms)
        // Signal 2: two hands close together sliding against each other.
        val pairSpeed = if (palms.size >= 2) twoHandSpeed(palms[0], palms[1], dt) else {
            prevPair = null
            0f
        }

        val score = max(
            fingerSpeed / WashConfig.SINGLE_HAND_RUB_THRESHOLD,
            pairSpeed / WashConfig.RUB_SPEED_THRESHOLD,
        )
        if (dt > 0f) {
            val alpha = 1f - exp(-dt / WashConfig.RUB_SMOOTHING_SECONDS)
            smoothedScore += ((if (handVisible) score else 0f) - smoothedScore) * alpha
        }

        val rubbing = handVisible && smoothedScore >= 1f
        val washingNow = rubbing
        val foamNow = washingNow && foamRatio >= WashConfig.FOAM_PIXEL_THRESHOLD

        if (washingNow) washSeconds = (washSeconds + dt).coerceAtMost(WashConfig.WASH_SECONDS_REQUIRED)
        if (foamNow) foamSeconds = (foamSeconds + dt).coerceAtMost(WashConfig.FOAM_SECONDS_REQUIRED)

        val soapDone = foamSeconds >= WashConfig.FOAM_SECONDS_REQUIRED
        val hint = when {
            !handVisible -> WashHint.ShowHand
            !rubbing -> WashHint.RubHand
            !soapDone && !foamNow -> WashHint.AddSoap
            else -> WashHint.KeepGoing
        }
        return WashState(
            washSeconds = washSeconds,
            foamSeconds = foamSeconds,
            hint = hint,
            washingNow = washingNow,
            foamNow = foamNow,
            passed = washSeconds >= WashConfig.WASH_SECONDS_REQUIRED && soapDone,
            frames = frames,
        )
    }

    // ---- Signal 1: fingers vs. palm, per hand -------------------------------------------

    /** Follows each hand across frames and returns the fastest finger movement (hand-lengths/s). */
    private fun updateTracks(ts: Long, palms: List<Palm>): Float {
        tracks.removeAll { ts - it.lastSeen > WashConfig.HAND_TRACK_TIMEOUT_MS }

        var best = 0f
        val used = mutableSetOf<Track>()
        for (palm in palms) {
            // Same hand as before = the nearest track, if it's not too far away.
            val track = tracks
                .filter { it !in used && dist(it.center, palm.center) < 2f * palm.size }
                .minByOrNull { dist(it.center, palm.center) }
                ?: Track().also { tracks.add(it) }
            used += track
            track.center = palm.center
            track.lastSeen = ts
            track.history.addLast(ts to palm.localFingers)
            while (track.history.size > 2 && ts - track.history.first().first > WashConfig.FINGER_MOTION_WINDOW_MS * 3) {
                track.history.removeFirst()
            }
            best = max(best, track.fingerSpeed(ts))
        }
        return best
    }

    private class Track {
        var center = Pt(0f, 0f)
        var lastSeen = 0L
        val history = ArrayDeque<Pair<Long, List<Pt>>>()

        /**
         * Average finger movement in the hand's own frame, compared with how the hand looked
         * about [WashConfig.FINGER_MOTION_WINDOW_MS] ago. Using a short window (instead of
         * frame-to-frame) keeps tiny detection jitter from adding up to fake motion.
         */
        fun fingerSpeed(now: Long): Float {
            val (pastTs, past) = history.lastOrNull { now - it.first >= WashConfig.FINGER_MOTION_WINDOW_MS }
                ?: return 0f
            val current = history.last().second
            var sum = 0f
            for (i in current.indices) sum += dist(current[i], past[i])
            val seconds = (now - pastTs) / 1000f
            return if (seconds <= 0f) 0f else sum / current.size / seconds
        }
    }

    // ---- Signal 2: two hands sliding against each other ----------------------------------

    private fun twoHandSpeed(first: Palm, second: Palm, dt: Float): Float {
        // MediaPipe may swap the order of hands between frames: match by position.
        var a = first
        var b = second
        val prev = prevPair
        if (prev != null &&
            dist(a.center, prev.first.center) + dist(b.center, prev.second.center) >
            dist(a.center, prev.second.center) + dist(b.center, prev.first.center)
        ) {
            a = second; b = first
        }
        prevPair = a to b

        val size = (a.size + b.size) / 2f
        val close = dist(a.center, b.center) < WashConfig.HANDS_CLOSE_FACTOR * size
        if (prev == null || !close || dt <= 0f) return 0f

        // Relative offset change cancels out camera shake that moves both hands equally.
        val relNow = Pt(a.center.x - b.center.x, a.center.y - b.center.y)
        val relPrev = Pt(prev.first.center.x - prev.second.center.x, prev.first.center.y - prev.second.center.y)
        val slide = dist(relNow, relPrev) / size
        val twist = abs(angleDiff(a.angle - b.angle, prev.first.angle - prev.second.angle))
        return (slide + 0.5f * twist) / dt
    }

    // ---- Geometry -------------------------------------------------------------------------

    private class Palm(val center: Pt, val size: Float, val angle: Float, val localFingers: List<Pt>) {
        companion object {
            /** Wrist and finger bases: the stiff part of the hand. */
            private val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)

            /** Thumb and finger joints that move when washing: tips plus the middle joints. */
            private val FINGER_POINTS = intArrayOf(3, 4, 6, 8, 10, 12, 14, 16, 18, 20)

            fun of(lm: List<Pt>): Palm {
                val cx = PALM_POINTS.map { lm[it].x }.average().toFloat()
                val cy = PALM_POINTS.map { lm[it].y }.average().toFloat()
                val wrist = lm[0]
                val middleBase = lm[9]
                val size = dist(wrist, middleBase).coerceAtLeast(1f)
                val angle = atan2(middleBase.y - wrist.y, middleBase.x - wrist.x)
                // Express finger points in the hand's own frame: centred on the palm,
                // rotated so the hand always "points" the same way, measured in hand-lengths.
                val c = cos(-angle)
                val s = sin(-angle)
                val local = FINGER_POINTS.map { i ->
                    val dx = lm[i].x - cx
                    val dy = lm[i].y - cy
                    Pt((dx * c - dy * s) / size, (dx * s + dy * c) / size)
                }
                return Palm(Pt(cx, cy), size, angle, local)
            }
        }
    }

    private companion object {
        fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

        fun angleDiff(a: Float, b: Float): Float {
            var d = a - b
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return d
        }
    }
}
