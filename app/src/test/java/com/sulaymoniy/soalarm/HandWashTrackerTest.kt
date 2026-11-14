package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.ring.HandWashTracker
import com.sulaymoniy.soalarm.ring.Pt
import com.sulaymoniy.soalarm.ring.WashConfig
import com.sulaymoniy.soalarm.ring.WashHint
import com.sulaymoniy.soalarm.ring.WashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HandWashTrackerTest {

    /**
     * A fake MediaPipe hand (21 landmarks). [curl] 0 = open hand, 1 = fist.
     * [size] is roughly wrist-to-middle-knuckle in pixels; [angle] rotates the whole hand.
     */
    private fun hand(cx: Float, cy: Float, curl: Float = 0.2f, size: Float = 100f, angle: Float = 0f): List<Pt> {
        // Local layout in hand-lengths, fingers pointing "up" (negative y).
        val local = MutableList(21) { Pt(0f, 0f) }
        local[0] = Pt(0f, 0.5f) // wrist
        // Thumb 1-4
        val open = 1f - curl
        local[1] = Pt(-0.25f, 0.3f)
        local[2] = Pt(-0.4f, 0.1f)
        local[3] = Pt(-0.4f - 0.15f * open, -0.1f * open)
        local[4] = Pt(-0.4f - 0.25f * open, -0.3f * open + 0.1f * curl)
        // Fingers: base knuckle x positions, knuckles at y = -0.5
        val bases = floatArrayOf(-0.3f, 0f, 0.25f, 0.45f)
        for ((f, bx) in bases.withIndex()) {
            val i = 5 + f * 4
            local[i] = Pt(bx, -0.5f)
            local[i + 1] = Pt(bx, -0.5f - 0.35f * open)
            local[i + 2] = Pt(bx, -0.5f - 0.6f * open + 0.15f * curl)
            local[i + 3] = Pt(bx, -0.5f - 0.8f * open + 0.3f * curl)
        }
        val c = cos(angle)
        val s = sin(angle)
        return local.map { p ->
            Pt(cx + (p.x * c - p.y * s) * size, cy + (p.x * s + p.y * c) * size)
        }
    }

    private fun run(
        seconds: Int,
        foam: Float,
        frame: (t: Double) -> List<List<Pt>>,
    ): WashState {
        val tracker = HandWashTracker()
        var state = WashState()
        val fps = 15
        for (i in 0 until seconds * fps) {
            val t = i / fps.toDouble()
            state = tracker.update((t * 1000).toLong(), frame(t), foam)
        }
        return state
    }

    /** One hand opening and closing / rubbing its fingers, about once per second. */
    private fun washingHand(t: Double) = hand(320f, 300f, curl = (0.5 + 0.4 * sin(t * 6)).toFloat())

    @Test
    fun noHandAsksToShowHand() {
        val s = run(5, 0f) { emptyList() }
        assertEquals(WashHint.ShowHand, s.hint)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun stillHandAsksToRub() {
        val s = run(10, 0.5f) { listOf(hand(320f, 300f)) }
        assertEquals(WashHint.RubHand, s.hint)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun oneHandWashingWithSoapPasses() {
        val s = run(25, 0.3f) { t -> listOf(washingHand(t)) }
        assertTrue("wash=${s.washSeconds}", s.passed)
    }

    @Test
    fun oneHandWithoutSoapAsksForSoap() {
        val s = run(25, 0f) { t -> listOf(washingHand(t)) }
        assertTrue(s.washSeconds > 19f)
        assertEquals(WashHint.AddSoap, s.hint)
        assertFalse(s.passed)
    }

    @Test
    fun cameraShakeAloneDoesNotCount() {
        // Still fingers, but the whole hand jumps around, tilts and zooms like a shaky phone.
        val s = run(15, 0.5f) { t ->
            listOf(
                hand(
                    cx = 320f + (60 * sin(t * 9)).toFloat(),
                    cy = 300f + (40 * cos(t * 7)).toFloat(),
                    size = 100f + (10 * sin(t * 5)).toFloat(),
                    angle = (0.25 * sin(t * 8)).toFloat(),
                ),
            )
        }
        assertEquals(0f, s.washSeconds)
        assertEquals(WashHint.RubHand, s.hint)
    }

    @Test
    fun twoHandsRubbingStillWork() {
        val s = run(25, 0.3f) { t ->
            val dx = (40 * sin(t * 8)).toFloat()
            listOf(hand(300f + dx, 300f), hand(380f - dx, 300f))
        }
        assertTrue(s.passed)
    }

    @Test
    fun briefDropPausesButDoesNotReset() {
        val tracker = HandWashTracker()
        var state = WashState()
        val fps = 15
        for (i in 0 until 12 * fps) {
            val t = i / fps.toDouble()
            // Hand disappears for 0.6 s in the middle.
            val hands = if (t in 6.0..6.6) emptyList() else listOf(washingHand(t))
            state = tracker.update((t * 1000).toLong(), hands, 0.3f)
            if (t in 6.1..6.6) {
                assertEquals(WashHint.ShowHand, state.hint)
                assertFalse(state.washingNow)
            }
        }
        // ~12 s of washing minus the gap and the short start-up: progress kept, not reset.
        assertTrue("wash=${state.washSeconds}", state.washSeconds > 9f)
        assertTrue(state.washSeconds < WashConfig.WASH_SECONDS_REQUIRED)
    }
}
