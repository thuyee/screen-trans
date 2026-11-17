package vn.dichmanhinh.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Entry
import vn.dichmanhinh.app.data.EntryStore
import vn.dichmanhinh.app.data.HistoryStore
import vn.dichmanhinh.app.data.SavedStore

/** Lịch sử dịch, hoặc Sổ cụm từ (extra "saved" = true). Có tìm kiếm; chạm một mục để mở lại ở trang chủ. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var store: EntryStore
    private var saved = false
    private var query = ""
    private var data = listOf<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = intent.getBooleanExtra("saved", false)
        store = if (saved) SavedStore else HistoryStore
        title = getString(if (saved) R.string.saved_phrases else R.string.history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val search = EditText(this).apply {
            hint = getString(R.string.search)
            setSingleLine()
            val p = UiKit.dp(this@HistoryActivity, 16)
            setPadding(p, p / 2, p, p / 2)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); reload() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = ListView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val q = query.trim().lowercase()
        data = store.all(this).filter { q.isEmpty() || it.src.lowercase().contains(q) || it.dst.lowercase().contains(q) }
        list.adapter = object : ArrayAdapter<Entry>(this, R.layout.item_entry, R.id.tvSrc, data) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
                val e = data[position]
                EntryUi.bind(this@HistoryActivity, v, e, onClick = { open(e) }, onStar = { if (saved) reload() })
                v.setOnLongClickListener { confirmDelete(e); true }
                return v
            }
        }
    }

    private fun open(e: Entry) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("text", e.src).putExtra("sl", e.sl).putExtra("dl", e.dl)
        )
        finish()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setMessage(e.dst)
            .setPositiveButton(R.string.delete) { _, _ -> store.remove(this, e.t); reload() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history, menu)
        menu.findItem(R.id.action_clear)?.setTitle(if (saved) R.string.saved_clear else R.string.history_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear) {
            AlertDialog.Builder(this)
                .setMessage(if (saved) R.string.saved_clear else R.string.history_clear)
                .setPositiveButton(R.string.delete) { _, _ -> store.clear(this); reload() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
