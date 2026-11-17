package vn.dichmanhinh.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.Speaker
import vn.dichmanhinh.app.engine.TranslatorFactory

/**
 * Hội thoại hai chiều: mỗi người bấm micro của ngôn ngữ mình, nói xong bản dịch hiện ra và được đọc to cho người kia.
 * Nhận dạng giọng nói dùng dịch vụ của hệ thống (hộp thoại nói của Google/OEM).
 */
class ConversationActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var micA: Button
    private lateinit var micB: Button
    private var side = 0

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !t.isNullOrBlank()) process(side, t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.conversation)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val p = UiKit.dp(this, 12)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, 0)
        }
        btnA = Button(this).apply { setOnClickListener { pick(0) } }
        btnB = Button(this).apply { setOnClickListener { pick(1) } }
        val swap = ImageButton(this).apply {
            setImageResource(R.drawable.ic_swap)
            setBackgroundResource(0)
            contentDescription = getString(R.string.swap)
            setOnClickListener {
                val a = prefs.convA
                prefs.convA = prefs.convB
                prefs.convB = a
                labels()
            }
        }
        top.addView(btnA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(swap)
        top.addView(btnB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        scroll = ScrollView(this).apply { addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p, p, p, p)
        }
        micA = Button(this).apply {
            setOnClickListener { listen(0) }
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
        }
        micB = Button(this).apply {
            setOnClickListener { listen(1) }
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
        }
        bottom.addView(micA, LinearLayout.LayoutParams(0, UiKit.dp(this, 64), 1f).apply { marginEnd = p / 2 })
        bottom.addView(micB, LinearLayout.LayoutParams(0, UiKit.dp(this, 64), 1f).apply { marginStart = p / 2 })
        root.addView(bottom)
        setContentView(root)
        labels()
    }

    private fun labels() {
        val a = Languages.name(this, prefs.convA)
        val b = Languages.name(this, prefs.convB)
        btnA.text = a
        btnB.text = b
        micA.text = a
        micB.text = b
    }

    private fun pick(s: Int) {
        val cur = if (s == 0) prefs.convA else prefs.convB
        LanguagePicker.show(this, this, false, cur) {
            if (s == 0) prefs.convA = it else prefs.convB = it
            labels()
        }
    }

    private fun listen(s: Int) {
        side = s
        val lang = if (s == 0) prefs.convA else prefs.convB
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        try {
            voice.launch(i)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.voice_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun process(s: Int, text: String) {
        val from = if (s == 0) prefs.convA else prefs.convB
        val to = if (s == 0) prefs.convB else prefs.convA
        lifecycleScope.launch {
            try {
                val out = TranslatorFactory.create(prefs).translate(listOf(text), listOf(from), to)[0]
                addBubble(s, text, out, to)
                if (prefs.convSpeak) Speaker.speak(this@ConversationActivity, out, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ConversationActivity, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addBubble(s: Int, src: String, dst: String, toLang: String) {
        val p = UiKit.dp(this, 14)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
            background = UiKit.round(if (s == 0) 0xFFD2E3FC.toInt() else 0xFFE8EAED.toInt(), UiKit.dp(this@ConversationActivity, 18).toFloat())
            setOnClickListener { Speaker.speak(this@ConversationActivity, dst, toLang) }
        }
        card.addView(TextView(this).apply { text = dst; textSize = 22f; setTextColor(0xFF1A1A1A.toInt()) })
        card.addView(TextView(this).apply { text = src; textSize = 14f; setTextColor(0xFF5F6368.toInt()) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (s == 0) Gravity.END else Gravity.START
            topMargin = p / 2
            val m = UiKit.dp(this@ConversationActivity, 48)
            if (s == 0) marginStart = m else marginEnd = m
        }
        messages.addView(card, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.conv_autospeak).apply { isCheckable = true; isChecked = prefs.convSpeak }
        menu.add(0, 2, 1, R.string.conv_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val on = !prefs.convSpeak
                prefs.sp.edit().putBoolean("conv_speak", on).apply()
                item.isChecked = on
                return true
            }
            2 -> { messages.removeAllViews(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
