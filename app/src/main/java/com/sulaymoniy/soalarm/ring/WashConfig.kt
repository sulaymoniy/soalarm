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
    /** Seconds of rubbing (after soap is confirmed) needed to stop the alarm. */
    const val WASH_SECONDS_REQUIRED = 5f

    // ---------------------------------------------------------------------------------
    // Hand mask: foam is only measured on the hand itself, never on the background.
    // ---------------------------------------------------------------------------------

    /** Finger width used for the mask, as a fraction of hand length (wrist to middle knuckle). */
    const val FINGER_MASK_RADIUS = 0.09f

    /**
     * Very large hands are analysed on a smaller grid to save work. Kept high so small
     * bar-soap bubbles survive the downscaling.
     */
    const val FOAM_GRID_MAX_SIZE = 480

    // ---------------------------------------------------------------------------------
    // Soap bar in the hand, ANY colour (orange, green, blue, pink, white, cream): a solid,
    // smooth area whose colour differs from the hand is cut out of the mask, for both the
    // skin sample and the soap check. Lather is never cut out because it is full of texture.
    // ---------------------------------------------------------------------------------

    /**
     * How different (0..1, biggest R/G/B difference) from the hand's typical colour an area
     * must be to be a soap bar candidate. A white bar on skin is ~0.5, an orange one ~0.3.
     */
    const val SOAP_BAR_COLOR_DISTANCE = 0.20f

    /**
     * Bar candidates must be at least this many grid pixels away from any texture
     * (bubbles, edges, wrinkles). A bar's inside is perfectly smooth; lather never is.
     */
    const val SOAP_BAR_TEXTURE_CLEARANCE = 4

    /** A smooth, differently coloured area must cover at least this share of the hand to be a bar. */
    const val SOAP_BAR_MIN_SIZE = 0.02f

    /** The cut-out grows by this many grid pixels beyond the clearance, so the bar's outline isn't checked. */
    const val SOAP_BAR_GROW = 2

    // ---------------------------------------------------------------------------------
    // Sharpness: rubbing blurs the picture and wipes out small bubbles, so the foam check
    // only uses sharp frames. Blurry frames are skipped, NOT counted as "no foam".
    // ---------------------------------------------------------------------------------

    /**
     * A frame is sharp if its sharpness (Laplacian variance on the hand) is at least this
     * fraction of the sharpest recent frame of this hand.
     */
    const val SHARPNESS_RELATIVE = 0.5f

    /** How fast the "sharpest recent frame" memory fades, per frame (0.98 = ~2 s). */
    const val SHARPNESS_MEMORY = 0.98f

    /** Absolute floor: below this a frame is always blurry. */
    const val SHARPNESS_MIN = 0.00002f

    // ---------------------------------------------------------------------------------
    // Skin colour: sampled from the hand during the first frames, so "white" is judged
    // against this person's own skin under this light.
    // ---------------------------------------------------------------------------------

    /** Frames with a visible hand used to learn the skin colour. */
    const val SKIN_SAMPLE_FRAMES = 10

    /** Pixels at least this colourful count as skin while sampling (foam is greyish-white). */
    const val SKIN_MIN_SATURATION = 0.15f

    // ---------------------------------------------------------------------------------
    // Soap on the hand: small bright bubble highlights spread over the skin. This covers
    // thick white foam, thin bar-soap lather, and wet, shiny, soap-filmed skin.
    // ---------------------------------------------------------------------------------

    /** A bubble highlight is never darker than this (0..1), whatever the skin colour... */
    const val FOAM_MIN_BRIGHTNESS = 0.45f

    /** ...and must be at least this much brighter than the skin (0..1)... */
    const val FOAM_BRIGHTER_THAN_SKIN = 0.08f

    /** ...and at most this colourful (0..1)... */
    const val FOAM_MAX_SATURATION = 0.25f

    /**
     * Tinted lather (pinkish, greenish liquid soap) may be this colourful (0..1), but only
     * when its bubble texture is strong: contrast of at least [BUBBLE_STRONG_CONTRAST] and
     * falling off in at least [BUBBLE_STRONG_FALLOFF] of 8 directions.
     */
    const val FOAM_MAX_SATURATION_TINTED = 0.35f
    const val BUBBLE_STRONG_CONTRAST = 0.06f
    const val BUBBLE_STRONG_FALLOFF = 7

    /** ...and at most this fraction of the skin's own saturation. */
    const val FOAM_SATURATION_VS_SKIN = 0.65f

    /**
     * Bubble highlight: brighter than all 8 neighbours by at least this much on average
     * (0..1). Smooth white things (sink, tiles, towel, soap bar) have almost none.
     */
    const val BUBBLE_MIN_CONTRAST = 0.03f

    /** Of a bubble highlight's 8 neighbours, at least this many must be greyish (not plain skin). */
    const val BUBBLE_MIN_GREY_NEIGHBOURS = 4

    /**
     * A bubble is a SMALL spot: 2 pixels away it must be darker (by [BUBBLE_MIN_CONTRAST]) in
     * at least this many of 8 directions. The edge of a white soap bar or sink stays bright
     * along one side, so it is rejected.
     */
    const val BUBBLE_MIN_FALLOFF_DIRECTIONS = 6

    /** Look this many grid pixels around each hand pixel when counting bubble highlights. */
    const val FOAM_TEXTURE_RADIUS = 4

    /** A hand pixel counts as soapy if at least this many bubble highlights are nearby. */
    const val FOAM_MIN_BUBBLES = 2

    /**
     * SOAP THRESHOLD: share (0..1) of the hand that must be NEWLY soapy, i.e. above the
     * hand's lowest reading so far. Raise it if a dry hand passes; lower it if real lather
     * isn't detected.
     */
    const val FOAM_COVERAGE_THRESHOLD = 0.04f

    /**
     * The "before" level never counts as higher than this. So if the very first frames
     * already show lather, the user isn't locked out.
     */
    const val FOAM_BASELINE_CAP = 0.05f

    /** Smoothing of the foam reading, in seconds. */
    const val FOAM_SMOOTHING_SECONDS = 0.3f

    /**
     * Soap only has to be CONFIRMED once: after foam has been seen (on sharp frames) for
     * this many seconds in total, the hand is "soaped" for the rest of the alarm. From
     * then on only rubbing fills the ring.
     */
    const val FOAM_CONFIRM_SECONDS = 2f

    /**
     * The confirmation timer drains by this many seconds per second while sharp frames show
     * NO soap. A dry hand that reads "soapy" on a stray frame now and then never adds up
     * to a confirmation; real lather (seen most of the time) does.
     */
    const val FOAM_CONFIRM_LEAK_PER_SECOND = 0.5f

    // ---------------------------------------------------------------------------------
    // Rubbing = repeated back-and-forth of the fingers relative to the palm.
    // Measured in the hand's own frame, so moving, tilting or shaking the phone, and
    // waving the whole hand, don't count. Holding something still doesn't count either.
    // ---------------------------------------------------------------------------------

    /** Rubbing is judged over this much recent time (the motion must be sustained). */
    const val RUB_WINDOW_MS = 2_000L

    /** At least this many analysed frames inside the window (slow phones manage ~5 per second). */
    const val RUB_MIN_SAMPLES = 5

    /** Back-and-forth must be at least this fast (cycles per second)... */
    const val RUB_MIN_HZ = 0.7f

    /** ...and at most this fast (faster is detection jitter, not washing). */
    const val RUB_MAX_HZ = 5.0f

    /** Rubbing stays "on" this long after the last valid reading, so short gaps don't stop the count. */
    const val RUB_HOLD_MS = 500L

    /** How far fingers must move back and forth (standard deviation, in hand-lengths). */
    const val RUB_MIN_AMPLITUDE = 0.03f

    /** At least this many direction changes inside the window. */
    const val RUB_MIN_REVERSALS = 2

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
