package vn.dichmanhinh.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.databinding.ActivityScreenBinding
import vn.dichmanhinh.app.service.BubbleService
import vn.dichmanhinh.app.service.CaptureAccessibilityService

/** Dịch màn hình: cấp quyền, chọn chế độ lấy chữ, bật/tắt bong bóng nổi. */
class ScreenActivity : AppCompatActivity() {
    private lateinit var b: ActivityScreenBinding
    private lateinit var prefs: Prefs
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScreenBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = getString(R.string.screen_translate)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        LangUi.bind(this, b.langRow.spSrc, b.langRow.btnSwap, b.langRow.spDst, prefs)

        b.rgMode.check(if (prefs.mode == "acc") R.id.rbAcc else R.id.rbOcr)
        b.rgMode.setOnCheckedChangeListener { _, id ->
            prefs.sp.edit().putString("mode", if (id == R.id.rbAcc) "acc" else "ocr").apply()
            refresh()
        }

        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnAcc.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnToggle.setOnClickListener {
            if (BubbleService.running) stopService(Intent(this, BubbleService::class.java)) else startBubble()
            b.root.postDelayed({ refresh() }, 400)
        }
        b.btnCamera.setOnClickListener { startActivity(Intent(this, CameraTranslateActivity::class.java)) }
        b.btnImage.setOnClickListener { startActivity(Intent(this, ImageTranslateActivity::class.java)) }
        b.btnText.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        b.btnModels.setOnClickListener { startActivity(Intent(this, ModelsActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnTutorial.setOnClickListener { Dialogs.tutorial(this) }
        b.btnShareApp.setOnClickListener { Dialogs.shareApp(this) }

        if (intent.getBooleanExtra("autostart", false)) startBubble()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        b.statusOverlay.setText(if (ov) R.string.status_overlay_ok else R.string.status_overlay_no)
        b.btnOverlay.visibility = if (ov) View.GONE else View.VISIBLE
        val acc = CaptureAccessibilityService.instance != null
        val accMode = prefs.mode == "acc"
        b.statusAcc.setText(
            when {
                acc -> R.string.status_acc_ok
                accMode -> R.string.status_acc_need
                else -> R.string.status_acc_no
            }
        )
        b.btnAcc.visibility = if (acc) View.GONE else View.VISIBLE
        b.btnToggle.setText(if (BubbleService.running) R.string.stop_bubble else R.string.start_bubble)
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Chế độ Trợ năng không cần chụp màn hình để dịch; chế độ OCR cần chụp.
        val accOk = Build.VERSION.SDK_INT >= 30 &&
            CaptureAccessibilityService.instance != null && prefs.captureMethod == "auto"
        val accOnly = prefs.mode == "acc" && CaptureAccessibilityService.instance != null
        if (accOk || accOnly) {
            ContextCompat.startForegroundService(
                this, Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_START)
            )
        } else {
            startActivity(Intent(this, ProjectionRequestActivity::class.java))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
