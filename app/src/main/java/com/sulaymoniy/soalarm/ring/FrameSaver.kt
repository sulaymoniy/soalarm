package com.sulaymoniy.soalarm.ring

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Test mode only, and only when the user taps "Save frame": saves the current camera
 * frame to the phone's Pictures/SoAlarm folder, so real examples can be shared for tuning.
 * Two pictures are written:
 * - `..._frame.png`: the raw camera frame, exactly what the detector saw.
 * - `..._debug.png`: the same frame with the hand mask / soap bar / foam / bubbles drawn
 *   on it, plus all live values printed underneath.
 * Nothing is uploaded; the files stay on the phone.
 */
object FrameSaver {

    /** Returns a short message for the user. Runs on the analysis thread. */
    fun save(context: Context, frame: Bitmap, hands: List<List<Pt>>, foam: FoamResult, state: WashState): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val lines = values(stamp, frame, hands, foam, state)
        val annotated = annotate(frame, hands, foam.debug, lines)
        return try {
            val where = savePng(context, "soalarm_${stamp}_frame.png", frame)
            savePng(context, "soalarm_${stamp}_debug.png", annotated)
            "Saved 2 pictures to $where"
        } catch (e: Exception) {
            "Couldn't save: ${e.message}"
        } finally {
            annotated.recycle()
        }
    }

    private fun values(stamp: String, frame: Bitmap, hands: List<List<Pt>>, f: FoamResult, s: WashState): List<String> {
        val d = s.debug
        fun pct(v: Float) = String.format(Locale.US, "%.1f%%", v * 100)
        fun num(v: Float) = String.format(Locale.US, "%.5f", v)
        return listOf(
            "SoAlarm wash check  $stamp",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Frame: ${frame.width}x${frame.height}, hands: ${hands.size}",
            "Skin learned: ${f.skinReady}  V=${num(f.skinV)} S=${num(f.skinS)}",
            "Sharp: ${f.sharp}  sharpness=${num(f.sharpness)} ref=${num(f.sharpnessRef)} (needs >= ${WashConfig.SHARPNESS_RELATIVE} x ref)",
            "Soap bar cut out: ${pct(f.barShare)} of hand",
            "Soap this frame: ${pct(max(f.coverage, 0f))} of hand (${f.maskPixels} px)",
            "Soap smoothed: ${pct(d.foamCoverage)}  before: ${pct(d.foamBaseline)}  needs +${pct(WashConfig.FOAM_COVERAGE_THRESHOLD)}  -> ${d.foamNow}",
            "Soap confirmed: ${s.soaped}  (${String.format(Locale.US, "%.1f", d.foamSeconds)} / ${WashConfig.FOAM_CONFIRM_SECONDS} s)",
            "Rubbing: ${d.rubbing}  ${String.format(Locale.US, "%.2f Hz, amp %.4f, %.1f fps", d.rubHz, d.rubAmplitude, d.fps)}",
            "Washing: ${String.format(Locale.US, "%.1f", s.washSeconds)} / ${WashConfig.WASH_SECONDS_REQUIRED} s   hint: ${s.hint.text}",
            "Config: bubbleContrast=${WashConfig.BUBBLE_MIN_CONTRAST} grey>=${WashConfig.BUBBLE_MIN_GREY_NEIGHBOURS} " +
                "falloff>=${WashConfig.BUBBLE_MIN_FALLOFF_DIRECTIONS}",
            "        minBubbles=${WashConfig.FOAM_MIN_BUBBLES} radius=${WashConfig.FOAM_TEXTURE_RADIUS} " +
                "grid=${WashConfig.FOAM_GRID_MAX_SIZE}",
        )
    }

    private fun annotate(frame: Bitmap, hands: List<List<Pt>>, d: FoamDebug?, lines: List<String>): Bitmap {
        val textSize = (frame.width / 48f).coerceAtLeast(12f)
        val lineH = textSize * 1.35f
        val panel = (lineH * lines.size + textSize).toInt()
        val out = createBitmap(frame.width, frame.height + panel)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(frame, 0f, 0f, null)
        if (d != null) {
            val colors = IntArray(d.w * d.h) { i ->
                when {
                    d.bubbles[i] -> 0xFFFFEB3B.toInt()
                    d.foam[i] -> 0xAA00E676.toInt()
                    d.bar[i] -> 0x88FF9800.toInt()
                    d.mask[i] -> 0x553D8BFF
                    else -> 0
                }
            }
            val overlay = Bitmap.createBitmap(colors, d.w, d.h, Bitmap.Config.ARGB_8888)
            val dst = Rect(d.originX, d.originY, d.originX + d.w * d.scale, d.originY + d.h * d.scale)
            canvas.drawBitmap(overlay, null, dst, Paint().apply { isFilterBitmap = false })
            overlay.recycle()
        }
        val dot = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        for (hand in hands) for (p in hand) canvas.drawCircle(p.x, p.y, 2.5f, dot)
        val text = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, textSize * 0.5f, frame.height + lineH * (i + 1), text) }
        return out
    }

    /** Pictures/SoAlarm via MediaStore (Android 10+), or the app's own Pictures folder before that. */
    private fun savePng(context: Context, name: String, bitmap: Bitmap): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SoAlarm")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("no storage")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("can't write")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Pictures/SoAlarm"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SoAlarm").apply { mkdirs() }
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return dir.absolutePath
        }
    }
}
