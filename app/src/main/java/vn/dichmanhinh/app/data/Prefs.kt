package vn.dichmanhinh.app.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager

/** Truy cập cài đặt tập trung. Khóa trùng với res/xml/preferences.xml. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)

    // ---- ngôn ngữ
    var src: String
        get() = s("src_lang", Languages.AUTO)
        set(v) = sp.edit().putString("src_lang", v).apply()
    var dst: String
        get() = s("dst_lang", "vi")
        set(v) = sp.edit().putString("dst_lang", v).apply()

    // ---- công cụ dịch
    val engine get() = s("engine", "auto")
    val googleKey get() = s("google_key", "").trim()
    val geminiKey get() = s("gemini_key", "").trim()
    val geminiModel get() = s("gemini_model", "gemini-2.5-flash").trim().ifEmpty { "gemini-2.5-flash" }
    val aiUrl get() = s("ai_url", "https://api.openai.com/v1").trim().trimEnd('/').ifEmpty { "https://api.openai.com/v1" }
    val aiKey get() = s("ai_key", "").trim()
    val aiModel get() = s("ai_model", "gpt-4o-mini").trim().ifEmpty { "gpt-4o-mini" }
    val glossary get() = s("glossary", "")
    val wifiOnlyDl get() = b("wifi_only_dl", false)

    // ---- chép để dịch (Tap to Translate) + ngôn ngữ gần đây + hội thoại
    val tapTranslate get() = b("tap_translate", false)
    val convSpeak get() = b("conv_speak", true)
    var convA: String
        get() = s("conv_a", "vi")
        set(v) = sp.edit().putString("conv_a", v).apply()
    var convB: String
        get() = s("conv_b", "en")
        set(v) = sp.edit().putString("conv_b", v).apply()
    var recentLangs: List<String>
        get() = s("recent_langs", "").split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("recent_langs", v.joinToString(",")).apply()

    fun pushRecent(code: String) {
        if (code == Languages.AUTO) return
        recentLangs = (listOf(code) + recentLangs.filter { it != code }).take(5)
    }

    // ---- chế độ / chụp
    /** "ocr" = chụp màn hình + nhận dạng chữ; "acc" = đọc chữ trực tiếp qua Trợ năng (nhanh, chính xác). */
    val mode get() = s("mode", "ocr")
    val captureMethod get() = s("capture_method", "auto")
    val projScope get() = s("proj_scope", "entire")
    val verticalSrc get() = b("vertical_src", false)
    val liveInterval get() = i("live_interval", 3)
    val typedTranslate get() = b("typed_translate", false)
    val typedDst get() = s("typed_dst", "en")

    // ---- biểu tượng nổi
    val iconStyle get() = s("icon_style", "0").toIntOrNull() ?: 0
    val iconColor get() = parseColor(s("icon_color", "#00897B"), 0xFF00897B.toInt())
    val bubbleSize get() = i("bubble_size", 52)
    val bubbleAlpha get() = i("bubble_alpha", 85)
    val edgeDim get() = b("edge_dim", true)
    val tapAction get() = s("tap_action", "full")
    val doubleTapRegion get() = b("double_tap_region", true)
    val longPress get() = s("long_press", "menu")
    val autoRegion get() = b("auto_region", false)

    // ---- hiển thị bản dịch
    val resultMode get() = s("result_mode", "overlay")
    val oneTouchClose get() = b("one_touch_close", false)
    val swipeClose get() = b("swipe_close", true)
    val hideClose get() = b("hide_close", false)
    val hideSettings get() = b("hide_settings", false)
    val font get() = s("font", "sans-serif")
    val textStyle get() = s("text_style", "normal")
    val textColor: Int? get() = s("text_color", "auto").let { if (it == "auto") null else parseColor(it, Color.WHITE) }
    val bgColor: Int? get() = s("bg_color", "auto").let { if (it == "auto") null else parseColor(it, 0xFF202124.toInt()) }
    val bgAlpha get() = i("bg_alpha", 95)
    val border get() = b("border", false)
    val center get() = b("center", false)
    val upcase get() = b("upcase", false)
    val textScale get() = i("text_scale", 100)
    val manga get() = b("manga_mode", false)
    val mangaFont get() = s("manga_font", "casual")
    val mangaBg get() = parseColor(s("manga_bg", "#FFFFFF"), Color.WHITE)

    // ---- giọng đọc
    val ttsVoice get() = s("tts_voice", "")
    val ttsSpeed get() = i("tts_speed", 100)
    val ttsPitch get() = i("tts_pitch", 100)
    val autoSpeak get() = b("auto_speak", false)

    // ---- giao diện
    val theme get() = s("theme", "system")
    val appLang get() = s("app_lang", "system")

    // ---- khác
    val saveHistory get() = b("save_history", true)
    val autoCopy get() = b("auto_copy", false)
    val haptics get() = b("haptics", true)

    var firstRunDone: Boolean
        get() = b("first_run_done", false)
        set(v) = sp.edit().putBoolean("first_run_done", v).apply()

    var bubbleX: Int
        get() = i("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()
    var bubbleY: Int
        get() = i("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    companion object {
        fun parseColor(v: String, def: Int): Int = try {
            Color.parseColor(v)
        } catch (e: Exception) {
            def
        }
    }
}
