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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Washing counts only while foam on the hand AND rhythmic rubbing happen together. */
class HandWashTrackerTest {

    private val soapy = 0.40f
    private val dry = 0.0f

    private fun run(
        seconds: Double,
        foam: (t: Double) -> Float,
        frame: (t: Double) -> List<List<Pt>>,
    ): WashState {
        val tracker = HandWashTracker()
        var state = WashState()
        val fps = 15
        for (i in 0 until (seconds * fps).toInt()) {
            val t = i / fps.toDouble()
            state = tracker.update((t * 1000).toLong(), frame(t), foam(t))
        }
        return state
    }

    /** Fingers rubbing back and forth against the palm at [hz] times per second. */
    private fun rubbing(t: Double, hz: Double = 2.0) =
        fakeHand(320f, 300f, curl = (0.5 + 0.35 * sin(2 * PI * hz * t)).toFloat())

    /** The whole hand moving, tilting and zooming (waving / shaky phone), fingers still. */
    private fun waving(t: Double) = fakeHand(
        cx = 320f + (80 * sin(t * 9)).toFloat(),
        cy = 300f + (50 * cos(t * 7)).toFloat(),
        size = 100f + (12 * sin(t * 5)).toFloat(),
        angle = (0.4 * sin(t * 8)).toFloat(),
    )

    @Test
    fun noHandAsksToShowHand() {
        val s = run(5.0, { soapy }) { emptyList() }
        assertEquals(WashHint.ShowHand, s.hint)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun soapyRubbingHandPasses() {
        val s = run(25.0, { soapy }) { t -> listOf(rubbing(t)) }
        assertTrue("wash=${s.washSeconds}", s.passed)
    }

    @Test
    fun dryRubbingHandDoesNotCount() {
        val s = run(25.0, { dry }) { t -> listOf(rubbing(t)) }
        assertEquals(0f, s.washSeconds)
        assertEquals(WashHint.AddSoap, s.hint)
        assertTrue(s.debug.rubbing) // rubbing is seen, but no soap
    }

    @Test
    fun dryHandWavingDoesNotCount() {
        val s = run(25.0, { dry }) { t -> listOf(waving(t)) }
        assertEquals(0f, s.washSeconds)
        assertFalse(s.debug.rubbing)
    }

    @Test
    fun dryHandHoldingSoapBarDoesNotCount() {
        // A white soap bar gives a little "foam" reading, below the threshold; fingers still.
        val s = run(25.0, { 0.04f }) { listOf(fakeHand(320f, 300f, curl = 0.6f)) }
        assertEquals(0f, s.washSeconds)
        assertFalse(s.debug.foamNow)
        assertFalse(s.debug.rubbing)
    }

    @Test
    fun soapyHandHeldStillAsksToRub() {
        val s = run(15.0, { soapy }) { listOf(fakeHand(320f, 300f)) }
        assertEquals(0f, s.washSeconds)
        assertEquals(WashHint.RubHand, s.hint)
    }

    @Test
    fun soapyHandWavingOrShakyPhoneDoesNotCount() {
        val s = run(25.0, { soapy }) { t -> listOf(waving(t)) }
        assertEquals(0f, s.washSeconds)
        assertEquals(WashHint.RubHand, s.hint)
    }

    @Test
    fun slowFidgetingIsNotRubbing() {
        val s = run(25.0, { soapy }) { t -> listOf(rubbing(t, hz = 0.3)) }
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun foamMustBeNewNotThereFromTheStart() {
        // The hand reads 10% "foam" from the start (shiny skin, light) and never more: no pass,
        // because the baseline (capped at 5%) plus the 12% threshold is never reached.
        val s = run(25.0, { 0.10f }) { t -> listOf(rubbing(t)) }
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun dryFirstThenLatherPasses() {
        val s = run(30.0, { t -> if (t < 4) 0.02f else soapy }) { t -> listOf(rubbing(t)) }
        assertTrue("wash=${s.washSeconds}", s.passed)
        assertTrue(s.debug.foamBaseline <= 0.03f)
    }

    @Test
    fun twoHandsRubbingWithSoapStillWork() {
        val s = run(25.0, { soapy }) { t ->
            val dx = (40 * sin(2 * PI * 1.5 * t)).toFloat()
            listOf(fakeHand(300f + dx, 300f), fakeHand(380f - dx, 300f))
        }
        assertTrue(s.passed)
    }

    @Test
    fun briefDropPausesButDoesNotReset() {
        val tracker = HandWashTracker()
        var state = WashState()
        val fps = 15
        var before = 0f
        for (i in 0 until 14 * fps) {
            val t = i / fps.toDouble()
            // Hand disappears for 0.6 s in the middle.
            val gone = t in 8.0..8.6
            state = tracker.update((t * 1000).toLong(), if (gone) emptyList() else listOf(rubbing(t)), soapy)
            if (t < 8.0) before = state.washSeconds
            if (gone && t > 8.05) {
                assertEquals(WashHint.ShowHand, state.hint)
                assertEquals(before, state.washSeconds, 0.001f)
            }
        }
        assertTrue("progress kept: ${state.washSeconds} > $before", state.washSeconds > before)
        assertTrue(state.washSeconds < WashConfig.WASH_SECONDS_REQUIRED)
    }
}
