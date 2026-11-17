package vn.dichmanhinh.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Util {
    @Volatile
    var lastSelfCopy = 0L

    fun copy(ctx: Context, text: String) {
        lastSelfCopy = android.os.SystemClock.uptimeMillis()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("dich", text))
    }

    fun shareText(ctx: Context, text: String) {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun hasCjk(s: String) = s.any {
        it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF || it.code in 0xAC00..0xD7AF
    }

    fun hasKana(s: String) = s.any { it.code in 0x3040..0x30FF }
    fun hasHangul(s: String) = s.any { it.code in 0xAC00..0xD7AF }

    /** POST đơn giản, không cần thư viện ngoài. Ném IOException nếu HTTP != 2xx. */
    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
