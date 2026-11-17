package vn.dichmanhinh.app.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.OfflinePacks

/**
 * Hộp chọn ngôn ngữ giống Google Dịch: ô tìm kiếm, "Phát hiện ngôn ngữ" (cho ngôn ngữ nguồn),
 * mục "Gần đây", rồi toàn bộ ngôn ngữ. Ngôn ngữ đã tải gói ngoại tuyến có dấu ✓.
 */
object LanguagePicker {
    private class Row(val code: String?, val title: String, val header: Boolean = false)

    fun show(ctx: Context, owner: LifecycleOwner, withAuto: Boolean, current: String, onPick: (String) -> Unit) {
        val prefs = Prefs(ctx)
        val all = Languages.all(ctx)
        var offline: Set<String> = emptySet()
        var query = ""

        fun build(): List<Row> {
            val q = query.trim().lowercase()
            fun ok(c: String) = q.isEmpty() || Languages.name(ctx, c).lowercase().contains(q) || c.lowercase().contains(q)
            val rows = ArrayList<Row>()
            if (withAuto && (q.isEmpty() || Languages.name(ctx, Languages.AUTO).lowercase().contains(q))) {
                rows.add(Row(Languages.AUTO, Languages.name(ctx, Languages.AUTO)))
            }
            if (q.isEmpty()) {
                val rec = prefs.recentLangs.filter { it in all }
                if (rec.isNotEmpty()) {
                    rows.add(Row(null, ctx.getString(R.string.lang_recent), true))
                    rec.forEach { rows.add(Row(it, label(ctx, it, offline))) }
                }
                rows.add(Row(null, ctx.getString(R.string.lang_all), true))
            }
            all.filter { ok(it) }.forEach { rows.add(Row(it, label(ctx, it, offline))) }
            return rows
        }

        val search = EditText(ctx).apply {
            hint = ctx.getString(R.string.lang_search)
            setSingleLine()
        }
        val list = ListView(ctx)
        var rows = build()
        val adapter = object : ArrayAdapter<Row>(ctx, android.R.layout.simple_list_item_1, rows.toMutableList()) {
            override fun isEnabled(position: Int) = getItem(position)?.header == false
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val r = getItem(position)!!
                v.text = (if (r.code == current && !r.header) "● " else "") + r.title
                v.setTypeface(null, if (r.header) Typeface.BOLD else Typeface.NORMAL)
                return v
            }
        }
        list.adapter = adapter

        fun refresh() {
            rows = build()
            adapter.clear()
            adapter.addAll(rows)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, p / 2, p, 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 420)))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (withAuto) R.string.lang_pick_src else R.string.lang_pick_dst)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _, pos, _ ->
            val code = adapter.getItem(pos)?.code ?: return@setOnItemClickListener
            prefs.pushRecent(code)
            dialog.dismiss()
            onPick(code)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        dialog.show()

        // đánh dấu ✓ cho gói ngoại tuyến sau khi đọc xong trạng thái
        val scope = (owner as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope
        scope?.launch {
            offline = OfflinePacks.downloaded() + "en"
            if (dialog.isShowing) refresh()
        }
    }

    private fun label(ctx: Context, code: String, offline: Set<String>): String {
        val n = Languages.name(ctx, code)
        return if (code in offline) "$n  ✓" else n
    }
}
