package vn.dichmanhinh.app.ui

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import vn.dichmanhinh.app.R

/** Các hộp thoại dùng chung: hướng dẫn, quyền riêng tư, chia sẻ app. */
object Dialogs {
    fun tutorial(ctx: Context, onClose: () -> Unit = {}) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.tutorial)
            .setMessage(R.string.tutorial_body)
            .setPositiveButton(R.string.got_it) { _, _ -> onClose() }
            .setOnCancelListener { onClose() }
            .show()
    }

    fun privacy(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.privacy)
            .setMessage(R.string.privacy_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun shareApp(ctx: Context) {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.share_app_text)), null
            )
        )
    }
}
