package vn.dichmanhinh.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.Item

/** Xem trước kiểu chữ bản dịch (phông, màu, nền, viền, manga…) trên một ảnh mẫu. */
class StylePreviewActivity : AppCompatActivity() {
    private lateinit var img: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pref_style_preview)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF111111.toInt()) }
        setContentView(img)
    }

    override fun onResume() {
        super.onResume()
        val w = 720
        val h = 540
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF455A64.toInt(), 0xFFCFD8DC.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val items = listOf(
            Item(Rect(40, 50, 680, 150), "こんにちは、世界！今日はいい天気ですね。", getString(R.string.sample_1)),
            Item(Rect(40, 200, 680, 300), "Hello, how are you today?", getString(R.string.sample_2)),
            Item(Rect(40, 350, 680, 470), "你好，欢迎使用屏幕翻译。", getString(R.string.sample_3))
        )
        val prefs = Prefs(this)
        BoxPainter.draw(c, BoxPainter.prepare(bmp, items, StyleCfg.from(prefs)), w / 360f, prefs.textScale / 100f)
        img.setImageBitmap(bmp)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
