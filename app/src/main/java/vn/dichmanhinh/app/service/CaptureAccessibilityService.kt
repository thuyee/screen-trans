package vn.dichmanhinh.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.Block
import vn.dichmanhinh.app.engine.LangDetect
import vn.dichmanhinh.app.engine.TranslatorFactory
import kotlin.coroutines.resume

/**
 * Dịch vụ Trợ năng. Ba việc, tất cả đều do bạn chủ động bật:
 *  1. Chụp màn hình bằng takeScreenshot (Android 11+) -> không phải xin quyền ghi màn hình mỗi lần.
 *  2. "Chế độ Trợ năng": đọc chữ hiển thị trên màn hình trực tiếp (nhanh, chính xác, không cần OCR).
 *  3. Tùy chọn "Dịch & thay chữ đang gõ": dịch ô nhập liệu rồi thay vào chỗ. Bỏ qua ô mật khẩu.
 * Không gửi dữ liệu đi đâu nếu bạn dùng công cụ dịch ngoại tuyến.
 */
class CaptureAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var typedJob: Job? = null
    private var lastSet: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val prefs = Prefs(this)
        if (!prefs.typedTranslate) return
        val node = event.source ?: return
        if (node.isPassword || !node.isEditable || node.isShowingHintText) return
        val text = node.text?.toString() ?: return
        if (text.length < 2 || text == lastSet) return
        typedJob?.cancel()
        typedJob = scope.launch { translateTyped(node, text, prefs) }
    }

    private suspend fun translateTyped(node: AccessibilityNodeInfo, text: String, prefs: Prefs) {
        delay(1400) // chờ người dùng gõ xong
        try {
            node.refresh()
            val cur = node.text?.toString() ?: return
            if (cur != text) return // đã gõ tiếp -> sự kiện mới sẽ xử lý
            val dst = prefs.typedDst
            val from = LangDetect.detect(cur) ?: Languages.AUTO
            if (from == dst) return
            val out = withContext(Dispatchers.Default) {
                TranslatorFactory.create(prefs).translate(listOf(cur), listOf(from), dst)[0]
            }
            node.refresh()
            if (node.text?.toString() != cur) return
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, out)
            }
            lastSet = out
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            App.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    /** Chữ đang hiển thị trên cửa sổ hiện tại, kèm tọa độ trên màn hình. Bỏ qua ô mật khẩu. */
    fun readScreen(): List<Block> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<Block>()
        walk(root, out, 0)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<Block>, depth: Int) {
        if (n == null || depth > 45 || out.size > 400) return
        if (n.isVisibleToUser && !n.isPassword) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length <= 1200 && t.any { it.isLetter() }) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 8 && r.height() > 8) out.add(Block(t, r))
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    @RequiresApi(30)
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        hb.close()
                    }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
    }

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
    }
}
