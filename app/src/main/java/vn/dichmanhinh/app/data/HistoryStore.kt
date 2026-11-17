package vn.dichmanhinh.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Entry(val t: Long, val src: String, val dst: String, val sl: String, val dl: String)

/** Kho lưu mục dịch cục bộ (JSON trong bộ nhớ riêng của app). Dùng cho Lịch sử và Sổ cụm từ (đã lưu). */
open class EntryStore(private val fileName: String, private val max: Int) {
    private fun file(ctx: Context) = File(ctx.filesDir, fileName)

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) {
                val o = a.getJSONObject(it)
                Entry(o.getLong("t"), o.getString("s"), o.getString("d"), o.optString("sl"), o.optString("dl"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("t", it.t).put("s", it.src).put("d", it.dst).put("sl", it.sl).put("dl", it.dl))
        }
        file(ctx).writeText(a.toString())
    }

    @Synchronized
    fun add(ctx: Context, src: String, dst: String, sl: String, dl: String) {
        write(ctx, (listOf(Entry(System.currentTimeMillis(), src, dst, sl, dl)) + all(ctx)).take(max))
    }

    /**
     * Thêm kiểu "thông minh": nếu mục mới gần như là bản gõ tiếp của mục ngay trước (cùng ngôn ngữ đích, trong 2 phút)
     * thì thay thế thay vì chồng thêm, tránh lịch sử đầy các bản gõ dở.
     */
    @Synchronized
    fun addSmart(ctx: Context, src: String, dst: String, sl: String, dl: String) {
        val list = all(ctx)
        val first = list.firstOrNull()
        val now = System.currentTimeMillis()
        val replace = first != null && first.dl == dl && now - first.t < 120_000 &&
            (src.startsWith(first.src) || first.src.startsWith(src))
        val base = if (replace) list.drop(1) else list
        write(ctx, (listOf(Entry(now, src, dst, sl, dl)) + base).take(max))
    }

    @Synchronized
    fun contains(ctx: Context, src: String, dl: String) = all(ctx).any { it.src == src && it.dl == dl }

    /** Bật/tắt lưu. Trả về true nếu sau thao tác mục đang được lưu. */
    @Synchronized
    fun toggle(ctx: Context, src: String, dst: String, sl: String, dl: String): Boolean {
        val list = all(ctx)
        return if (list.any { it.src == src && it.dl == dl }) {
            write(ctx, list.filterNot { it.src == src && it.dl == dl })
            false
        } else {
            write(ctx, (listOf(Entry(System.currentTimeMillis(), src, dst, sl, dl)) + list).take(max))
            true
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    @Synchronized
    fun remove(ctx: Context, t: Long) {
        write(ctx, all(ctx).filter { it.t != t })
    }
}

object HistoryStore : EntryStore("history.json", 500)

/** Sổ cụm từ: các bản dịch bạn gắn sao. */
object SavedStore : EntryStore("saved.json", 2000)
