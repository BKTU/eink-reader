package tw.bktu.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 中文排版引擎 + 分頁顯示。
 * - 首行縮排、兩端對齊、段落間距
 * - 標點避頭（，。」等不在行首）避尾（「（等不在行尾）
 * - 英文單字不拆開
 * 不捲動、不做動畫，每次畫一整頁。
 */
class ReaderView(context: Context) : View(context) {

    var text: String = ""
        set(v) { field = v; cache.clear() }
    var start = 0
        private set
    var end = 0
        private set

    var message: String? = "載入中…"
    var statusLeft = ""
    var statusRight = ""

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var spacing = 1.5f
    private var marginPx = 40
    private var indent = true
    private var paraGapEm = 0.5f
    private var night = false
    private var flashColor: Int? = null

    var onLayoutReady: (() -> Unit)? = null

    fun configure(fontPx: Float, spacingMul: Float, marginPx: Int, bold: Boolean,
                  indent: Boolean, paraGapEm: Float, night: Boolean, typeface: Typeface) {
        paint.typeface = typeface
        paint.textSize = fontPx
        paint.isFakeBoldText = bold
        statusPaint.textSize = fontPx * 0.62f
        statusPaint.isFakeBoldText = bold
        spacing = spacingMul
        this.marginPx = marginPx
        this.indent = indent
        this.paraGapEm = paraGapEm
        this.night = night
        val fg = if (night) Color.WHITE else Color.BLACK
        paint.color = fg
        statusPaint.color = fg
        cache.clear()
    }

    val bgColor: Int get() = if (night) Color.BLACK else Color.WHITE

    private val statusH: Int get() = (statusPaint.textSize * 2.2f).toInt()
    private val contentW: Int get() = width - marginPx * 2
    private val contentH: Int get() = height - marginPx - statusH
    private val lineH: Float get() = paint.textSize * spacing
    private val paraGap: Float get() = paint.textSize * paraGapEm

    val ready: Boolean get() = width > 0 && height > 0 && contentW > 50 && contentH > 50

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache.clear()
        if (ready) onLayoutReady?.invoke()
    }

    // ============================ 排版 ============================

    /** 一行：start/end 是在全文中的位置；runs 是每個字（或英文單字）的起訖與 x 座標 */
    private class Line(
        val start: Int, val end: Int,
        val runS: IntArray, val runE: IntArray, val runX: FloatArray,
        val firstOfPara: Boolean,
    )

    private val cache = object : LinkedHashMap<Int, List<Line>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Line>>?) = size > 24
    }

    private fun paraStart(pos: Int): Int {
        if (pos <= 0) return 0
        val nl = text.lastIndexOf('\n', pos - 1)
        return if (nl < 0) 0 else nl + 1
    }

    private fun paraEnd(ps: Int): Int {
        val nl = text.indexOf('\n', ps)
        return if (nl < 0) text.length else nl
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '　' || c == ' '
    private fun isWordChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '\'' || c == '-' || c == '_'

    private fun layoutPara(ps: Int): List<Line> {
        cache[ps]?.let { return it }
        val pe = paraEnd(ps)
        var cs = ps
        while (cs < pe && isSpace(text[cs])) cs++
        if (cs >= pe) {
            cache[ps] = emptyList()
            return emptyList()
        }

        val n = pe - cs
        val widths = FloatArray(n)
        paint.getTextWidths(text, cs, pe, widths)

        // 切成 token：英文單字為一個，其他每個字一個
        val tokS = IntArray(n)
        val tokE = IntArray(n)
        val tokW = FloatArray(n)
        var tc = 0
        var i = cs
        while (i < pe) {
            val c = text[i]
            var j = i + 1
            if (isWordChar(c)) {
                while (j < pe && isWordChar(text[j]) && j - i < 24) j++
            } else if (Character.isHighSurrogate(c) && j < pe) {
                j++
            }
            var w = 0f
            for (k in i until j) w += widths[k - cs]
            tokS[tc] = i; tokE[tc] = j; tokW[tc] = w
            tc++
            i = j
        }

        val maxW = contentW.toFloat()
        val indentW = if (indent) paint.textSize * 2f else 0f
        val lines = ArrayList<Line>()
        var t = 0
        var first = true
        while (t < tc) {
            // 行首不放空白
            if (!first) while (t < tc && isSpace(text[tokS[t]])) t++
            if (t >= tc) break
            val lineMax = if (first) maxW - indentW else maxW
            var w = 0f
            var e = t
            while (e < tc && (w + tokW[e] <= lineMax || e == t)) { w += tokW[e]; e++ }

            if (e < tc) {
                // 避頭：下一行開頭是收尾標點 → 擠進這一行（最多 2 個）
                var pulled = 0
                while (e < tc && pulled < 2 && NO_START.indexOf(text[tokS[e]]) >= 0) {
                    w += tokW[e]; e++; pulled++
                }
                // 避尾：行尾是開頭標點 → 移到下一行
                if (pulled == 0) {
                    while (e - t > 1 && NO_END.indexOf(text[tokS[e - 1]]) >= 0) { e--; w -= tokW[e] }
                }
            }
            // 行尾空白不算寬度
            var visEnd = e
            while (visEnd - t > 1 && isSpace(text[tokS[visEnd - 1]])) { visEnd--; w -= tokW[visEnd] }

            val last = e >= tc
            val count = visEnd - t
            val gaps = count - 1
            var extra = if (gaps > 0 && (!last || w > lineMax)) (lineMax - w) / gaps else 0f
            if (extra > paint.textSize) extra = 0f   // 太稀疏（例如很長的英文單字）就不對齊

            val rs = IntArray(count)
            val re = IntArray(count)
            val rx = FloatArray(count)
            var x = if (first) indentW else 0f
            for (k in 0 until count) {
                rs[k] = tokS[t + k]; re[k] = tokE[t + k]; rx[k] = x
                x += tokW[t + k] + extra
            }
            val lineStart = if (first) ps else tokS[t]
            val lineEnd = if (last) minOf(text.length, pe + 1) else tokS[e]
            lines.add(Line(lineStart, lineEnd, rs, re, rx, first))
            first = false
            t = e
        }
        cache[ps] = lines
        return lines
    }

    // ============================ 分頁 ============================

    private class Placed(val line: Line, val y: Float)
    private var page: List<Placed> = emptyList()

    /** 從 from 開始排出一頁（from 會對齊到所在行的行首） */
    private fun buildPage(from: Int): List<Placed> {
        val out = ArrayList<Placed>()
        var ps = paraStart(from)
        var y = 0f
        var firstLine = true
        val len = text.length
        while (ps < len) {
            val lines = layoutPara(ps)
            var li = 0
            if (firstLine) {
                // 找出包含 from 的那一行
                while (li < lines.size - 1 && lines[li].end <= from) li++
            }
            while (li < lines.size) {
                val ln = lines[li]
                val gap = if (!firstLine && ln.firstOfPara) paraGap else 0f
                if (y + gap + lineH > contentH && !firstLine) return out
                y += gap
                out.add(Placed(ln, y))
                y += lineH
                firstLine = false
                li++
            }
            val pe = paraEnd(ps)
            ps = pe + 1
        }
        return out
    }

    fun showAt(from: Int) {
        message = null
        if (!ready || text.isEmpty()) return
        var f = from.coerceIn(0, maxOf(0, text.length - 1))
        var pg = buildPage(f)
        // 落在空段落裡 → 往後找到有字的地方
        while (pg.isEmpty() && f < text.length - 1) {
            f = paraEnd(paraStart(f)) + 1
            if (f >= text.length) break
            pg = buildPage(f)
        }
        page = pg
        start = pg.firstOrNull()?.line?.start ?: f
        end = pg.lastOrNull()?.line?.end ?: text.length
        invalidate()
    }

    fun nextStart(): Int? = if (end >= text.length) null else end

    /** 往回排：從目前頁首往前收集行，直到塞滿一頁 */
    fun prevStart(): Int? {
        if (start <= 0) return null
        var pos = start
        var h = 0f
        var top = -1
        var belowIsFirstOfPara = false
        var any = false
        while (pos > 0) {
            val ps = paraStart(pos - 1)
            val lines = layoutPara(ps).filter { it.end <= pos }
            for (k in lines.indices.reversed()) {
                val ln = lines[k]
                val gap = if (any && belowIsFirstOfPara) paraGap else 0f
                if (h + gap + lineH > contentH && any) return top
                h += gap + lineH
                top = ln.start
                belowIsFirstOfPara = ln.firstOfPara
                any = true
            }
            pos = ps
        }
        return if (any) top else 0
    }

    // ============================ 翻頁效果 ============================

    private var bmpOld: Bitmap? = null
    private var bmpNew: Bitmap? = null
    private var animT = -1f
    private var animFwd = true
    private var effect = 0
    private var animator: ValueAnimator? = null
    private val edgePaint = Paint().apply { color = Color.GRAY; strokeWidth = 3f }
    private val fadePaint = Paint()

    private fun snapshot(reuse: Bitmap?): Bitmap {
        val b = if (reuse != null && reuse.width == width && reuse.height == height) reuse
        else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawPage(Canvas(b))
        return b
    }

    /**
     * 換頁並播放效果。effect：0 無、1 平移、2 覆蓋、3 淡入、4 上下
     * onShown 在新頁排好後、拍下畫面前呼叫（用來更新狀態列）
     */
    fun showAnimated(from: Int, forward: Boolean, effect: Int, onShown: () -> Unit, onEnd: () -> Unit) {
        animator?.end()
        if (effect == 0 || !ready || message != null || width == 0) {
            showAt(from); onShown(); onEnd(); return
        }
        bmpOld = snapshot(bmpOld)
        showAt(from)
        onShown()
        bmpNew = snapshot(bmpNew)
        animFwd = forward
        this.effect = effect
        animT = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { animT = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animT = -1f
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
    }

    private fun drawAnim(canvas: Canvas, old: Bitmap, new: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = animT
        when (effect) {
            1 -> if (animFwd) {
                canvas.drawBitmap(old, -t * w, 0f, null); canvas.drawBitmap(new, (1 - t) * w, 0f, null)
            } else {
                canvas.drawBitmap(old, t * w, 0f, null); canvas.drawBitmap(new, -(1 - t) * w, 0f, null)
            }
            2 -> if (animFwd) {
                val x = (1 - t) * w
                canvas.drawBitmap(old, 0f, 0f, null); canvas.drawBitmap(new, x, 0f, null)
                canvas.drawLine(x, 0f, x, h, edgePaint)
            } else {
                val x = t * w
                canvas.drawBitmap(new, 0f, 0f, null); canvas.drawBitmap(old, x, 0f, null)
                canvas.drawLine(x, 0f, x, h, edgePaint)
            }
            3 -> {
                canvas.drawBitmap(old, 0f, 0f, null)
                fadePaint.alpha = (t * 255).toInt()
                canvas.drawBitmap(new, 0f, 0f, fadePaint)
            }
            4 -> if (animFwd) {
                canvas.drawBitmap(old, 0f, -t * h, null); canvas.drawBitmap(new, 0f, (1 - t) * h, null)
            } else {
                canvas.drawBitmap(old, 0f, t * h, null); canvas.drawBitmap(new, 0f, -(1 - t) * h, null)
            }
            else -> canvas.drawBitmap(new, 0f, 0f, null)
        }
    }

    // ============================ 繪製 ============================

    /** 全畫面黑 → 白 → 內容，清除水墨屏殘影 */
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
        val o = bmpOld
        val n = bmpNew
        if (animT >= 0f && o != null && n != null) {
            drawAnim(canvas, o, n)
            return
        }
        drawPage(canvas)
    }

    private fun drawPage(canvas: Canvas) {
        canvas.drawColor(bgColor)

        val msg = message
        if (msg != null) {
            val w = paint.measureText(msg)
            canvas.drawText(msg, (width - w) / 2f, height / 2f, paint)
            return
        }

        val fm = paint.fontMetrics
        val baseOff = (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val ox = marginPx.toFloat()
        val oy = marginPx.toFloat()
        for (p in page) {
            val ln = p.line
            val by = oy + p.y + baseOff
            for (k in ln.runS.indices) {
                canvas.drawText(text, ln.runS[k], ln.runE[k], ox + ln.runX[k], by, paint)
            }
        }

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

    companion object {
        private const val NO_START = "，。、；：？！）」』》〉】〕｝’”…—～·,.;:?!)]}%"
        private const val NO_END = "（「『《〈【〔｛‘“([{"
    }
}
