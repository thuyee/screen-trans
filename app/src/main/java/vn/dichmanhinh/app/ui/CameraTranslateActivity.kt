package vn.dichmanhinh.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.OcrPackMissing
import vn.dichmanhinh.app.engine.Pipeline
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Dịch bằng camera: "Dịch" = đóng băng một khung hình rồi dịch; "Liên tục" = dịch lặp lại theo thời gian thực.
 * Mọi thứ xử lý trên máy (ML Kit), không gửi ảnh đi đâu.
 */
class CameraTranslateActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var preview: PreviewView
    private lateinit var frozen: ImageView
    private lateinit var overlay: BoxOverlay
    private lateinit var btnTranslate: Button
    private lateinit var btnLive: Button
    private var camera: Camera? = null
    private var torch = false
    private var liveJob: Job? = null
    private var shotJob: Job? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<((Bitmap) -> Unit)?>(null)

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, R.string.cam_perm, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_camera)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        frozen = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        overlay = BoxOverlay(this)
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(preview, full)
        root.addView(frozen, full)
        root.addView(overlay, full)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
            setPadding(16, 8, 16, 8)
        }
        val row = layoutInflater.inflate(R.layout.row_lang, panel, false)
        LangUi.bind(this, row.findViewById<Spinner>(R.id.spSrc), row.findViewById(R.id.btnSwap), row.findViewById<Spinner>(R.id.spDst), prefs)
        panel.addView(row)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: Int, onClick: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { onClick() }
        }.also {
            buttons.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        btnTranslate = btn(R.string.cam_translate) { onTranslateClick() }
        btnLive = btn(R.string.cam_live) { toggleLive() }
        btn(R.string.cam_torch) { toggleTorch() }
        btn(R.string.cam_gallery) {
            startActivity(Intent(this, ImageTranslateActivity::class.java).putExtra("pick", true))
        }
        panel.addView(buttons)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else perm.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val pv = Preview.Builder().setResolutionSelector(rs).build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            an.setAnalyzer(analysisExecutor) { proxy ->
                val cb = pending.getAndSet(null)
                if (cb != null) {
                    try { cb(toRotatedBitmap(proxy)) } catch (e: Exception) { /* bỏ qua khung lỗi */ }
                }
                proxy.close()
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toRotatedBitmap(p: ImageProxy): Bitmap {
        val bmp = p.toBitmap()
        val deg = p.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private suspend fun grabFrame(): Bitmap = suspendCancellableCoroutine { cont ->
        pending.set { bmp -> if (cont.isActive) cont.resume(bmp) }
        cont.invokeOnCancellation { pending.set(null) }
    }

    private suspend fun translateOnce(freeze: Boolean) {
        val bmp = grabFrame()
        val res = Pipeline.run(this, bmp)
        overlay.set(bmp.width, bmp.height, BoxPainter.prepare(bmp, res.items, StyleCfg.from(prefs)), prefs.textScale / 100f)
        if (freeze) {
            frozen.setImageBitmap(bmp)
            frozen.visibility = View.VISIBLE
            if (res.items.isEmpty()) Toast.makeText(this, R.string.no_text, Toast.LENGTH_SHORT).show()
        } else bmp.recycle()
    }

    private fun unfreeze() {
        frozen.visibility = View.GONE
        frozen.setImageDrawable(null)
        overlay.clear()
        btnTranslate.setText(R.string.cam_translate)
    }

    private fun onTranslateClick() {
        if (frozen.visibility == View.VISIBLE) { unfreeze(); return }
        stopLive()
        if (shotJob?.isActive == true) return
        shotJob = lifecycleScope.launch {
            btnTranslate.isEnabled = false
            try {
                translateOnce(true)
                btnTranslate.setText(R.string.cam_resume)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                btnTranslate.isEnabled = true
            }
        }
    }

    private fun toggleLive() {
        if (liveJob?.isActive == true) { stopLive(); return }
        if (frozen.visibility == View.VISIBLE) unfreeze()
        btnLive.setText(R.string.cam_live_stop)
        liveJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    translateOnce(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e)
                    stopLive()
                    return@launch
                }
                delay(900)
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        btnLive.setText(R.string.cam_live)
        if (frozen.visibility != View.VISIBLE) overlay.clear()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        torch = !torch
        cam.cameraControl.enableTorch(torch)
    }

    private fun showError(e: Exception) {
        val msg = if (e is OcrPackMissing) e.message else getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Vẽ bản dịch lên đúng vị trí trong khung hình (căn giữa, giữ tỉ lệ như PreviewView FIT_CENTER). */
    private class BoxOverlay(ctx: Context) : View(ctx) {
        private var bw = 0
        private var bh = 0
        private var boxes: List<DrawBox> = emptyList()
        private var scale = 1f

        fun set(w: Int, h: Int, b: List<DrawBox>, s: Float) {
            bw = w; bh = h; boxes = b; scale = s
            invalidate()
        }

        fun clear() {
            boxes = emptyList()
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (bw == 0 || bh == 0 || boxes.isEmpty()) return
            val s = min(width.toFloat() / bw, height.toFloat() / bh)
            c.save()
            c.translate((width - bw * s) / 2, (height - bh * s) / 2)
            c.scale(s, s)
            BoxPainter.draw(c, boxes, bw / 360f, scale)
            c.restore()
        }
    }
}
