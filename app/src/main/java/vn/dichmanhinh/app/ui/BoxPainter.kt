package vn.dichmanhinh.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.Util
import vn.dichmanhinh.app.engine.Item
import kotlin.math.max
import kotlin.math.min

/** Kiểu hiển thị bản dịch (phông, màu, nền, viền, căn giữa, chữ hoa, chế độ manga). */
class StyleCfg(
    val typeface: Typeface,
    val textColor: Int?,
    val bgColor: Int?,
    val bgAlpha: Int,
    val border: Boolean,
    val center: Boolean,
    val upcase: Boolean,
    val manga: Boolean,
    val mangaTypeface: Typeface,
    val mangaBg: Int
) {
    companion object {
        fun face(family: String, style: String): Typeface = Typeface.create(
            family,
            when (style) {
                "bold" -> Typeface.BOLD
                "italic" -> Typeface.ITALIC
                "bold_italic" -> Typeface.BOLD_ITALIC
                else -> Typeface.NORMAL
            }
        )

        fun from(p: Prefs) = StyleCfg(
            face(p.font, p.textStyle), p.textColor, p.bgColor, p.bgAlpha,
            p.border, p.center, p.upcase, p.manga, face(p.mangaFont, "bold"), p.mangaBg
        )
    }
}

class DrawBox(
    val rect: RectF,
    val src: String,
    val text: String,
    val bg: Int,
    val fg: Int,
    val bgAlpha: Int,          // 0..255
    val typeface: Typeface,
    val center: Boolean,
    val border: Boolean
)

/**
 * Vẽ bản dịch đè lên vị trí chữ gốc: nền mặc định lấy màu từ viền quanh khối chữ (hòa vào màn hình),
 * chữ tự co/giãn cho vừa khung. Màu/phông/viền/căn lề theo [StyleCfg].
 */
object BoxPainter {
    private const val FALLBACK_BG = 0xFF202124.toInt()

    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    /** [bmp] có thể null (chế độ Trợ năng không chụp được ảnh): khi đó dùng nền tối mặc định. */
    fun prepare(bmp: Bitmap?, items: List<Item>, style: StyleCfg): List<DrawBox> = items.map { it ->
        val isManga = style.manga && Util.hasCjk(it.src)
        val sampled = if (bmp != null) sampleBg(bmp, it.box) else FALLBACK_BG
        val bg = when {
            isManga -> style.mangaBg
            style.bgColor != null -> style.bgColor
            else -> sampled
        }
        val fg = when {
            style.textColor != null && !isManga -> style.textColor
            lum(bg) > 140 -> 0xFF101010.toInt()
            else -> 0xFFF8F8F8.toInt()
        }
        val alpha = if (isManga) 255 else (style.bgAlpha.coerceIn(0, 100) * 255 / 100)
        val rect = RectF(it.box)
        if (isManga) rect.inset(-rect.width() * 0.03f, -rect.height() * 0.03f)
        DrawBox(
            rect, it.src,
            if (style.upcase) it.dst.uppercase() else it.dst,
            bg, fg, alpha,
            if (isManga) style.mangaTypeface else style.typeface,
            style.center || isManga,
            style.border
        )
    }

    private fun sampleBg(b: Bitmap, r: Rect): Int {
        val m = 3
        val pts = listOf(
            r.left - m to r.top - m, r.centerX() to r.top - m, r.right + m to r.top - m,
            r.left - m to r.centerY(), r.right + m to r.centerY(),
            r.left - m to r.bottom + m, r.centerX() to r.bottom + m, r.right + m to r.bottom + m
        )
        var rs = 0
        var gs = 0
        var bs = 0
        for ((x, y) in pts) {
            val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
        }
        return Color.rgb(rs / pts.size, gs / pts.size, bs / pts.size)
    }

    private fun layoutFor(text: String, tp: TextPaint, w: Int, size: Float, center: Boolean): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    fun draw(c: Canvas, boxes: List<DrawBox>, density: Float, scale: Float) {
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val pad = 3 * density
        val rad = 4 * density
        for (b in boxes) {
            val w = max(b.rect.width(), 24 * density).toInt()
            tp.color = b.fg
            tp.typeface = b.typeface
            var lo = 8 * density
            var hi = max(lo, min(b.rect.height(), 44 * density) * scale)
            var layout = layoutFor(b.text, tp, w, lo, b.center)
            repeat(8) {
                val mid = (lo + hi) / 2
                val l = layoutFor(b.text, tp, w, mid, b.center)
                if (l.height <= b.rect.height() + pad) {
                    lo = mid
                    layout = l
                } else hi = mid
            }
            val h = max(layout.height + 2 * pad, b.rect.height())
            val bgRect = RectF(b.rect.left - pad, b.rect.top - pad / 2, b.rect.left + w + pad, b.rect.top - pad / 2 + h)
            bgP.color = (b.bg and 0x00FFFFFF) or (b.bgAlpha shl 24)
            c.drawRoundRect(bgRect, rad, rad, bgP)
            if (b.border) {
                stroke.color = b.fg
                c.drawRoundRect(bgRect, rad, rad, stroke)
            }
            c.save()
            c.translate(b.rect.left, bgRect.top + (bgRect.height() - layout.height) / 2)
            layout.draw(c)
            c.restore()
        }
    }
}
