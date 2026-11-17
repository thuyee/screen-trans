package vn.dichmanhinh.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Vài view dựng bằng code cho overlay (service không có theme Material). */
object UiKit {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun round(color: Int, radius: Float, topOnly: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        else cornerRadius = radius
    }

    fun chip(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        background = round(0xE6263238.toInt(), dp(ctx, 20).toFloat())
        setOnClickListener { onClick() }
    }

    fun menuItem(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        setOnClickListener { onClick() }
    }

    fun label(ctx: Context, text: String, sp: Float, color: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
    }

    fun gone(v: View?) {
        v?.visibility = View.GONE
    }
}
