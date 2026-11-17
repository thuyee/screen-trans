package vn.dichmanhinh.app.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.Prefs

/** Gắn hàng chọn ngôn ngữ nguồn ⇄ đích dùng chung cho các màn hình. */
object LangUi {
    fun bind(
        ctx: Context, spSrc: Spinner, btnSwap: View, spDst: Spinner,
        prefs: Prefs, onChange: () -> Unit = {}
    ) {
        val langs = Languages.all(ctx)
        val srcCodes = listOf(Languages.AUTO) + langs

        fun adapter(codes: List<String>) = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, codes.map { Languages.name(ctx, it) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spSrc.adapter = adapter(srcCodes)
        spDst.adapter = adapter(langs)

        fun load() {
            spSrc.setSelection(srcCodes.indexOf(prefs.src).coerceAtLeast(0))
            spDst.setSelection(langs.indexOf(prefs.dst).coerceAtLeast(0))
        }
        load()

        spSrc.onItemSelectedListener = listener { prefs.src = srcCodes[it]; onChange() }
        spDst.onItemSelectedListener = listener { prefs.dst = langs[it]; onChange() }
        btnSwap.setOnClickListener {
            val s = prefs.src
            val d = prefs.dst
            if (s == Languages.AUTO) {
                prefs.src = d
                prefs.dst = if (d == "vi") "en" else "vi"
            } else {
                prefs.src = d
                prefs.dst = s
            }
            load()
            onChange()
        }
    }

    private fun listener(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = f(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}
