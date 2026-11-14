package com.sulaymoniy.soalarm.ring

/**
 * All the knobs for the hand-washing check in one place.
 *
 * Use "Test wash check" in the app menu to see the live values while you tune them:
 * it shows the hand mask, the pixels counted as foam, foam %, rubbing frequency and
 * which condition is passing or failing.
 *
 * The rule: the ring only fills while a SOAPY hand is being RUBBED, both at once.
 */
object WashConfig {
    /** Seconds of "soapy hand + rubbing" needed to stop the alarm. */
    const val WASH_SECONDS_REQUIRED = 20f

    // ---------------------------------------------------------------------------------
    // Hand mask: foam is only measured on the hand itself, never on the background.
    // ---------------------------------------------------------------------------------

    /** Finger width used for the mask, as a fraction of hand length (wrist to middle knuckle). */
    const val FINGER_MASK_RADIUS = 0.09f

    /** Large hands are analysed on a smaller grid so every phone does the same amount of work. */
    const val FOAM_GRID_MAX_SIZE = 320

    // ---------------------------------------------------------------------------------
    // Skin colour: sampled from the hand during the first frames, so "white" is judged
    // against this person's own skin under this light.
    // ---------------------------------------------------------------------------------

    /** Frames with a visible hand used to learn the skin colour. */
    const val SKIN_SAMPLE_FRAMES = 10

    /** Pixels at least this colourful count as skin while sampling (foam is greyish-white). */
    const val SKIN_MIN_SATURATION = 0.15f

    // ---------------------------------------------------------------------------------
    // Foam = bubbly white ON the hand.
    // ---------------------------------------------------------------------------------

    /** A foam pixel is never darker than this (0..1), whatever the skin colour... */
    const val FOAM_MIN_BRIGHTNESS = 0.45f

    /** ...and must be at least this much brighter than the skin (0..1)... */
    const val FOAM_BRIGHTER_THAN_SKIN = 0.08f

    /** ...and at most this colourful (0..1)... */
    const val FOAM_MAX_SATURATION = 0.25f

    /** ...and at most this fraction of the skin's own saturation. */
    const val FOAM_SATURATION_VS_SKIN = 0.65f

    /**
     * Bubble texture: a "bubble highlight" is a pixel brighter than all 8 neighbours by at
     * least this much on average (0..1). Smooth white things (sink, tiles, towel, soap bar)
     * have almost none; lather is full of them.
     */
    const val BUBBLE_MIN_CONTRAST = 0.05f

    /**
     * Of a bubble highlight's 8 neighbours, at least this many must be greyish (lather's
     * gaps are grey shadows). This rejects the edge of a white soap bar against skin.
     */
    const val BUBBLE_MIN_GREY_NEIGHBOURS = 6

    /** Look this many grid pixels around each pixel when counting bubble highlights. */
    const val FOAM_TEXTURE_RADIUS = 3

    /** A whitish pixel is foam only if it has at least this many bubble highlights nearby. */
    const val FOAM_MIN_BUBBLES = 3

    /**
     * SOAP THRESHOLD: share (0..1) of the hand that must be NEWLY covered by foam, i.e.
     * above the hand's lowest foam reading so far. Raise it if a dry hand passes;
     * lower it if real lather isn't detected.
     */
    const val FOAM_COVERAGE_THRESHOLD = 0.12f

    /**
     * The "before" foam level never counts as higher than this. So if the very first
     * frames already show lather, the user isn't locked out.
     */
    const val FOAM_BASELINE_CAP = 0.05f

    /** Smoothing of the foam reading, in seconds. */
    const val FOAM_SMOOTHING_SECONDS = 0.3f

    // ---------------------------------------------------------------------------------
    // Rubbing = repeated back-and-forth of the fingers relative to the palm.
    // Measured in the hand's own frame, so moving, tilting or shaking the phone, and
    // waving the whole hand, don't count. Holding something still doesn't count either.
    // ---------------------------------------------------------------------------------

    /** Rubbing is judged over this much recent time (the motion must be sustained). */
    const val RUB_WINDOW_MS = 1_500L

    /** Back-and-forth must be at least this fast (cycles per second)... */
    const val RUB_MIN_HZ = 1.0f

    /** ...and at most this fast (faster is detection jitter, not washing). */
    const val RUB_MAX_HZ = 4.0f

    /** How far fingers must move back and forth (standard deviation, in hand-lengths). */
    const val RUB_MIN_AMPLITUDE = 0.03f

    /** At least this many direction changes inside the window. */
    const val RUB_MIN_REVERSALS = 3

    /** Two hands count as "together" when palm centres are closer than this many hand-lengths. */
    const val HANDS_CLOSE_FACTOR = 1.8f

    /** A hand not seen for this long is forgotten. Progress is kept; it only pauses. */
    const val HAND_TRACK_TIMEOUT_MS = 1_000L

    // ---------------------------------------------------------------------------------
    // Fallbacks
    // ---------------------------------------------------------------------------------

    /** If the camera delivers no frames for this long, fall back to math problems. */
    const val CAMERA_NO_FRAMES_TIMEOUT_MS = 8_000L

    /** After the alarm has rung this long, offer "Can't wash right now? Solve math instead". */
    const val MANUAL_FALLBACK_AFTER_MS = 3 * 60 * 1000L

    /** That optional way out is harder than the camera-failure fallback. */
    const val MANUAL_FALLBACK_PROBLEMS = 5
}
