package vn.dichmanhinh.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import vn.dichmanhinh.app.service.BubbleService

/** Cầu nối cho Quick Tile: thanh thông báo đã thu lại trước khi chụp. */
class TrampolineActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BubbleService.running) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_TRANSLATE)
                    .putExtra(BubbleService.EXTRA_DELAY, 500L)
            )
        } else {
            startActivity(Intent(this, ScreenActivity::class.java).putExtra("autostart", true))
        }
        finish()
    }
}
