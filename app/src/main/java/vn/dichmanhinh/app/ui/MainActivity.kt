package vn.dichmanhinh.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Entry
import vn.dichmanhinh.app.data.HistoryStore
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.SavedStore
import vn.dichmanhinh.app.data.Util
import vn.dichmanhinh.app.databinding.ActivityMainBinding
import vn.dichmanhinh.app.engine.Glossary
import vn.dichmanhinh.app.engine.GoogleWeb
import vn.dichmanhinh.app.engine.LangDetect
import vn.dichmanhinh.app.engine.OfflinePacks
import vn.dichmanhinh.app.engine.Speaker
import vn.dichmanhinh.app.engine.TranslatorFactory
import vn.dichmanhinh.app.engine.Net
import vn.dichmanhinh.app.service.BubbleService
import java.util.Locale

/**
 * Trang chủ kiểu Google Dịch: chọn ngôn ngữ, nhập/dán/nói/viết tay, kết quả tức thì kèm phiên âm + từ điển,
 * nghe, sao chép, chia sẻ, phóng to, gắn sao vào Sổ cụm từ; lối tắt Camera / Hội thoại / Phiên âm / Dịch màn hình.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var job: Job? = null
    private var idleJob: Job? = null

    private var curSrc = ""
    private var curDst = ""
    private var detected: String? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !t.isNullOrBlank()) setInput(t)
    }
    private val hand = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringExtra("text")
        if (r.resultCode == RESULT_OK && !t.isNullOrBlank()) setInput(t)
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.btnSaved.setOnClickListener { nav(R.id.nav_saved) }
        b.btnScreen.setOnClickListener { toggleScreen() }
        b.btnScreen.setOnLongClickListener { nav(R.id.nav_screen); true }
        b.btnAdvanced.setOnClickListener { chooseEngine() }
        b.btnMenu.setOnClickListener { showMenu() }

        b.btnSrc.setOnClickListener {
            LanguagePicker.show(this, this, true, prefs.src) { prefs.src = it; updateLangs(); schedule() }
        }
        b.btnDst.setOnClickListener {
            LanguagePicker.show(this, this, false, prefs.dst) { prefs.dst = it; updateLangs(); schedule() }
        }
        b.btnSwap.setOnClickListener { swap() }
        b.input.doAfterTextChanged { schedule() }
        b.btnClear.setOnClickListener { b.input.setText("") }
        b.btnMic.setOnClickListener { startVoice() }
        b.btnSpeakIn.setOnClickListener {
            val t = b.input.text.toString()
            if (t.isNotBlank()) Speaker.speak(this, t, speakLang())
        }
        b.btnSpeak.setOnClickListener { if (curDst.isNotEmpty()) Speaker.speak(this, curDst, prefs.dst) }
        b.btnCopy.setOnClickListener {
            if (curDst.isNotEmpty()) {
                Util.copy(this, curDst)
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        b.btnShare.setOnClickListener { if (curDst.isNotEmpty()) Util.shareText(this, curDst) }
        b.btnFull.setOnClickListener { fullscreen() }
        b.btnStar.setOnClickListener {
            if (curSrc.isEmpty()) return@setOnClickListener
            val on = SavedStore.toggle(this, curSrc, curDst, detected ?: prefs.src, prefs.dst)
            paintStar()
            Toast.makeText(this, if (on) R.string.saved_added else R.string.saved_removed, Toast.LENGTH_SHORT).show()
        }

        b.btnCamera.setOnClickListener { startActivity(Intent(this, CameraTranslateActivity::class.java)) }
        b.btnHand.setOnClickListener {
            hand.launch(Intent(this, HandwritingActivity::class.java).putExtra("lang", speakLang()))
        }
        b.btnLive.setOnClickListener { chooseLive() }

        updateLangs()
        if (savedInstanceState == null) {
            handleIntent(intent)
            if (!prefs.firstRunDone) firstRun()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateLangs()
        refreshRecent()
        refreshNav()
        if (b.input.text.isNotEmpty()) paintStar()
    }

    // ---------------------------------------------------------------- điều hướng

    private fun nav(id: Int) {
        when (id) {
            R.id.nav_history -> startActivity(Intent(this, HistoryActivity::class.java))
            R.id.nav_saved -> startActivity(Intent(this, HistoryActivity::class.java).putExtra("saved", true))
            R.id.nav_offline -> startActivity(Intent(this, ModelsActivity::class.java))
            R.id.nav_image -> startActivity(Intent(this, ImageTranslateActivity::class.java))
            R.id.nav_screen -> startActivity(Intent(this, ScreenActivity::class.java))
            R.id.nav_tap -> toggleTap()
            R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.nav_tutorial -> Dialogs.tutorial(this)
            R.id.nav_privacy -> Dialogs.privacy(this)
        }
    }

    private fun showMenu() {
        val pm = PopupMenu(this, b.btnMenu)
        pm.menuInflater.inflate(R.menu.more, pm.menu)
        pm.menu.findItem(R.id.nav_tap)?.title =
            getString(if (prefs.tapTranslate) R.string.tap_translate_on else R.string.tap_translate_off)
        pm.setOnMenuItemClickListener { nav(it.itemId); true }
        pm.show()
    }

    /** Nút "Dịch trực tiếp": chọn Hội thoại hai người hoặc Phiên âm bài nói dài. */
    private fun chooseLive() {
        val items = arrayOf(getString(R.string.conversation), getString(R.string.transcribe))
        AlertDialog.Builder(this)
            .setTitle(R.string.live_label)
            .setItems(items) { _, which ->
                startActivity(Intent(this, if (which == 0) ConversationActivity::class.java else TranscribeActivity::class.java))
            }
            .show()
    }

    /** Nút "Nâng cao": chọn công cụ dịch (Tự động, ngoại tuyến, Google web, Gemini, AI tương thích OpenAI…). */
    private fun chooseEngine() {
        val names = resources.getStringArray(R.array.engine_names)
        val values = resources.getStringArray(R.array.engine_values)
        val cur = values.indexOf(prefs.engine).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_engine)
            .setSingleChoiceItems(names, cur) { d, which ->
                val v = values[which]
                prefs.sp.edit().putString("engine", v).apply()
                d.dismiss()
                val needKey = (v == "google" && prefs.googleKey.isEmpty()) ||
                    (v == "gemini" && prefs.geminiKey.isEmpty()) || (v == "openai" && prefs.aiKey.isEmpty())
                if (needKey) {
                    Toast.makeText(this, R.string.adv_need_key, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else schedule()
            }
            .setNeutralButton(R.string.settings) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Nút bật / tắt dịch màn hình (bong bóng nổi). Nhấn giữ để mở cài đặt dịch màn hình. */
    private fun toggleScreen() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        val wasRunning = BubbleService.running
        BubbleLauncher.toggle(this)
        Toast.makeText(this, if (wasRunning) R.string.screen_off else R.string.screen_on, Toast.LENGTH_SHORT).show()
        b.root.postDelayed({ refreshNav() }, 700)
    }

    private fun refreshNav() {
        // nút dịch màn hình sáng lên khi bong bóng đang chạy
        val on = BubbleService.running
        b.btnScreen.setBackgroundResource(if (on) R.drawable.bg_circle_mic else R.drawable.bg_circle_btn)
        b.btnScreen.setColorFilter(if (on) ContextCompat.getColor(this, R.color.mic_icon) else 0, android.graphics.PorterDuff.Mode.SRC_IN)
        if (!on) b.btnScreen.clearColorFilter()
    }

    /** Chép để dịch: sao chép chữ ở app bất kỳ -> hiện khung dịch nhanh (cần dịch vụ nổi đang chạy). */
    private fun toggleTap() {
        val on = !prefs.tapTranslate
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        prefs.sp.edit().putBoolean("tap_translate", on).apply()
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(
                this, Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_START)
            )
            Toast.makeText(this, R.string.tap_translate_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val sl = i.getStringExtra("sl")
        val dl = i.getStringExtra("dl")
        if (!dl.isNullOrEmpty()) prefs.dst = dl
        if (sl != null) prefs.src = sl.ifEmpty { Languages.AUTO }
        val t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: i.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: i.getStringExtra("text")
        updateLangs()
        if (!t.isNullOrBlank()) setInput(t.toString())
    }

    /** Lần đầu: hướng dẫn nhanh, rồi hỏi có tải gói ngoại tuyến mặc định (Trung, Việt; Anh làm trung gian) không. */
    private fun firstRun() {
        Dialogs.tutorial(this) {
            AlertDialog.Builder(this)
                .setTitle(R.string.first_dl_title)
                .setMessage(getString(R.string.first_dl_msg, Languages.OFFLINE_DEFAULT.joinToString { Languages.name(this, it) }))
                .setPositiveButton(R.string.download_now) { _, _ ->
                    Toast.makeText(this, R.string.downloading, Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        OfflinePacks.ensureDefaults(prefs.wifiOnlyDl)
                        Toast.makeText(this@MainActivity, R.string.dl_done, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.later, null)
                .show()
            prefs.firstRunDone = true
        }
    }

    // ---------------------------------------------------------------- ngôn ngữ / nhập

    private fun updateLangs() {
        b.btnSrc.text = Languages.shortName(this, prefs.src)
        b.btnDst.text = Languages.shortName(this, prefs.dst)
    }

    private fun setInput(t: String) {
        b.input.setText(t)
        b.input.setSelection(b.input.text.length)
    }

    private fun speakLang(): String =
        if (prefs.src != Languages.AUTO) prefs.src else (detected ?: Locale.getDefault().language)

    private fun swap() {
        val s = if (prefs.src == Languages.AUTO) detected else prefs.src
        val d = prefs.dst
        val moveText = curDst.takeIf { it.isNotEmpty() }
        if (s == null) {
            prefs.src = d
            prefs.dst = if (d == "en") "vi" else "en"
        } else {
            prefs.src = d
            prefs.dst = s
        }
        updateLangs()
        if (moveText != null) setInput(moveText) else schedule()
    }

    private fun startVoice() {
        val tag = if (prefs.src == Languages.AUTO) Locale.getDefault().toLanguageTag() else prefs.src
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        try {
            voice.launch(i)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.voice_missing, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- dịch

    private fun schedule() {
        job?.cancel()
        idleJob?.cancel()
        val t = b.input.text.toString().trim()
        b.btnClear.visibility = if (t.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (t.isEmpty()) {
            b.resultBox.visibility = View.GONE
            b.tvDetected.text = ""
            curSrc = ""
            curDst = ""
            detected = null
            return
        }
        job = lifecycleScope.launch {
            delay(450)
            translate(t)
        }
    }

    private suspend fun translate(t: String) {
        val dst = prefs.dst
        val srcSel = prefs.src
        var det: String? = if (srcSel == Languages.AUTO) LangDetect.detect(t) else null
        var out = ""
        var rom: String? = null
        var details = ""
        var done = false
        var webFailed = false

        val web = (prefs.engine == "auto" || prefs.engine == "gtx") && Net.hasNetwork()
        if (web) {
            try {
                val short = t.length <= 60 && t.trim().split(Regex("\\s+")).size <= 4
                val r = GoogleWeb.translate(t, srcSel, dst, dict = short)
                out = Glossary.apply(r.text, prefs.glossary)
                rom = r.romanization
                details = GoogleWeb.formatDetails(r)
                if (srcSel == Languages.AUTO && r.detected != null) det = r.detected
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                webFailed = true
            }
        }
        if (!done) {
            val s = if (srcSel == Languages.AUTO) (det ?: Languages.AUTO) else srcSel
            try {
                out = if (s == dst) t else {
                    val tr = if (webFailed && prefs.engine == "auto") TranslatorFactory.mlkit() else TranslatorFactory.create(prefs)
                    Glossary.apply(tr.translate(listOf(t), listOf(s), dst)[0], prefs.glossary)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out = getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
                show("", out, null, "", null)
                curSrc = ""
                curDst = ""
                return
            }
        }
        detected = det
        show(t, out, rom, details, det)
        // Lưu lịch sử khi người dùng ngừng gõ ~2 giây
        idleJob = lifecycleScope.launch {
            delay(2000)
            if (prefs.saveHistory && out.isNotBlank() && out != t) {
                HistoryStore.addSmart(this@MainActivity, t, out, det ?: srcSel, dst)
            }
        }
    }

    private fun show(src: String, out: String, rom: String?, details: String, det: String?) {
        curSrc = src
        curDst = if (src.isEmpty()) "" else out
        b.output.text = out
        b.romanization.text = rom?.let { "[$it]" } ?: ""
        b.romanization.visibility = if (rom.isNullOrBlank()) View.GONE else View.VISIBLE
        b.details.text = details
        b.details.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
        b.resultBox.visibility = View.VISIBLE
        b.tvDetected.text = if (prefs.src == Languages.AUTO && det != null)
            getString(R.string.detected, Languages.name(this, det)) else ""
        paintStar()
    }

    private fun paintStar() {
        val saved = curSrc.isNotEmpty() && SavedStore.contains(this, curSrc, prefs.dst)
        b.btnStar.setImageResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun fullscreen() {
        if (curDst.isEmpty()) return
        val tv = TextView(this).apply {
            text = curDst
            textSize = 40f
            setTextIsSelectable(true)
            val p = UiKit.dp(this@MainActivity, 24)
            setPadding(p, p, p, p)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setView(sv)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ -> Util.copy(this, curDst) }
            .show()
    }

    // ---------------------------------------------------------------- gần đây

    private fun refreshRecent() {
        val list = HistoryStore.all(this).take(6)
        b.recentList.removeAllViews()
        b.recentTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEach { e -> b.recentList.addView(row(e)) }
    }

    private fun row(e: Entry): View {
        val v = layoutInflater.inflate(R.layout.item_entry, b.recentList, false)
        EntryUi.bind(this, v, e, onClick = {
            prefs.src = e.sl.ifEmpty { Languages.AUTO }
            prefs.dst = e.dl
            updateLangs()
            setInput(e.src)
        }, onStar = { paintStar() })
        return v
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }
}
