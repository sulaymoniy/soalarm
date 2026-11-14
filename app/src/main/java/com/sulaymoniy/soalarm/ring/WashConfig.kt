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

    /** How fast hands must move against each other, in hand-lengths per second. */
    const val RUB_SPEED_THRESHOLD = 0.30f

    /** Hands count as "together" when palm centres are closer than this many hand-lengths. */
    const val HANDS_CLOSE_FACTOR = 1.8f

    /** Rubbing hands often hide each other. Keep "both hands" for this long after losing one. */
    const val HAND_LOSS_GRACE_MS = 900L

    /** If the camera delivers no frames for this long, fall back to math problems. */
    const val CAMERA_NO_FRAMES_TIMEOUT_MS = 8_000L
}
