package vn.dichmanhinh.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import vn.dichmanhinh.app.R

/**
 * Viết tay: vẽ chữ lên khung, gợi ý hiện ngay (ML Kit Digital Ink, chạy trên máy). Gói viết tay của mỗi ngôn ngữ
 * (~vài chục MB) được tải ở lần dùng đầu tiên. Bấm "Xong" để đưa chữ về trang chủ.
 */
class HandwritingActivity : AppCompatActivity() {
    private lateinit var ink: InkView
    private lateinit var candidates: LinearLayout
    private lateinit var etText: EditText
    private lateinit var status: TextView
    private var recognizer: DigitalInkRecognizer? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.handwriting)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val p = UiKit.dp(this, 12)
        val lang = intent.getStringExtra("lang") ?: "en"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p) }
        etText = EditText(this).apply { hint = getString(R.string.handwriting_hint); textSize = 22f; setSingleLine(false) }
        status = TextView(this)
        root.addView(etText)
        root.addView(status)

        candidates = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(candidates) })

        ink = InkView(this) { recognizeSoon() }
        root.addView(ink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = p })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(label: Int, f: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { f() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        btn(R.string.hw_clear) { clearCanvas() }
        btn(R.string.hw_space) { etText.append(" ") }
        btn(R.string.hw_back) { val t = etText.text; if (t.isNotEmpty()) etText.setText(t.dropLast(1)); etText.setSelection(etText.text.length) }
        btn(R.string.done) {
            setResult(RESULT_OK, Intent().putExtra("text", etText.text.toString()))
            finish()
        }
        root.addView(bar)
        setContentView(root)
        prepare(lang)
    }

    /** Mã ngôn ngữ của app -> thẻ ngôn ngữ của mô hình viết tay. */
    private fun tagFor(code: String) = when (code) {
        "zh", "zh-TW" -> "zh-Hani-CN"
        "en" -> "en-US"
        "pt" -> "pt-BR"
        else -> code
    }

    private fun prepare(lang: String) {
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tagFor(lang))
        } catch (e: MlKitException) {
            null
        }
        if (id == null) {
            Toast.makeText(this, R.string.hw_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val model = DigitalInkRecognitionModel.builder(id).build()
        lifecycleScope.launch {
            try {
                val mgr = RemoteModelManager.getInstance()
                if (!mgr.isModelDownloaded(model).await()) {
                    status.setText(R.string.hw_downloading)
                    mgr.download(model, DownloadConditions.Builder().build()).await()
                }
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                status.text = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.err_generic, e.message ?: "")
            }
        }
    }

    private fun recognizeSoon() {
        job?.cancel()
        job = lifecycleScope.launch {
            delay(600)
            val r = recognizer ?: return@launch
            try {
                val res = r.recognize(ink.build()).await()
                showCandidates(res.candidates.map { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = getString(R.string.err_generic, e.message ?: "")
            }
        }
    }

    private fun showCandidates(list: List<String>) {
        candidates.removeAllViews()
        list.take(8).forEach { s ->
            candidates.addView(Button(this).apply {
                this.text = s
                setOnClickListener {
                    etText.append(s)
                    etText.setSelection(etText.text.length)
                    clearCanvas()
                }
            })
        }
    }

    private fun clearCanvas() {
        job?.cancel()
        ink.reset()
        candidates.removeAllViews()
    }

    override fun onDestroy() {
        recognizer?.close()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class InkView(ctx: Context, val onStroke: () -> Unit) : View(ctx) {
        private var builder = Ink.builder()
        private var stroke: Ink.Stroke.Builder? = null
        private var cur = Path()
        private val done = ArrayList<Path>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setBackgroundColor(Color.WHITE)
        }

        fun build(): Ink = builder.build()

        fun reset() {
            builder = Ink.builder()
            stroke = null
            cur = Path()
            done.clear()
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            done.forEach { c.drawPath(it, paint) }
            c.drawPath(cur, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val t = System.currentTimeMillis()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    stroke = Ink.Stroke.builder().also { it.addPoint(Ink.Point.create(e.x, e.y, t)) }
                    cur = Path().also { it.moveTo(e.x, e.y) }
                }
                MotionEvent.ACTION_MOVE -> {
                    stroke?.addPoint(Ink.Point.create(e.x, e.y, t))
                    cur.lineTo(e.x, e.y)
                }
                MotionEvent.ACTION_UP -> {
                    stroke?.addPoint(Ink.Point.create(e.x, e.y, t))
                    stroke?.let { builder.addStroke(it.build()) }
                    stroke = null
                    done.add(cur)
                    cur = Path()
                    onStroke()
                }
            }
            invalidate()
            return true
        }
    }
}
