package dev.ffaccess
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class Item(val text: String, val box: Rect)

class Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Grayscale + extra contrast: helps with text on busy game backgrounds. Same size, so coordinates still match the screen. */
    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val k = 1.6f
        val t = (-0.5f * k + 0.5f) * 255f
        val cm = ColorMatrix().apply { setSaturation(0f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, t,
            0f, k, 0f, 0f, t,
            0f, 0f, k, 0f, t,
            0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    fun run(bmp: Bitmap, cb: (List<Item>) -> Unit) {
        val input = if (Config.ENHANCE_OCR) enhance(bmp) else bmp
        rec.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { r ->
                if (input !== bmp) input.recycle()
                cb(r.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { Item(l.text.trim(), it) } }
                    .filter { it.text.length > 1 })
            }
            .addOnFailureListener { if (input !== bmp) input.recycle(); cb(emptyList()) }
    }

    /**
     * Second, more careful look at one area: cut it out, enlarge it 2x, boost contrast and read it again.
     * Small text becomes much easier to read. Returned boxes are in full-screen coordinates.
     */
    fun cropPass(src: Bitmap, r: Rect, cb: (List<Item>) -> Unit) {
        val crop = runCatching { Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height()) }.getOrNull()
        if (crop == null) { cb(emptyList()); return }
        val up = Bitmap.createScaledBitmap(crop, r.width() * 2, r.height() * 2, true)
        if (crop !== up) crop.recycle()
        val input = enhance(up)
        up.recycle()
        rec.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { res ->
                input.recycle()
                cb(res.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { b ->
                        Item(l.text.trim(), Rect(r.left + b.left / 2, r.top + b.top / 2, r.left + b.right / 2, r.top + b.bottom / 2))
                    }
                }.filter { it.text.length > 1 })
            }
            .addOnFailureListener { input.recycle(); cb(emptyList()) }
    }
}
