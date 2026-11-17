package vn.dichmanhinh.app.ui

import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Entry
import vn.dichmanhinh.app.data.Languages
import vn.dichmanhinh.app.data.SavedStore

/** Gắn một mục lịch sử / sổ cụm từ vào layout item_entry (có nút gắn sao). */
object EntryUi {
    fun bind(ctx: Context, v: View, e: Entry, onClick: () -> Unit, onStar: () -> Unit = {}) {
        val sl = if (e.sl.isEmpty()) "" else Languages.name(ctx, e.sl)
        v.findViewById<TextView>(R.id.tvLangs).text = "$sl → ${Languages.name(ctx, e.dl)}"
        v.findViewById<TextView>(R.id.tvSrc).text = e.src
        v.findViewById<TextView>(R.id.tvDst).text = e.dst
        val star = v.findViewById<ImageButton>(R.id.btnStar)
        fun paint() = star.setImageResource(
            if (SavedStore.contains(ctx, e.src, e.dl)) R.drawable.ic_star else R.drawable.ic_star_border
        )
        paint()
        star.setOnClickListener {
            SavedStore.toggle(ctx, e.src, e.dst, e.sl, e.dl)
            paint()
            onStar()
        }
        v.setOnClickListener { onClick() }
    }
}
