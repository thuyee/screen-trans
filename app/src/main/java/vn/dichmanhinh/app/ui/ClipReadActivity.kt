package vn.dichmanhinh.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import vn.dichmanhinh.app.service.BubbleService

/**
 * Cầu nối cho "Chép để dịch": Android 10+ chỉ cho app ĐANG HIỂN THỊ đọc clipboard. Khi dịch vụ phát hiện bạn vừa
 * sao chép, activity trong suốt này bật lên một nháy, đọc clipboard rồi chuyển chữ cho dịch vụ và tự đóng.
 * Bỏ qua nội dung được đánh dấu nhạy cảm (mật khẩu từ trình quản lý mật khẩu).
 */
class ClipReadActivity : AppCompatActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        val text = if (clip != null && clip.itemCount > 0 && !sensitive) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else null
        if (!text.isNullOrBlank() && text.length <= 5000) {
            startService(
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_CLIP)
                    .putExtra(BubbleService.EXTRA_TEXT, text)
            )
        }
        finish()
    }
}
