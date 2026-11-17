package vn.dichmanhinh.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Util
import java.util.concurrent.ConcurrentHashMap

data class Block(val text: String, val box: Rect)

/** Chưa có gói nhận dạng chữ (Nhật / Hàn / Devanagari). Gói đang được yêu cầu tải về. */
class OcrPackMissing(script: String) : Exception(App.instance.getString(R.string.err_ocr_pack, script))

/**
 * OCR trên thiết bị. Latin + Trung đóng gói sẵn; Nhật / Hàn / Devanagari tải qua Google Play Services khi cần
 * (nhờ vậy APK nhẹ hơn rất nhiều).
 */
object OcrEngine {
    private val cache = ConcurrentHashMap<String, TextRecognizer>()

    fun recognizerFor(script: String): TextRecognizer = cache.getOrPut(script) {
        when (script) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun requestInstall(script: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OcrPacks.install(App.instance, script)
            } catch (e: Exception) {
                // Thiếu Google Play Services: người dùng sẽ thấy thông báo lỗi ở lần dịch
            }
        }
    }

    private suspend fun run(script: String, bmp: Bitmap, vertical: Boolean): List<Block> {
        val text = try {
            recognizerFor(script).process(InputImage.fromBitmap(bmp, 0)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (script in OcrPacks.EXTRA) {
                requestInstall(script)
                throw OcrPackMissing(script)
            }
            throw e
        }
        return text.textBlocks.mapNotNull { b ->
            val box = b.boundingBox ?: return@mapNotNull null
            val cjk = Util.hasCjk(b.text)
            val sep = if (cjk) "" else " "
            // Chữ dọc (Trung/Nhật): đọc từ cột phải sang cột trái.
            val lines: List<Text.Line> = if (vertical && cjk) {
                b.lines.sortedWith(
                    compareByDescending<Text.Line> { it.boundingBox?.centerX() ?: 0 }
                        .thenBy { it.boundingBox?.top ?: 0 }
                )
            } else b.lines
            val t = lines.joinToString(sep) { it.text.trim() }.trim()
            if (t.none { it.isLetterOrDigit() }) null else Block(t, Rect(box))
        }.sortedBy { it.box.top }
    }

    suspend fun recognize(bmp: Bitmap, srcLang: String, vertical: Boolean = false): List<Block> {
        val script = when (srcLang) {
            "zh", "zh-TW" -> "zh"
            "ja" -> "ja"
            "ko" -> "ko"
            "hi", "mr", "ne" -> "hi"
            Languages.AUTO -> "auto"
            else -> "latin"
        }
        if (script != "auto") return run(script, bmp, vertical)

        // Tự động: thử Latin trước; nếu gần như không có chữ Latin thì thử nhóm CJK.
        val latin = run("latin", bmp, vertical)
        val letters = latin.sumOf { b -> b.text.count { it.isLetter() } }
        if (letters >= 12) return latin
        val cjk = run("zh", bmp, vertical)
        val all = cjk.joinToString("") { it.text }
        return when {
            Util.hasKana(all) -> run("ja", bmp, vertical)
            Util.hasHangul(all) -> run("ko", bmp, vertical)
            cjk.isNotEmpty() -> cjk
            else -> latin
        }
    }
}
