package vn.dichmanhinh.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.engine.OcrPacks
import vn.dichmanhinh.app.engine.OfflinePacks

/**
 * Quản lý gói ngoại tuyến. Mặc định chỉ Trung + Anh + Việt (dịch) và OCR Latin + Hán (đã kèm trong app).
 * Mọi ngôn ngữ / OCR khác tải thêm ở đây khi cần để app nhẹ.
 */
class ModelsActivity : AppCompatActivity() {
    private class Row(val kind: Int, val code: String, val title: String, val sub: String)

    private lateinit var list: ListView
    private lateinit var prefs: Prefs
    private var rows = listOf<Row>()
    private val busy = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.models)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        list = ListView(this)
        setContentView(list)
        list.setOnItemClickListener { _, _, pos, _ -> click(rows[pos]) }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val dl = OfflinePacks.downloaded()
            val ocr = OcrPacks.EXTRA.associateWith { OcrPacks.isAvailable(this@ModelsActivity, it) }
            val out = ArrayList<Row>()

            fun trRow(code: String): Row {
                val name = Languages.name(this@ModelsActivity, code)
                val sub = when {
                    "t$code" in busy -> getString(R.string.state_downloading)
                    code in dl || code == "en" -> getString(R.string.state_ready)
                    else -> getString(R.string.state_tap_download)
                }
                return Row(1, code, name, sub)
            }

            out.add(Row(0, "", getString(R.string.models_sec_default), ""))
            Languages.OFFLINE_DEFAULT.sortedBy { if (it == "zh") 0 else if (it == "vi") 1 else 2 }.forEach { out.add(trRow(it)) }

            out.add(Row(0, "", getString(R.string.models_sec_ocr), ""))
            out.add(Row(3, "", getString(R.string.ocr_pack_title, "Latin (Anh, Việt, …)"), getString(R.string.state_bundled)))
            out.add(Row(3, "", getString(R.string.ocr_pack_title, Languages.name(this@ModelsActivity, "zh")), getString(R.string.state_bundled)))
            OcrPacks.EXTRA.forEach { s ->
                val name = if (s == "hi") "Devanagari (Hindi, Marathi, Nepal)" else Languages.name(this@ModelsActivity, s)
                val sub = when {
                    "o$s" in busy -> getString(R.string.state_downloading)
                    ocr[s] == true -> getString(R.string.state_ready)
                    else -> getString(R.string.state_tap_download_gms)
                }
                out.add(Row(2, s, getString(R.string.ocr_pack_title, name), sub))
            }

            out.add(Row(0, "", getString(R.string.models_sec_more), ""))
            Languages.offlineCapable(this@ModelsActivity).filter { it !in Languages.OFFLINE_DEFAULT }.forEach { out.add(trRow(it)) }

            rows = out
            list.adapter = object : ArrayAdapter<Row>(
                this@ModelsActivity, android.R.layout.simple_list_item_2, android.R.id.text1, rows
            ) {
                override fun isEnabled(position: Int) = rows[position].kind == 1 || rows[position].kind == 2
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    val r = rows[position]
                    val t1 = v.findViewById<TextView>(android.R.id.text1)
                    val t2 = v.findViewById<TextView>(android.R.id.text2)
                    t1.text = r.title
                    t1.setTypeface(null, if (r.kind == 0) Typeface.BOLD else Typeface.NORMAL)
                    t2.text = r.sub
                    t2.visibility = if (r.kind == 0) View.GONE else View.VISIBLE
                    return v
                }
            }
        }
    }

    private fun click(r: Row) {
        when (r.kind) {
            1 -> translatePack(r.code)
            2 -> ocrPack(r.code)
        }
    }

    private fun translatePack(code: String) {
        if ("t$code" in busy) return
        lifecycleScope.launch {
            if (code == "en" || OfflinePacks.isDownloaded(code)) {
                if (code == "en") return@launch
                AlertDialog.Builder(this@ModelsActivity)
                    .setTitle(Languages.name(this@ModelsActivity, code))
                    .setMessage(
                        if (code in Languages.OFFLINE_DEFAULT) R.string.models_delete_default else R.string.models_delete
                    )
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            try { OfflinePacks.delete(code) } catch (e: Exception) { error(e) }
                            refresh()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }
            busy.add("t$code")
            Toast.makeText(this@ModelsActivity, R.string.downloading, Toast.LENGTH_SHORT).show()
            refresh()
            try {
                OfflinePacks.download(code, prefs.wifiOnlyDl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e)
            }
            busy.remove("t$code")
            refresh()
        }
    }

    private fun ocrPack(script: String) {
        if ("o$script" in busy) return
        lifecycleScope.launch {
            if (OcrPacks.isAvailable(this@ModelsActivity, script)) return@launch
            busy.add("o$script")
            Toast.makeText(this@ModelsActivity, R.string.downloading, Toast.LENGTH_SHORT).show()
            refresh()
            try {
                OcrPacks.install(this@ModelsActivity, script)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(e)
            }
            busy.remove("o$script")
            refresh()
        }
    }

    private fun error(e: Exception) {
        Toast.makeText(this, getString(R.string.err_generic, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.models, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_defaults) {
            Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                OfflinePacks.ensureDefaults(prefs.wifiOnlyDl)
                Toast.makeText(this@ModelsActivity, R.string.dl_done, Toast.LENGTH_SHORT).show()
                refresh()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
