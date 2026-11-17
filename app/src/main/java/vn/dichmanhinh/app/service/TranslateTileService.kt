package vn.dichmanhinh.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import vn.dichmanhinh.app.ui.TrampolineActivity

/** Ô cài đặt nhanh: kéo thanh trạng thái xuống rồi chạm để dịch màn hình. */
class TranslateTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (BubbleService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, TrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
