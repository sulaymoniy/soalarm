package com.sulaymoniy.soalarm.ring

/**
 * All the knobs for the hand-washing check in one place.
 * If the check is too strict or too easy on your phone, tune these numbers.
 */
object WashConfig {
    /** Seconds of detected hand rubbing needed to stop the alarm. */
    const val WASH_SECONDS_REQUIRED = 20f

    /** Of those, how many seconds soap foam must be visible. */
    const val FOAM_SECONDS_REQUIRED = 5f

    /**
     * SOAP THRESHOLD: share (0..1) of bright, low-colour pixels around the hands
     * that counts as foam. Raise it if plain hands pass as "soapy"; lower it if
     * real foam is not detected.
     */
    const val FOAM_PIXEL_THRESHOLD = 0.06f

    /** A pixel is "foam-like" when it is at least this bright (0..1)... */
    const val FOAM_MIN_BRIGHTNESS = 0.78f

    /** ...and has at most this much colour saturation (0..1). Skin is usually above this. */
    const val FOAM_MAX_SATURATION = 0.20f

    /**
     * ONE HAND: how fast the fingers must move relative to their own palm, in
     * hand-lengths per second (averaged over the thumb and finger joints).
     * Measured in the hand's own frame, so shaking or tilting the phone doesn't count.
     * Lower it if real washing isn't detected; raise it if a still hand counts as washing.
     */
    const val SINGLE_HAND_RUB_THRESHOLD = 0.35f

    /**
     * Finger movement is compared with how the hand looked this long ago (not just the
     * previous frame), so tiny detection jitter doesn't add up to fake motion.
     */
    const val FINGER_MOTION_WINDOW_MS = 250L

    /** TWO HANDS (optional): how fast hands must slide against each other, in hand-lengths per second. */
    const val RUB_SPEED_THRESHOLD = 0.30f

    /** Two hands count as "together" when palm centres are closer than this many hand-lengths. */
    const val HANDS_CLOSE_FACTOR = 1.8f

    /** Smoothing of the rubbing signal, in seconds. Higher = steadier but slower to react. */
    const val RUB_SMOOTHING_SECONDS = 0.4f

    /** A hand not seen for this long is forgotten (progress is kept; it only pauses). */
    const val HAND_TRACK_TIMEOUT_MS = 1_000L

    /** If the camera delivers no frames for this long, fall back to math problems. */
    const val CAMERA_NO_FRAMES_TIMEOUT_MS = 8_000L

    /** After the alarm has rung this long, offer "Can't wash right now? Solve math instead". */
    const val MANUAL_FALLBACK_AFTER_MS = 3 * 60 * 1000L

    /** That optional way out is harder than the camera-failure fallback. */
    const val MANUAL_FALLBACK_PROBLEMS = 5
}
