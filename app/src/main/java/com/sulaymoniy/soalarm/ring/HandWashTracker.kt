package com.sulaymoniy.soalarm.ring

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/** A 2D point in image pixels. */
data class Pt(val x: Float, val y: Float)

enum class WashHint(val text: String) {
    ShowBothHands("Show both hands"),
    RubHands("Rub hands together"),
    AddSoap("Add soap"),
    KeepGoing("Great, keep washing!"),
}

data class WashState(
    val washSeconds: Float = 0f,
    val foamSeconds: Float = 0f,
    val hint: WashHint = WashHint.ShowBothHands,
    val washingNow: Boolean = false,
    val foamNow: Boolean = false,
    val passed: Boolean = false,
    val frames: Long = 0,
) {
    val washProgress: Float get() = (washSeconds / WashConfig.WASH_SECONDS_REQUIRED).coerceIn(0f, 1f)
    val foamProgress: Float get() = (foamSeconds / WashConfig.FOAM_SECONDS_REQUIRED).coerceIn(0f, 1f)
}

/**
 * Decides, frame by frame, whether the user is washing their hands.
 *
 * Washing = both hands visible + close together + moving against each other.
 * Progress only grows while washing is detected. Soap is counted while washing
 * and the foam share around the hands is above [WashConfig.FOAM_PIXEL_THRESHOLD].
 *
 * Pure Kotlin (no Android types) so it is easy to reason about and test.
 */
class HandWashTracker {
    private var lastTs = -1L
    private var lastBothHandsTs = -1L
    private var frames = 0L

    private var prevA: Palm? = null
    private var prevB: Palm? = null
    private var prevSingle: Palm? = null
    private var handsClose = false
    private var smoothedSpeed = 0f

    private var washSeconds = 0f
    private var foamSeconds = 0f

    fun update(timestampMs: Long, hands: List<List<Pt>>, foamRatio: Float): WashState {
        frames++
        val dt = if (lastTs < 0) 0f else ((timestampMs - lastTs) / 1000f).coerceIn(0f, 0.5f)
        lastTs = timestampMs

        val palms = hands.filter { it.size >= 21 }.map { Palm.of(it) }
        val motion: Float? = when {
            palms.size >= 2 -> twoHandMotion(palms[0], palms[1]).also { lastBothHandsTs = timestampMs }
            palms.size == 1 && withinGrace(timestampMs) -> singleHandMotion(palms[0])
            else -> {
                prevA = null; prevB = null; prevSingle = null
                null
            }
        }

        // Exponential smoothing of rubbing speed (time constant ~0.4 s).
        if (dt > 0f) {
            val speed = if (motion != null) motion / dt else 0f
            val alpha = 1f - exp(-dt / 0.4f)
            smoothedSpeed += (speed - smoothedSpeed) * alpha
        }

        val bothVisible = withinGrace(timestampMs)
        val rubbing = smoothedSpeed >= WashConfig.RUB_SPEED_THRESHOLD
        val washingNow = bothVisible && handsClose && rubbing
        val foamNow = washingNow && foamRatio >= WashConfig.FOAM_PIXEL_THRESHOLD

        if (washingNow) washSeconds = (washSeconds + dt).coerceAtMost(WashConfig.WASH_SECONDS_REQUIRED)
        if (foamNow) foamSeconds = (foamSeconds + dt).coerceAtMost(WashConfig.FOAM_SECONDS_REQUIRED)

        val soapDone = foamSeconds >= WashConfig.FOAM_SECONDS_REQUIRED
        val hint = when {
            !bothVisible -> WashHint.ShowBothHands
            !handsClose || !rubbing -> WashHint.RubHands
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

    private fun withinGrace(ts: Long) =
        lastBothHandsTs >= 0 && ts - lastBothHandsTs <= WashConfig.HAND_LOSS_GRACE_MS

    /** Movement of the hands relative to each other since the last frame, in hand-lengths. */
    private fun twoHandMotion(first: Palm, second: Palm): Float? {
        // MediaPipe may swap the order of hands between frames: match by position.
        var a = first
        var b = second
        val pa = prevA
        val pb = prevB
        if (pa != null && pb != null &&
            dist(a.center, pa.center) + dist(b.center, pb.center) >
            dist(a.center, pb.center) + dist(b.center, pa.center)
        ) {
            a = second; b = first
        }

        val size = (a.size + b.size) / 2f
        handsClose = dist(a.center, b.center) < WashConfig.HANDS_CLOSE_FACTOR * size

        val result = if (pa != null && pb != null) {
            // Relative offset change cancels out camera shake that moves both hands equally.
            val relNow = Pt(a.center.x - b.center.x, a.center.y - b.center.y)
            val relPrev = Pt(pa.center.x - pb.center.x, pa.center.y - pb.center.y)
            val slide = dist(relNow, relPrev) / size
            val twist = abs(angleDiff(a.angle - b.angle, pa.angle - pb.angle))
            // Fingertips sliding over the other hand also count.
            val fingers = (a.fingerSpread(pa) + b.fingerSpread(pb)) / 2f / size
            slide + 0.5f * twist + 0.5f * fingers
        } else {
            null
        }
        prevA = a
        prevB = b
        prevSingle = null
        return result
    }

    /** When rubbing hands merge into one detection, use that hand's own movement. */
    private fun singleHandMotion(hand: Palm): Float? {
        val prev = prevSingle
        prevSingle = hand
        prevA = null; prevB = null
        if (prev == null) return null
        return dist(hand.center, prev.center) / hand.size + 0.5f * hand.fingerSpread(prev) / hand.size
    }

    private class Palm(val center: Pt, val size: Float, val angle: Float, val tips: List<Pt>) {
        /** Average fingertip movement relative to the palm centre. */
        fun fingerSpread(prev: Palm): Float {
            var sum = 0f
            for (i in tips.indices) {
                val now = Pt(tips[i].x - center.x, tips[i].y - center.y)
                val before = Pt(prev.tips[i].x - prev.center.x, prev.tips[i].y - prev.center.y)
                sum += dist(now, before)
            }
            return sum / tips.size
        }

        companion object {
            private val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)
            private val TIP_POINTS = intArrayOf(4, 8, 12, 16, 20)

            fun of(lm: List<Pt>): Palm {
                val cx = PALM_POINTS.map { lm[it].x }.average().toFloat()
                val cy = PALM_POINTS.map { lm[it].y }.average().toFloat()
                val wrist = lm[0]
                val middleBase = lm[9]
                val size = dist(wrist, middleBase).coerceAtLeast(1f)
                val angle = atan2(middleBase.y - wrist.y, middleBase.x - wrist.x)
                return Palm(Pt(cx, cy), size, angle, TIP_POINTS.map { lm[it] })
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
