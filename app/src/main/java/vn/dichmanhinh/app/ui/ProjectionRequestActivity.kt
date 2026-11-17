package vn.dichmanhinh.app.ui

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.service.BubbleService

/** Activity trong suốt: xin quyền ghi màn hình rồi giao kết quả cho BubbleService. */
class ProjectionRequestActivity : AppCompatActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_PROJECTION)
                    .putExtra(BubbleService.EXTRA_CODE, r.resultCode)
                    .putExtra(BubbleService.EXTRA_DATA, data)
                    .putExtra(BubbleService.EXTRA_THEN, intent.getBooleanExtra(BubbleService.EXTRA_THEN, false))
            )
        } else {
            Toast.makeText(this, R.string.projection_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: mặc định "Toàn bộ màn hình"; có thể đổi sang "để tôi chọn" (một ứng dụng) trong Cài đặt.
            val cfg = if (Prefs(this).projScope == "choice") MediaProjectionConfig.createConfigForUserChoice()
            else MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(cfg)
        } else mpm.createScreenCaptureIntent()
        launcher.launch(i)
    }
}
