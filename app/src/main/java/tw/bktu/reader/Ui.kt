package tw.bktu.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 水墨屏用的介面元件：純黑白、粗框、沒有漣漪動畫 */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun border(ctx: Context, strokeDp: Int = 2, radiusDp: Int = 6, fill: Int = Color.WHITE) =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(ctx, strokeDp), Color.BLACK)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun label(ctx: Context, text: String, sp: Float = 18f, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.BLACK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun button(ctx: Context, text: String, sp: Float = 17f, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = sp
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        background = border(ctx)
        setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
        // 窄螢幕：一律單行，放不下就自動縮小字
        maxLines = 1
        setSingleLine(true)
        if (Build.VERSION.SDK_INT >= 26) {
            setAutoSizeTextTypeUniformWithConfiguration(10, sp.toInt().coerceAtLeast(11), 1, TypedValue.COMPLEX_UNIT_SP)
        } else {
            ellipsize = TextUtils.TruncateAt.END
        }
        setOnClickListener { onClick() }
    }

    /** 不佔高度的空白（用來補齊一排） */
    fun spacer(ctx: Context) = View(ctx).apply { minimumHeight = 0 }

    /** 選取中的按鈕：反白（黑底白字） */
    fun select(tv: TextView, selected: Boolean) {
        tv.background = border(tv.context, 2, 6, if (selected) Color.BLACK else Color.WHITE)
        tv.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 加到橫排裡，平均分配寬度 */
    fun addWeighted(parent: LinearLayout, v: View, weight: Float = 1f, marginDp: Int = 4) {
        val m = dp(parent.context, marginDp)
        // 純 View 用 WRAP_CONTENT 會撐滿高度，固定為 0
        val h = if (v.javaClass == View::class.java) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        parent.addView(v, LinearLayout.LayoutParams(0, h, weight).apply {
            setMargins(m, m, m, m)
        })
    }

    fun matchWrap(ctx: Context, topDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, topDp) }
}
