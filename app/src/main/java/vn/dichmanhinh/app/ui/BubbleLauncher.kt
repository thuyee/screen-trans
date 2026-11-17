package vn.dichmanhinh.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.service.BubbleService
import vn.dichmanhinh.app.service.CaptureAccessibilityService

/** Bật / tắt bong bóng dịch màn hình từ bất kỳ đâu (nút trên trang chủ, ô Quick Settings…). */
object BubbleLauncher {
    /** Bật bong bóng. Trả về false nếu còn thiếu quyền (đã mở màn hình cấp quyền / xin ghi màn hình). */
    fun start(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, R.string.need_overlay, Toast.LENGTH_LONG).show()
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val prefs = Prefs(ctx)
        val accOk = Build.VERSION.SDK_INT >= 30 &&
            CaptureAccessibilityService.instance != null && prefs.captureMethod == "auto"
        val accOnly = prefs.mode == "acc" && CaptureAccessibilityService.instance != null
        if (accOk || accOnly) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_START)
            )
        } else {
            // Chế độ chụp + OCR cần cho phép ghi màn hình (hoặc bật Trợ năng để khỏi hỏi lại)
            ctx.startActivity(Intent(ctx, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, BubbleService::class.java))
    }

    fun toggle(ctx: Context) {
        if (BubbleService.running) stop(ctx) else start(ctx)
    }
}
