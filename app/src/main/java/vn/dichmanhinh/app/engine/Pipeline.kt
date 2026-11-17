package vn.dichmanhinh.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.dichmanhinh.app.data.HistoryStore
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs

data class Item(val box: Rect, val src: String, val dst: String)
data class PipelineResult(val items: List<Item>, val note: String?)

object Glossary {
    fun apply(text: String, raw: String): String {
        var out = text
        raw.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val a = line.substring(0, idx).trim()
                val b = line.substring(idx + 1).trim()
                if (a.isNotEmpty()) {
                    out = Regex(Regex.escape(a), RegexOption.IGNORE_CASE).replace(out, Regex.escapeReplacement(b))
                }
            }
        }
        return out
    }
}

/**
 * Hai đường vào:
 *  - run(): ảnh chụp -> OCR -> dịch
 *  - runBlocks(): chữ lấy trực tiếp từ Trợ năng (không cần OCR) -> dịch
 * Cả hai đều bỏ qua khối đã đúng ngôn ngữ đích, rồi áp dụng từ điển riêng.
 */
object Pipeline {
    suspend fun run(ctx: Context, full: Bitmap, region: Rect? = null): PipelineResult =
        withContext(Dispatchers.Default) {
            val p = Prefs(ctx)
            var ox = 0
            var oy = 0
            val cropped = region != null
            val bmp = if (region != null) {
                val l = region.left.coerceIn(0, full.width - 1)
                val t = region.top.coerceIn(0, full.height - 1)
                val r = region.right.coerceIn(l + 1, full.width)
                val b = region.bottom.coerceIn(t + 1, full.height)
                ox = l
                oy = t
                Bitmap.createBitmap(full, l, t, r - l, b - t)
            } else full

            val blocks = try {
                OcrEngine.recognize(bmp, p.src, p.verticalSrc)
            } finally {
                if (cropped) bmp.recycle()
            }
            finish(ctx, p, blocks, ox, oy)
        }

    suspend fun runBlocks(ctx: Context, blocks: List<Block>, region: Rect? = null): PipelineResult =
        withContext(Dispatchers.Default) {
            val p = Prefs(ctx)
            val use = if (region == null) blocks else blocks.filter { Rect.intersects(it.box, region) }
            finish(ctx, p, use, 0, 0)
        }

    private suspend fun finish(ctx: Context, p: Prefs, blocks: List<Block>, ox: Int, oy: Int): PipelineResult {
        if (blocks.isEmpty()) return PipelineResult(emptyList(), null)

        val texts = blocks.map { it.text }
        val srcs: List<String> = if (p.src != Languages.AUTO) {
            List(texts.size) { p.src }
        } else {
            val whole = LangDetect.detect(texts.joinToString("\n"))
            texts.map { LangDetect.detect(it) ?: whole ?: Languages.AUTO }
        }
        val todo = texts.indices.filter { srcs[it] != p.dst }
        var note: String? = null
        val translated = HashMap<Int, String>()
        if (todo.isNotEmpty()) {
            val subT = todo.map { texts[it] }
            val subS = todo.map { srcs[it] }
            val out = try {
                TranslatorFactory.create(p).translate(subT, subS, p.dst)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (TranslatorFactory.isOnline(p)) {
                    note = e.message ?: e.javaClass.simpleName
                    TranslatorFactory.mlkit().translate(subT, subS, p.dst)
                } else throw e
            }
            todo.forEachIndexed { k, idx -> translated[idx] = out[k] }
        }

        val items = blocks.indices.map { i ->
            val box = Rect(blocks[i].box).apply { offset(ox, oy) }
            Item(box, texts[i], Glossary.apply(translated[i] ?: texts[i], p.glossary))
        }
        if (p.saveHistory && translated.isNotEmpty()) {
            HistoryStore.add(
                ctx,
                items.joinToString("\n") { it.src },
                items.joinToString("\n") { it.dst },
                p.src, p.dst
            )
        }
        return PipelineResult(items, note)
    }
}
