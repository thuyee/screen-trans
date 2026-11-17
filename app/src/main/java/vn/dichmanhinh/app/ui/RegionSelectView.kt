package vn.dichmanhinh.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Kéo để chọn vùng cần dịch. */
class RegionSelectView(
    ctx: Context,
    private val hint: String,
    private val onDone: (Rect) -> Unit
) : View(ctx) {
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var active = false
    private val dim = Paint().apply { color = 0x88000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private fun sel() = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))

    override fun onDraw(c: Canvas) {
        if (!active) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            c.drawText(hint, width / 2f, height * 0.12f, txt)
            return
        }
        val r = sel()
        c.drawRect(0f, 0f, width.toFloat(), r.top, dim)
        c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
        c.drawRect(r, stroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; active = true }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                val r = sel()
                if (r.width() > 40 && r.height() > 40) {
                    onDone(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
                } else active = false
            }
        }
        invalidate()
        return true
    }
}
