package vn.dichmanhinh.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.Util
import vn.dichmanhinh.app.engine.TranslatorFactory
import java.util.Locale

/**
 * Phiên âm + dịch trực tiếp cho bài nói dài (họp, bài giảng): nghe liên tục, hiện chữ và bản dịch song song.
 * Cần quyền micro và dịch vụ nhận dạng giọng nói của hệ thống.
 */
class TranscribeActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var btnSrc: Button
    private lateinit var btnDst: Button
    private lateinit var tvSrc: TextView
    private lateinit var tvDst: TextView
    private lateinit var btnToggle: Button
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var running = false
    private val committed = StringBuilder()
    private var partial = ""
    private var tJob: Job? = null

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else Toast.makeText(this, R.string.mic_perm, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.transcribe)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        val p = UiKit.dp(this, 12)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnSrc = Button(this).apply {
            setOnClickListener { LanguagePicker.show(this@TranscribeActivity, this@TranscribeActivity, false, srcLang()) { prefs.src = it; labels(); restartIfRunning() } }
        }
        btnDst = Button(this).apply {
            setOnClickListener { LanguagePicker.show(this@TranscribeActivity, this@TranscribeActivity, false, prefs.dst) { prefs.dst = it; labels(); scheduleTranslate() } }
        }
        top.addView(btnSrc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btnDst, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        tvSrc = TextView(this).apply { textSize = 18f; hint = getString(R.string.transcribe_hint); setTextIsSelectable(true) }
        tvDst = TextView(this).apply { textSize = 20f; setTextColor(ContextCompat.getColor(this@TranscribeActivity, R.color.brand)); setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(tvSrc) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ScrollView(this).apply { addView(tvDst) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        btnToggle = Button(this).apply { setOnClickListener { if (running) stop() else ensurePermAndStart() } }
        root.addView(btnToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 64)))
        setContentView(root)
        labels()
    }

    private fun srcLang(): String = if (prefs.src == Languages.AUTO) Locale.getDefault().language else prefs.src

    private fun labels() {
        btnSrc.text = Languages.name(this, srcLang())
        btnDst.text = Languages.name(this, prefs.dst)
        btnToggle.setText(if (running) R.string.transcribe_stop else R.string.transcribe_start)
    }

    private fun ensurePermAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, srcLang())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    private fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_missing, Toast.LENGTH_LONG).show()
            return
        }
        running = true
        labels()
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        sr?.startListening(intent())
    }

    private fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        sr?.stopListening()
        sr?.cancel()
        labels()
    }

    private fun restartIfRunning() {
        if (running) { sr?.cancel(); main.postDelayed({ if (running) sr?.startListening(intent()) }, 300) }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) {
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            render()
        }

        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) committed.append(t.trim()).append(". ")
            partial = ""
            render()
            if (running) main.postDelayed({ if (running) sr?.startListening(intent()) }, 200)
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    main.postDelayed({ if (running) sr?.startListening(intent()) }, 200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    sr?.cancel()
                    main.postDelayed({ if (running) sr?.startListening(intent()) }, 600)
                }
                else -> {
                    stop()
                    Toast.makeText(this@TranscribeActivity, getString(R.string.err_generic, "speech $error"), Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    private fun full(): String = (committed.toString() + partial).trim()

    private fun render() {
        tvSrc.text = full()
        scheduleTranslate()
    }

    private fun scheduleTranslate() {
        tJob?.cancel()
        val text = full()
        if (text.isEmpty()) { tvDst.text = ""; return }
        tJob = lifecycleScope.launch {
            delay(700)
            try {
                val tail = text.takeLast(2000)
                tvDst.text = TranslatorFactory.create(prefs).translate(listOf(tail), listOf(srcLang()), prefs.dst)[0]
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvDst.text = getString(R.string.err_generic, e.message ?: "")
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.copy)
        menu.add(0, 2, 1, R.string.clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { Util.copy(this, tvDst.text.toString()); Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show(); return true }
            2 -> { committed.clear(); partial = ""; render(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        super.onStop()
        if (running) stop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sr?.destroy()
        sr = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
