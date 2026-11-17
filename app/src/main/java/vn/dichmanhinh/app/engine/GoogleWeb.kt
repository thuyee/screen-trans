package vn.dichmanhinh.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Util
import java.net.URLEncoder

/**
 * Dịch qua điểm cuối web công khai của Google Translate (không cần khóa API) + tra từ điển.
 * Đây là điểm cuối không chính thức: có thể bị giới hạn tốc độ hoặc thay đổi; khi lỗi app tự quay về ML Kit.
 * Chỉ được gọi khi người dùng chọn công cụ này hoặc bấm "Tra từ điển".
 */
object GoogleWeb {
    data class Meaning(val pos: String, val terms: List<String>)
    data class Definition(val pos: String, val text: String, val example: String?)
    data class Result(
        val text: String,
        val romanization: String?,
        val detected: String?,
        val meanings: List<Meaning>,
        val definitions: List<Definition>
    )

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    private fun JSONArray.str(i: Int): String = if (isNull(i)) "" else optString(i, "")

    private fun call(text: String, src: String, dst: String, dict: Boolean): JSONArray {
        val dt = if (dict) "&dt=t&dt=rm&dt=bd&dt=md" else "&dt=t&dt=rm"
        val sl = if (src == Languages.AUTO) "auto" else src
        val url = "$ENDPOINT?client=gtx&sl=$sl&tl=$dst&ie=UTF-8&oe=UTF-8$dt"
        val body = "q=" + URLEncoder.encode(text, "UTF-8")
        val json = Util.post(url, body, "application/x-www-form-urlencoded; charset=UTF-8")
        return JSONArray(json)
    }

    private fun parse(d: JSONArray): Result {
        val sb = StringBuilder()
        var rom: String? = null
        val segs = d.optJSONArray(0)
        if (segs != null) {
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                val t = seg.str(0)
                if (t.isNotEmpty()) sb.append(t)
                else {
                    val r = seg.str(3).ifEmpty { seg.str(2) }
                    if (r.isNotEmpty()) rom = r
                }
            }
        }
        val meanings = ArrayList<Meaning>()
        d.optJSONArray(1)?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONArray(i) ?: continue
                val ts = e.optJSONArray(1) ?: continue
                val terms = List(ts.length()) { ts.str(it) }.filter { it.isNotEmpty() }.take(8)
                if (terms.isNotEmpty()) meanings.add(Meaning(e.str(0), terms))
            }
        }
        val defs = ArrayList<Definition>()
        d.optJSONArray(12)?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONArray(i) ?: continue
                val items = e.optJSONArray(1) ?: continue
                for (k in 0 until minOf(items.length(), 3)) {
                    val it2 = items.optJSONArray(k) ?: continue
                    val tx = it2.str(0)
                    if (tx.isNotEmpty()) defs.add(Definition(e.str(0), tx, it2.str(2).ifEmpty { null }))
                }
            }
        }
        val detected = d.str(2).ifEmpty { null }
        return Result(sb.toString(), rom, detected, meanings, defs)
    }

    /** Văn bản hiển thị: phiên âm, nghĩa theo loại từ, định nghĩa + ví dụ. */
    fun format(r: Result): String {
        val sb = StringBuilder()
        if (r.text.isNotEmpty()) sb.append(r.text).append('\n')
        r.romanization?.let { sb.append('[').append(it).append("]\n") }
        if (r.meanings.isNotEmpty()) {
            sb.append('\n')
            r.meanings.forEach { m ->
                if (m.pos.isNotEmpty()) sb.append(m.pos).append(": ")
                sb.append(m.terms.joinToString(", ")).append('\n')
            }
        }
        if (r.definitions.isNotEmpty()) {
            sb.append('\n')
            r.definitions.forEach { d ->
                sb.append("• ")
                if (d.pos.isNotEmpty()) sb.append('(').append(d.pos).append(") ")
                sb.append(d.text).append('\n')
                d.example?.let { sb.append("   \u201C").append(it).append("\u201D\n") }
            }
        }
        return sb.toString().trim()
    }

    /** Chỉ phần nghĩa theo loại từ + định nghĩa (không lặp lại bản dịch). */
    fun formatDetails(r: Result): String {
        val full = format(r.copy(text = "", romanization = null))
        return full.trim()
    }

    /** Dịch (kèm phiên âm). dict=true: thêm nghĩa theo loại từ và định nghĩa (hợp với từ/cụm ngắn). */
    suspend fun translate(text: String, src: String, dst: String, dict: Boolean): Result = withContext(Dispatchers.IO) {
        parse(call(text.take(4500), src, dst, dict))
    }

    suspend fun lookup(text: String, src: String, dst: String): Result = withContext(Dispatchers.IO) {
        parse(call(text.take(4500), src, dst, dict = true))
    }

    suspend fun translateOne(text: String, src: String, dst: String): String = withContext(Dispatchers.IO) {
        parse(call(text.take(4500), src, dst, dict = false)).text
    }
}

class GoogleWebTranslator : TextTranslator {
    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> =
        texts.mapIndexed { i, t -> GoogleWeb.translateOne(t, srcs[i], dst) }
}
