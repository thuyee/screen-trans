package vn.dichmanhinh.app.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Lớp phủ bản dịch toàn màn hình.
 *  - Chạm khung dịch: xem chi tiết / sao chép (onBox).
 *  - Chạm chỗ trống: đóng. Nếu bật "Một chạm để đóng": chạm đâu cũng đóng.
 *  - Vuốt lên: đóng (nếu bật).
 */
class ResultView(
    ctx: Context,
    private val boxes: List<DrawBox>,
    private val scale: Float,
    private val oneTouch: Boolean,
    private val swipeClose: Boolean,
    private val onOutside: () -> Unit,
    private val onBox: (DrawBox) -> Unit
) : View(ctx) {

    private var downX = 0f
    private var downY = 0f
    private val swipePx = 80 * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        BoxPainter.draw(canvas, boxes, resources.displayMetrics.density, scale)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val dy = e.y - downY
                val dx = e.x - downX
                if (swipeClose && -dy > swipePx && abs(dy) > abs(dx)) {
                    onOutside()
                } else if (oneTouch) {
                    onOutside()
                } else {
                    val hit = boxes.firstOrNull { it.rect.contains(e.x, e.y) }
                    if (hit != null) onBox(hit) else onOutside()
                }
            }
        }
        return true
    }
}
