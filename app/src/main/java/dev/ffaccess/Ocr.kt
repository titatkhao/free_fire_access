package dev.ffaccess
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class Item(val text: String, val box: Rect)

class Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    fun run(bmp: Bitmap, cb: (List<Item>) -> Unit) {
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                cb(r.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { Item(l.text.trim(), it) } }
                    .filter { it.text.length > 1 })
            }
            .addOnFailureListener { cb(emptyList()) }
    }
}
