package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.ring.HandWashTracker
import com.sulaymoniy.soalarm.ring.Pt
import com.sulaymoniy.soalarm.ring.WashHint
import com.sulaymoniy.soalarm.ring.WashState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HandWashTrackerTest {

    /** A fake hand: 21 landmarks spread around (cx, cy), about 100 px long. */
    private fun hand(cx: Float, cy: Float): List<Pt> =
        List(21) { i ->
            val angle = i * 0.3
            val r = if (i == 0) 50f else 30f + (i % 4) * 8f
            Pt(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat() - if (i == 9) 50f else 0f)
        }.toMutableList().also { it[0] = Pt(cx, cy + 50f); it[9] = Pt(cx, cy - 50f) }

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

    @Test
    fun noHandsAsksForBothHands() {
        val s = run(5, 0f) { emptyList() }
        assertEquals(WashHint.ShowBothHands, s.hint)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun stillHandsDoNotCount() {
        val s = run(10, 0.5f) { listOf(hand(300f, 300f), hand(380f, 300f)) }
        assertEquals(WashHint.RubHands, s.hint)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun rubbingWithoutSoapAsksForSoap() {
        val s = run(25, 0f) { t -> rubbing(t) }
        assertTrue(s.washSeconds > 19f)
        assertEquals(WashHint.AddSoap, s.hint)
        assertFalse(s.passed)
    }

    @Test
    fun rubbingWithSoapPasses() {
        val s = run(25, 0.3f) { t -> rubbing(t) }
        assertTrue(s.passed)
    }

    @Test
    fun handsFarApartDoNotCount() {
        val s = run(10, 0.3f) { t ->
            val dx = (40 * sin(t * 8)).toFloat()
            listOf(hand(100f + dx, 300f), hand(600f - dx, 300f))
        }
        assertEquals(0f, s.washSeconds)
    }

    /** Hands close together sliding back and forth against each other. */
    private fun rubbing(t: Double): List<List<Pt>> {
        val dx = (40 * sin(t * 8)).toFloat()
        return listOf(hand(300f + dx, 300f), hand(380f - dx, 300f))
    }
}
