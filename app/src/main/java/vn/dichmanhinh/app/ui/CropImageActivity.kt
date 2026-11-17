package vn.dichmanhinh.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import vn.dichmanhinh.app.R
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Cắt ảnh trước khi dịch. Nhận "path" (JPEG trong cache), trả về "path" của ảnh đã cắt. */
class CropImageActivity : AppCompatActivity() {
    private lateinit var crop: CropView
    private lateinit var src: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.crop)
        val path = intent.getStringExtra("path")
        val bmp = path?.let { BitmapFactory.decodeFile(it) }
        if (bmp == null) { finish(); return }
        src = bmp
        crop = CropView(this, bmp)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(crop, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })
        bar.addView(Button(this).apply {
            text = getString(R.string.done)
            setOnClickListener { done() }
        })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 48 })
        setContentView(root)
    }

    private fun done() {
        val r = crop.selection()
        val out = Bitmap.createBitmap(src, r.left.toInt(), r.top.toInt(), max(1, r.width().toInt()), max(1, r.height().toInt()))
        val f = File(File(cacheDir, "images").apply { mkdirs() }, "cropped.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        setResult(RESULT_OK, Intent().putExtra("path", f.absolutePath))
        finish()
    }

    private class CropView(ctx: Context, val bmp: Bitmap) : View(ctx) {
        private val sel = RectF()          // trong toạ độ view
        private var scale = 1f
        private var ox = 0f
        private var oy = 0f
        private var mode = 0               // 0 none, 1 move, 2..5 góc TL,TR,BL,BR
        private var lx = 0f
        private var ly = 0f
        private val dim = Paint().apply { color = 0xAA000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
        }
        private val handle = 28f * resources.displayMetrics.density

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            ox = (w - bmp.width * scale) / 2
            oy = (h - bmp.height * scale) / 2
            val iw = bmp.width * scale
            val ih = bmp.height * scale
            sel.set(ox + iw * 0.1f, oy + ih * 0.1f, ox + iw * 0.9f, oy + ih * 0.9f)
        }

        fun selection(): RectF = RectF(
            ((sel.left - ox) / scale).coerceIn(0f, bmp.width - 1f),
            ((sel.top - oy) / scale).coerceIn(0f, bmp.height - 1f),
            ((sel.right - ox) / scale).coerceIn(1f, bmp.width.toFloat()),
            ((sel.bottom - oy) / scale).coerceIn(1f, bmp.height.toFloat())
        )

        override fun onDraw(c: Canvas) {
            c.save(); c.translate(ox, oy); c.scale(scale, scale); c.drawBitmap(bmp, 0f, 0f, null); c.restore()
            c.drawRect(0f, 0f, width.toFloat(), sel.top, dim)
            c.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dim)
            c.drawRect(0f, sel.top, sel.left, sel.bottom, dim)
            c.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dim)
            c.drawRect(sel, line)
            for ((x, y) in listOf(sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom))
                c.drawCircle(x, y, handle / 2, line)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y
                    mode = when {
                        near(e, sel.left, sel.top) -> 2
                        near(e, sel.right, sel.top) -> 3
                        near(e, sel.left, sel.bottom) -> 4
                        near(e, sel.right, sel.bottom) -> 5
                        sel.contains(e.x, e.y) -> 1
                        else -> 0
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx
                    val dy = e.y - ly
                    lx = e.x; ly = e.y
                    val minS = handle * 1.5f
                    val l = ox; val t = oy; val r = ox + bmp.width * scale; val b = oy + bmp.height * scale
                    when (mode) {
                        1 -> {
                            val mx = dx.coerceIn(l - sel.left, r - sel.right)
                            val my = dy.coerceIn(t - sel.top, b - sel.bottom)
                            sel.offset(mx, my)
                        }
                        2 -> { sel.left = (sel.left + dx).coerceIn(l, sel.right - minS); sel.top = (sel.top + dy).coerceIn(t, sel.bottom - minS) }
                        3 -> { sel.right = (sel.right + dx).coerceIn(sel.left + minS, r); sel.top = (sel.top + dy).coerceIn(t, sel.bottom - minS) }
                        4 -> { sel.left = (sel.left + dx).coerceIn(l, sel.right - minS); sel.bottom = (sel.bottom + dy).coerceIn(sel.top + minS, b) }
                        5 -> { sel.right = (sel.right + dx).coerceIn(sel.left + minS, r); sel.bottom = (sel.bottom + dy).coerceIn(sel.top + minS, b) }
                    }
                    invalidate()
                }
            }
            return true
        }

        private fun near(e: MotionEvent, x: Float, y: Float) = abs(e.x - x) < handle && abs(e.y - y) < handle
    }
}
