package vn.dichmanhinh.app.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.Util
import vn.dichmanhinh.app.databinding.ActivityImageBinding
import vn.dichmanhinh.app.engine.Item
import vn.dichmanhinh.app.engine.Pipeline
import java.io.File

/**
 * Dịch ảnh từ thư viện / camera / chia sẻ. Cắt vùng cần dịch, lưu ảnh đã dịch vào thư viện,
 * chạm vào ảnh để đổi qua lại bản gốc – bản dịch.
 */
class ImageTranslateActivity : AppCompatActivity() {
    private lateinit var b: ActivityImageBinding
    private lateinit var prefs: Prefs
    private var original: Bitmap? = null
    private var translated: Bitmap? = null
    private var items: List<Item> = emptyList()
    private var showTranslated = true
    private var camUri: Uri? = null

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { load(it) } }
    private val shoot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) camUri?.let { load(it) }
    }
    private val cropper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra("path")
        if (r.resultCode == RESULT_OK && path != null) loadFile(File(path))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = getString(R.string.btn_image)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        LangUi.bind(this, b.langRow.spSrc, b.langRow.btnSwap, b.langRow.spDst, prefs) {
            // đổi ngôn ngữ: dịch lại ảnh đang mở
            original?.let { retranslate(it) }
        }

        b.btnPick.setOnClickListener { pick.launch("image/*") }
        b.btnCamera.setOnClickListener {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "cam.jpg"))
            camUri = uri
            shoot.launch(uri)
        }
        b.btnLiveCamera.setOnClickListener { startActivity(Intent(this, CameraTranslateActivity::class.java)) }
        b.image.setOnClickListener {
            if (translated != null) {
                showTranslated = !showTranslated
                b.image.setImageBitmap(if (showTranslated) translated else original)
            }
        }
        b.btnCrop.setOnClickListener { crop() }
        b.btnCopy.setOnClickListener {
            if (items.isNotEmpty()) {
                Util.copy(this, items.joinToString("\n") { it.dst })
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        b.btnSave.setOnClickListener { save() }
        b.btnShare.setOnClickListener { share() }

        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { load(it) }
        } else if (savedInstanceState == null && intent.getBooleanExtra("pick", false)) {
            pick.launch("image/*")
        }
    }

    private fun scaleTo(info: ImageDecoder.ImageInfo) =
        minOf(1f, 2400f / maxOf(info.size.width, info.size.height))

    private fun configure(dec: ImageDecoder, info: ImageDecoder.ImageInfo) {
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = scaleTo(info)
        dec.setTargetSize(
            (info.size.width * s).toInt().coerceAtLeast(1),
            (info.size.height * s).toInt().coerceAtLeast(1)
        )
    }

    private fun decode(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ -> configure(dec, info) }

    private fun decodeFile(f: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ -> configure(dec, info) }

    private fun load(uri: Uri) = start { decode(uri) }
    private fun loadFile(f: File) = start { decodeFile(f) }

    private fun start(decoder: () -> Bitmap) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { decoder() }
                original = bmp
                translateBitmap(bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ImageTranslateActivity, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun retranslate(bmp: Bitmap) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                translateBitmap(bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ImageTranslateActivity, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private suspend fun translateBitmap(bmp: Bitmap) {
        val res = Pipeline.run(this, bmp)
        items = res.items
        if (items.isEmpty()) {
            translated = null
            b.image.setImageBitmap(bmp)
            Toast.makeText(this, R.string.no_text, Toast.LENGTH_SHORT).show()
        } else {
            val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
            // "density" tương đối theo bề rộng ảnh (coi như màn hình 360dp)
            BoxPainter.draw(
                Canvas(out), BoxPainter.prepare(bmp, items, StyleCfg.from(prefs)),
                bmp.width / 360f, prefs.textScale / 100f
            )
            translated = out
            showTranslated = true
            b.image.setImageBitmap(out)
        }
    }

    private fun crop() {
        val src = original ?: return
        val f = File(File(cacheDir, "images").apply { mkdirs() }, "crop_src.jpg")
        f.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cropper.launch(Intent(this, CropImageActivity::class.java).putExtra("path", f.absolutePath))
    }

    private fun save() {
        val bmp = translated ?: return
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "dich_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DichManHinh")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("insert")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        val bmp = translated ?: return
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "translated.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null
            )
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
