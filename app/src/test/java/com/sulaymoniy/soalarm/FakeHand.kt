package com.sulaymoniy.soalarm

import com.sulaymoniy.soalarm.ring.Pt
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fake MediaPipe hand (21 landmarks) for tests. [curl] 0 = open hand, 1 = fist.
 * [size] is roughly wrist-to-middle-knuckle in pixels; [angle] rotates the whole hand.
 */
fun fakeHand(cx: Float, cy: Float, curl: Float = 0.2f, size: Float = 100f, angle: Float = 0f): List<Pt> {
    // Local layout in hand-lengths, fingers pointing "up" (negative y).
    val local = MutableList(21) { Pt(0f, 0f) }
    local[0] = Pt(0f, 0.5f) // wrist
    val open = 1f - curl
    // Thumb 1-4
    local[1] = Pt(-0.25f, 0.3f)
    local[2] = Pt(-0.4f, 0.1f)
    local[3] = Pt(-0.4f - 0.15f * open, -0.1f * open)
    local[4] = Pt(-0.4f - 0.25f * open, -0.3f * open + 0.1f * curl)
    // Fingers: knuckles at y = -0.5
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
    return local.map { p -> Pt(cx + (p.x * c - p.y * s) * size, cy + (p.x * s + p.y * c) * size) }
}
