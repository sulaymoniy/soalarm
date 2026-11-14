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
        sharp: (frame: Int) -> Boolean = { true },
        tracker: HandWashTracker = HandWashTracker(),
        fps: Int = 15,
        frame: (t: Double) -> List<List<Pt>>,
    ): WashState {
        var state = WashState()
        for (i in 0 until (seconds * fps).toInt()) {
            val t = i / fps.toDouble()
            state = tracker.update((t * 1000).toLong(), frame(t), foam(t), skinReady = true, sharp = sharp(i))
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
        // With the loosened rhythm (2 reversals, 0.7 Hz) a short stretch of slow movement can
        // briefly look like rubbing, but slow fidgeting must never come close to passing.
        val s = run(25.0, { soapy }) { t -> listOf(rubbing(t, hz = 0.3)) }
        assertFalse(s.passed)
        assertTrue("wash=${s.washSeconds}", s.washSeconds < 1f)
    }

    @Test
    fun foamMustBeNewNotThereFromTheStart() {
        // The hand reads 8% "soap" from the start (shiny skin, light) and never more: no pass,
        // because the baseline (capped at 5%) plus the 4% threshold is never reached.
        val s = run(25.0, { 0.08f }) { t -> listOf(rubbing(t)) }
        assertEquals(0f, s.washSeconds)
        assertFalse(s.soaped)
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
        val tracker = HandWashTracker(initiallySoaped = true)
        var state = WashState()
        val fps = 15
        var before = 0f
        for (i in 0 until 6 * fps) {
            val t = i / fps.toDouble()
            // Hand disappears for 0.6 s in the middle.
            val gone = t in 3.0..3.6
            state = tracker.update((t * 1000).toLong(), if (gone) emptyList() else listOf(rubbing(t)), soapy)
            if (t < 3.0) before = state.washSeconds
            if (gone && t > 3.05) {
                assertEquals(WashHint.ShowHand, state.hint)
                assertEquals(before, state.washSeconds, 0.001f)
            }
        }
        assertTrue("progress kept: ${state.washSeconds} > $before", state.washSeconds > before)
    }

    // ---- Bug: rubbing stuck at 0 after the hand was lost ------------------------------

    @Test
    fun rubbingRestartsAfterHandIsLostForOverASecond() {
        val tracker = HandWashTracker(initiallySoaped = true)
        var state = WashState()
        val fps = 15
        var beforeLoss = 0f
        for (i in 0 until 12 * fps) {
            val t = i / fps.toDouble()
            val hands = when {
                t < 2.5 -> listOf(rubbing(t))
                t < 3.7 -> emptyList() // gone for 1.2 s (longer than the track timeout)
                // Comes back somewhere else in the picture, still rubbing.
                else -> listOf(fakeHand(200f, 220f, curl = (0.5 + 0.35 * sin(2 * PI * 2.0 * t)).toFloat()))
            }
            state = tracker.update((t * 1000).toLong(), hands, dry)
            if (t < 2.5) beforeLoss = state.washSeconds
            if (t in 3.0..3.6) assertEquals(beforeLoss, state.washSeconds, 0.001f)
        }
        assertTrue("rubbing again: hz=${state.debug.rubHz} amp=${state.debug.rubAmplitude}", state.debug.rubAmplitude > 0f)
        assertTrue("counting resumed: ${state.washSeconds} > $beforeLoss", state.washSeconds > beforeLoss)
        assertTrue(state.passed)
    }

    @Test
    fun handOrderSwappingEveryFrameDoesNotBreakRubbing() {
        // Two hands far apart: the left one rubs, the right one is still. MediaPipe lists
        // them in a different order every frame.
        val s = run(15.0, { dry }, tracker = HandWashTracker(initiallySoaped = true)) { t ->
            val left = fakeHand(150f, 300f, curl = (0.5 + 0.35 * sin(2 * PI * 2.0 * t)).toFloat())
            val right = fakeHand(500f, 300f)
            if (((t * 15).toInt() % 2) == 0) listOf(left, right) else listOf(right, left)
        }
        assertTrue(s.passed)
    }

    @Test
    fun smallOneHandRubbingAtLowFrameRateCounts() {
        // Small motion (phone in the other hand) on a slow phone: 6 frames per second.
        val s = run(15.0, { soapy }, fps = 6) { t ->
            listOf(fakeHand(320f, 300f, curl = (0.5 + 0.12 * sin(2 * PI * 1.5 * t)).toFloat()))
        }
        assertTrue("wash=${s.washSeconds} hz=${s.debug.rubHz} amp=${s.debug.rubAmplitude}", s.passed)
    }

    // ---- Bug: soap line stuck after confirmation ---------------------------------------

    @Test
    fun soapReadingStaysLiveAfterConfirmation() {
        // 0.6 % "before", then 14 %: must show foamNow = true (14 > 0.6 + 4), and keep updating.
        val tracker = HandWashTracker()
        val s1 = run(8.0, { t -> if (t < 1.0) 0.006f else 0.14f }, tracker = tracker) { t -> listOf(rubbing(t)) }
        assertTrue(s1.soaped)
        assertTrue("foamNow with ${s1.debug.foamCoverage} vs ${s1.debug.foamBaseline}", s1.debug.foamNow)
        assertEquals(0.14f, s1.debug.foamCoverage, 0.01f)
        // Lather rinsed off: the reading follows, the confirmation stays.
        val s2 = run(3.0, { dry }, tracker = tracker) { t -> listOf(rubbing(t)) }
        assertTrue(s2.debug.foamCoverage < 0.02f)
        assertFalse(s2.debug.foamNow)
        assertTrue(s2.soaped)
    }

    // ---- Soap only has to be confirmed once -------------------------------------------

    @Test
    fun soapConfirmedOnceThenOnlyRubbingCounts() {
        // Lather visible for 3 s, then hidden by blur/hand angle for the rest: still passes.
        val s = run(30.0, { t -> if (t < 3) soapy else dry }) { t -> listOf(rubbing(t)) }
        assertTrue(s.soaped)
        assertTrue("wash=${s.washSeconds}", s.passed)
    }

    @Test
    fun ringDoesNotFillBeforeSoapIsConfirmed() {
        // 1.5 s of soap is not enough to confirm (needs 2 s).
        val s = run(25.0, { t -> if (t < 1.5) soapy else dry }) { t -> listOf(rubbing(t)) }
        assertFalse(s.soaped)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun blurryFramesAreSkippedNotCountedAsNoSoap() {
        // Every other frame is blurry and reads 0 soap: those frames must not cancel the soap.
        val s = run(30.0, { t -> if (((t * 15).toInt() % 2) == 0) soapy else dry }, sharp = { it % 2 == 0 }) { t ->
            listOf(rubbing(t))
        }
        assertTrue(s.soaped)
        assertTrue(s.passed)
    }

    @Test
    fun blurryFramesCannotConfirmSoap() {
        val s = run(25.0, { soapy }, sharp = { false }) { t -> listOf(rubbing(t)) }
        assertFalse(s.soaped)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun shortFalseSoapBurstsDoNotAddUp() {
        // A dry hand that looks "soapy" for 0.5 s every 5 s (a glint, a reflection) for a minute.
        val s = run(60.0, { t -> if (t % 5.0 < 0.5) soapy else dry }) { t -> listOf(rubbing(t)) }
        assertFalse(s.soaped)
        assertEquals(0f, s.washSeconds)
    }

    @Test
    fun alreadySoapedEarlierInThisAlarmOnlyNeedsRubbing() {
        val s = run(25.0, { dry }, tracker = HandWashTracker(initiallySoaped = true)) { t -> listOf(rubbing(t)) }
        assertTrue(s.passed)
    }

    @Test
    fun alreadySoapedStillNeedsRealRubbing() {
        val s = run(25.0, { dry }, tracker = HandWashTracker(initiallySoaped = true)) { t -> listOf(waving(t)) }
        assertEquals(0f, s.washSeconds)
    }
}
