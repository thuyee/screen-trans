package vn.dichmanhinh.app.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.Util
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** srcs[i] là mã ngôn ngữ của texts[i] (hoặc Languages.AUTO nếu chưa rõ). */
interface TextTranslator {
    suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String>
}

object LangDetect {
    private val client by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build()
        )
    }

    suspend fun detect(text: String): String? {
        val tag = try {
            client.identifyLanguage(text).await()
        } catch (e: Exception) {
            return null
        }
        if (tag == "und") return null
        return TranslateLanguage.fromLanguageTag(tag)
    }
}

/**
 * Dịch ngoại tuyến bằng ML Kit. Gói mặc định: Trung, Anh, Việt. Ngôn ngữ khác sẽ được tải thêm
 * ở lần dùng đầu tiên (có thông báo) hoặc tải trước trong màn hình "Gói ngôn ngữ".
 */
class MlKitTranslator : TextTranslator {
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    private suspend fun client(s: String, d: String): com.google.mlkit.nl.translate.Translator {
        val key = "$s>$d"
        val c = clients.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(d).build()
            )
        }
        if (ready.add(key)) {
            try {
                val app = App.instance
                val missing = listOf(s, d).filter { it != TranslateLanguage.ENGLISH && !OfflinePacks.isDownloaded(it) }
                if (missing.isNotEmpty()) {
                    App.toast(app.getString(R.string.dl_start, missing.joinToString { Languages.name(app, it) }))
                }
                val wifi = Prefs(app).wifiOnlyDl
                val cond = DownloadConditions.Builder().apply { if (wifi) requireWifi() }.build()
                c.downloadModelIfNeeded(cond).await()
            } catch (e: Exception) {
                ready.remove(key)
                throw e
            }
        }
        return c
    }

    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> =
        withContext(Dispatchers.Default) {
            texts.mapIndexed { i, t ->
                val s = if (srcs[i] == Languages.AUTO) "en" else srcs[i]
                if (s == dst) t else client(s, dst).translate(t).await()
            }
        }
}

/** Google Cloud Translation v2 — dùng khóa API của chính người dùng. */
class GoogleTranslator(private val key: String) : TextTranslator {
    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> =
        withContext(Dispatchers.IO) {
            val single = srcs.distinct().singleOrNull()?.takeIf { it != Languages.AUTO }
            texts.chunked(100).flatMap { chunk ->
                val body = StringBuilder("target=").append(dst).append("&format=text")
                if (single != null) body.append("&source=").append(single)
                chunk.forEach { body.append("&q=").append(URLEncoder.encode(it, "UTF-8")) }
                val json = Util.post(
                    "https://translation.googleapis.com/language/translate/v2",
                    body.toString(),
                    "application/x-www-form-urlencoded; charset=UTF-8",
                    mapOf("X-Goog-Api-Key" to key)
                )
                val arr = JSONObject(json).getJSONObject("data").getJSONArray("translations")
                List(arr.length()) { arr.getJSONObject(it).getString("translatedText") }
            }
        }
}

private fun aiSystemPrompt(srcs: List<String>, dst: String): String {
    val single = srcs.distinct().singleOrNull()?.takeIf { it != Languages.AUTO }
    val dstName = Languages.englishName(dst)
    val srcName = single?.let { Languages.englishName(it) } ?: "auto-detected"
    return "You are a professional translator. The input is a JSON array of text fragments " +
        "captured from a phone screen (source language: $srcName). Translate each fragment " +
        "into $dstName. Rules: keep proper nouns, numbers, URLs, emoji and code unchanged; fix obvious " +
        "OCR mistakes silently; use natural wording for UI and chat text; do not add explanations. " +
        "Return ONLY a JSON array of strings with exactly the same number of elements and the same order."
}

private fun parseStringArray(raw: String, expected: Int): List<String> {
    var t = raw.trim()
    if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = JSONArray(t)
    if (arr.length() != expected) error("AI trả về ${arr.length()}/$expected đoạn")
    return List(arr.length()) { arr.getString(it) }
}

/** Gemini — dịch theo ngữ cảnh, sửa lỗi OCR, giữ tên riêng. Dùng khóa API của người dùng. */
class GeminiTranslator(private val key: String, private val model: String) : TextTranslator {
    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> =
        withContext(Dispatchers.IO) {
            val system = aiSystemPrompt(srcs, dst)
            val req = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put(
                    "contents", JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", JSONArray(texts).toString())))
                    )
                )
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
            val json = Util.post(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                req.toString(),
                "application/json; charset=UTF-8",
                mapOf("x-goog-api-key" to key)
            )
            val text = JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parseStringArray(text, texts.size)
        }
}

/**
 * Mọi dịch vụ tương thích OpenAI (OpenAI, DeepSeek, OpenRouter, Groq, máy chủ riêng…):
 * điền địa chỉ gốc (…/v1), khóa API và tên mô hình trong Cài đặt.
 */
class OpenAiCompatTranslator(
    private val baseUrl: String,
    private val key: String,
    private val model: String
) : TextTranslator {
    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> =
        withContext(Dispatchers.IO) {
            val req = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", aiSystemPrompt(srcs, dst)))
                        .put(JSONObject().put("role", "user").put("content", JSONArray(texts).toString()))
                )
            val json = Util.post(
                "$baseUrl/chat/completions",
                req.toString(),
                "application/json; charset=UTF-8",
                mapOf("Authorization" to "Bearer $key")
            )
            val text = JSONObject(json).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            parseStringArray(text, texts.size)
        }
}

object Net {
    fun hasNetwork(): Boolean {
        val cm = App.instance.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Chế độ "Tự động" (giống Google Dịch): có mạng -> dùng Google Dịch web (chất lượng tốt nhất, nhiều ngôn ngữ nhất);
 * không có mạng hoặc lỗi -> dùng gói ngoại tuyến ML Kit nếu cả hai ngôn ngữ đều hỗ trợ ngoại tuyến.
 */
class AutoTranslator : TextTranslator {
    override suspend fun translate(texts: List<String>, srcs: List<String>, dst: String): List<String> {
        val offlineOk = Languages.mlSupported(dst) &&
            srcs.all { it == Languages.AUTO || Languages.mlSupported(it) }
        if (Net.hasNetwork()) {
            try {
                return GoogleWebTranslator().translate(texts, srcs, dst)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!offlineOk) throw e
            }
        } else if (!offlineOk) {
            throw java.io.IOException(App.instance.getString(R.string.err_need_online))
        }
        return TranslatorFactory.mlkit().translate(texts, srcs, dst)
    }
}

object TranslatorFactory {
    private val ml by lazy { MlKitTranslator() }
    fun mlkit(): TextTranslator = ml

    /** true nếu công cụ đang chọn gửi chữ ra Internet (khi lỗi sẽ quay về ML Kit). */
    fun isOnline(p: Prefs) = when (p.engine) {
        "gtx" -> true
        "google" -> p.googleKey.isNotEmpty()
        "gemini" -> p.geminiKey.isNotEmpty()
        "openai" -> p.aiKey.isNotEmpty()
        else -> false
    }

    fun create(p: Prefs): TextTranslator = when {
        p.engine == "auto" -> AutoTranslator()
        p.engine == "gtx" -> GoogleWebTranslator()
        p.engine == "google" && p.googleKey.isNotEmpty() -> GoogleTranslator(p.googleKey)
        p.engine == "gemini" && p.geminiKey.isNotEmpty() -> GeminiTranslator(p.geminiKey, p.geminiModel)
        p.engine == "openai" && p.aiKey.isNotEmpty() -> OpenAiCompatTranslator(p.aiUrl, p.aiKey, p.aiModel)
        else -> ml
    }
}
