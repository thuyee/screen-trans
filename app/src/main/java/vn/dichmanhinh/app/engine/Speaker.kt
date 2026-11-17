package vn.dichmanhinh.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import java.util.Locale

/** Đọc to bằng TTS của hệ thống, theo tốc độ / cao độ / giọng trong Cài đặt. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: (() -> Unit)? = null

    fun speak(ctx: Context, text: String, lang: String) {
        val app = ctx.applicationContext
        val body = text.take(3900)
        if (body.isBlank()) return
        val go = { say(app, body, lang) }
        if (tts == null) {
            queued = go
            tts = TextToSpeech(app) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) queued?.invoke() else App.toast(app.getString(R.string.tts_missing))
                queued = null
            }
        } else if (ready) go() else queued = go
    }

    private fun say(app: Context, text: String, lang: String) {
        val t = tts ?: return
        val p = Prefs(app)
        val loc = Locale.forLanguageTag(lang)
        t.setSpeechRate(p.ttsSpeed / 100f)
        t.setPitch(p.ttsPitch / 100f)
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            App.toast(app.getString(R.string.tts_missing))
            return
        }
        val want = p.ttsVoice
        if (want.isNotEmpty()) {
            t.voices?.firstOrNull { it.name == want && it.locale.language == loc.language }?.let { t.voice = it }
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dmh")
    }

    fun stop() {
        tts?.stop()
    }
}
