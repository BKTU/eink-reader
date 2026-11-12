package tw.bktu.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * 分頁顯示文字。不捲動、不做動畫，每次只畫一整頁。
 * 分頁方式：從目前位置取一段文字排版，算出能塞進一頁的最後一行。
 */
class ReaderView(context: Context) : View(context) {

    var text: String = ""
    var start = 0
        private set
    var end = 0
        private set

    var message: String? = "載入中…"
    var statusLeft = ""
    var statusRight = ""

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var spacing = 1.5f
    private var marginPx = 40
    private var pageLayout: StaticLayout? = null
    private var pageLines = 0
    private var flashColor: Int? = null

    var onLayoutReady: (() -> Unit)? = null

    fun configure(fontPx: Float, spacingMul: Float, marginPx: Int, bold: Boolean) {
        paint.textSize = fontPx
        paint.isFakeBoldText = bold
        spacing = spacingMul
        this.marginPx = marginPx
        statusPaint.textSize = fontPx * 0.62f
        statusPaint.isFakeBoldText = bold
    }

    private val statusH: Int get() = (statusPaint.textSize * 2.2f).toInt()
    private val contentW: Int get() = width - marginPx * 2
    private val contentH: Int get() = height - marginPx - statusH

    val ready: Boolean get() = width > 0 && height > 0 && contentW > 50 && contentH > 50

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready) onLayoutReady?.invoke()
    }

    private fun makeLayout(cs: CharSequence): StaticLayout {
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(cs, 0, cs.length, paint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(cs, paint, contentW, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)
        }
    }

    /** 估計一頁最多能放多少字（取寬鬆值，寧可多不可少） */
    private fun maxChars(): Int {
        val perLine = (contentW / (paint.textSize * 0.45f)).toInt() + 2
        val lines = (contentH / (paint.textSize * spacing * 0.9f)).toInt() + 2
        return perLine * lines
    }

    private class Page(val end: Int, val layout: StaticLayout, val lines: Int)

    private fun computePage(from: Int): Page {
        val len = text.length
        val chunkEnd = minOf(len, from + maxChars())
        val layout = makeLayout(text.subSequence(from, chunkEnd))
        var line = layout.getLineForVertical(contentH)
        if (layout.getLineBottom(line) > contentH) line--
        if (line < 0) line = 0
        var e = from + layout.getLineEnd(line)
        if (e <= from) e = minOf(len, from + 1)
        return Page(e, layout, line + 1)
    }

    /** 顯示從 from 開始的一頁 */
    fun showAt(from: Int) {
        message = null
        if (!ready || text.isEmpty()) return
        start = from.coerceIn(0, maxOf(0, text.length - 1))
        val pg = computePage(start)
        end = pg.end
        pageLayout = pg.layout
        pageLines = pg.lines
        invalidate()
    }

    fun nextStart(): Int? = if (end >= text.length) null else end

    /** 找上一頁的起點：退回到前面的段落開頭，往後排版到目前位置為止 */
    fun prevStart(): Int? {
        if (start <= 0) return null
        val back = maxChars() * 2
        var from = maxOf(0, start - back)
        val nl = text.lastIndexOf('\n', from - 1)
        val para = if (nl < 0) 0 else nl + 1
        if (start - para <= maxChars() * 6) from = para
        var s = from
        var last = from
        var guard = 0
        while (s < start && guard < 200) {
            last = s
            val e = computePage(s).end
            if (e <= s) break
            s = e
            guard++
        }
        return last
    }

    /** 全畫面黑 → 白 → 內容，用來清除水墨屏殘影 */
    fun flash() {
        flashColor = Color.BLACK
        invalidate()
        postDelayed({
            flashColor = Color.WHITE
            invalidate()
            postDelayed({
                flashColor = null
                invalidate()
            }, 110)
        }, 160)
    }

    override fun onDraw(canvas: Canvas) {
        val fc = flashColor
        if (fc != null) {
            canvas.drawColor(fc)
            return
        }
        canvas.drawColor(Color.WHITE)

        val msg = message
        if (msg != null) {
            val w = paint.measureText(msg)
            canvas.drawText(msg, (width - w) / 2f, height / 2f, paint)
            return
        }

        val layout = pageLayout ?: return
        canvas.save()
        canvas.translate(marginPx.toFloat(), marginPx.toFloat())
        canvas.clipRect(0, 0, contentW, layout.getLineBottom(pageLines - 1))
        layout.draw(canvas)
        canvas.restore()

        // 狀態列：左邊章節，右邊進度／時間／電量
        val y = height - statusH * 0.38f
        val right = statusRight
        val rw = statusPaint.measureText(right)
        canvas.drawText(right, width - marginPx - rw, y, statusPaint)
        val leftMax = contentW - rw - statusPaint.textSize * 2
        if (leftMax > 0) {
            val left = TextUtils.ellipsize(statusLeft, statusPaint, leftMax, TextUtils.TruncateAt.END)
            canvas.drawText(left, 0, left.length, marginPx.toFloat(), y, statusPaint)
        }
    }
}
