package vn.dichmanhinh.app.data

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import vn.dichmanhinh.app.R
import java.text.Collator
import java.util.Locale

object Languages {
    const val AUTO = "auto"

    /**
     * Gói ngoại tuyến mặc định: chỉ Trung + Anh + Việt để app nhẹ.
     * Mọi ngôn ngữ khác tải thêm khi cần.
     */
    val OFFLINE_DEFAULT = listOf(TranslateLanguage.ENGLISH, TranslateLanguage.VIETNAMESE, TranslateLanguage.CHINESE)

    /** Danh sách ngôn ngữ dịch trực tuyến (Google Dịch web) — nhiều hơn số ngôn ngữ dịch được ngoại tuyến. */
    val CATALOG: List<String> = listOf(
        "af", "sq", "am", "ar", "hy", "as", "ay", "az", "bm", "eu", "be", "bn", "bho", "bs", "bg", "ca", "ceb",
        "zh", "zh-TW", "co", "hr", "cs", "da", "dv", "doi", "nl", "en", "eo", "et", "ee", "tl", "fi", "fr", "fy",
        "gl", "ka", "de", "el", "gn", "gu", "ht", "ha", "haw", "he", "hi", "hmn", "hu", "is", "ig", "ilo", "id",
        "ga", "it", "ja", "jv", "kn", "kk", "km", "rw", "gom", "ko", "kri", "ku", "ckb", "ky", "lo", "la", "lv",
        "ln", "lt", "lg", "lb", "mk", "mai", "mg", "ms", "ml", "mt", "mi", "mr", "mni-Mtei", "lus", "mn", "my",
        "ne", "no", "ny", "or", "om", "ps", "fa", "pl", "pt", "pa", "qu", "ro", "ru", "sm", "sa", "gd", "nso",
        "sr", "st", "sn", "sd", "si", "sk", "sl", "so", "es", "su", "sw", "sv", "tg", "ta", "tt", "te", "th",
        "ti", "ts", "tr", "tk", "ak", "uk", "ur", "ug", "uz", "vi", "cy", "xh", "yi", "yo", "zu"
    )

    private val ML: Set<String> by lazy { TranslateLanguage.getAllLanguages().toSet() }

    /** Ngôn ngữ này có thể dịch NGOẠI TUYẾN bằng ML Kit (sau khi tải gói). */
    fun mlSupported(code: String) = code in ML

    private fun uiLocale(ctx: Context) = Locale(ctx.getString(R.string.ui_locale))

    fun name(ctx: Context, code: String): String {
        if (code == AUTO) return ctx.getString(R.string.lang_auto)
        if (code == "zh") return ctx.getString(R.string.lang_zh_cn)
        if (code == "zh-TW") return ctx.getString(R.string.lang_zh_tw)
        val l = uiLocale(ctx)
        val n = Locale.forLanguageTag(code).getDisplayLanguage(l)
        if (n.isEmpty() || n.equals(code, ignoreCase = true)) return code.uppercase()
        return n.replaceFirstChar { it.titlecase(l) }
    }

    /** Toàn bộ ngôn ngữ chọn được, sắp theo tên hiển thị. */
    fun all(ctx: Context): List<String> {
        val collator = Collator.getInstance(uiLocale(ctx))
        return CATALOG.sortedWith { a, b -> collator.compare(name(ctx, a), name(ctx, b)) }
    }

    /** Chỉ những ngôn ngữ có gói ngoại tuyến (danh sách cho màn hình Gói ngôn ngữ). */
    fun offlineCapable(ctx: Context): List<String> {
        val collator = Collator.getInstance(uiLocale(ctx))
        return ML.sortedWith { a, b -> collator.compare(name(ctx, a), name(ctx, b)) }
    }

    /** Tên tiếng Anh dùng cho prompt của mô hình ngôn ngữ. */
    fun englishName(code: String): String = when (code) {
        "zh" -> "Simplified Chinese"
        "zh-TW" -> "Traditional Chinese"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
    }
}
