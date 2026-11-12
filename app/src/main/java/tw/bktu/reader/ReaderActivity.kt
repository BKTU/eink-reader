package tw.bktu.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReaderActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var view: ReaderView
    private lateinit var menu: LinearLayout
    private var toc: LinearLayout? = null

    private var key = ""
    private var title = ""
    private var chapters: List<Chapter> = emptyList()
    private var loaded = false
    private var pageCount = 0
    private val history = ArrayList<Int>()      // 往後翻時記錄起點，往前翻可精準回到原頁
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)

    // 設定面板上的數值文字
    private lateinit var fontTv: TextView
    private lateinit var spacingTv: TextView
    private lateinit var marginTv: TextView
    private lateinit var boldTv: TextView
    private lateinit var refreshTv: TextView
    private lateinit var infoTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        hideSystemBars()

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        view = ReaderView(this)
        applySettings()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        menu = buildMenu()
        menu.visibility = View.GONE
        root.addView(menu, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        view.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) onTap(ev.x, ev.y, v.width, v.height)
            true
        }
        view.onLayoutReady = {
            if (loaded) {
                view.showAt(view.start)
                updateStatus()
            }
        }

        // 從哪裡開啟：書架／資料夾瀏覽（path）或其他 App（URI）
        val path = intent.getStringExtra("path")
        val uri = intent.data
        when {
            path != null -> { key = path; title = File(path).name }
            uri != null && uri.scheme == "file" && uri.path != null -> { key = uri.path!!; title = File(key).name }
            uri != null -> { key = uri.toString(); title = displayName(uri) }
            else -> { finish(); return }
        }
        load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
    }

    // ============================ 載入 ============================

    private fun load() {
        view.message = "載入中…"
        view.invalidate()
        Thread {
            try {
                val bytes = if (key.startsWith("/")) File(key).readBytes()
                else contentResolver.openInputStream(Uri.parse(key))!!.use { it.readBytes() }
                val text = TextLoader.normalize(TextLoader.decode(bytes))
                val ch = TextLoader.chapters(text)
                runOnUiThread {
                    view.text = text
                    chapters = ch
                    loaded = true
                    val off = store.offset(key).coerceIn(0, maxOf(0, text.length - 1))
                    if (view.ready) {
                        view.showAt(off)
                        updateStatus()
                        saveProgress()
                    } else {
                        view.post { view.showAt(off); updateStatus(); saveProgress() }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    view.message = "無法開啟檔案（點一下返回）"
                    view.invalidate()
                    loaded = false
                    Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ============================ 翻頁 ============================

    private fun onTap(x: Float, y: Float, w: Int, h: Int) {
        if (!loaded) { if (view.message != "載入中…") finish(); return }
        if (toc != null) return
        if (menu.visibility == View.VISIBLE) { hideMenu(); return }
        val inCenterX = x > w / 3f && x < w * 2 / 3f
        when {
            inCenterX && y > h / 4f && y < h * 3 / 4f -> showMenu()
            x < w / 3f -> prevPage()
            inCenterX && y <= h / 4f -> prevPage()
            else -> nextPage()
        }
    }

    private fun nextPage() {
        val n = view.nextStart() ?: run { Toast.makeText(this, "已經是最後一頁", Toast.LENGTH_SHORT).show(); return }
        history.add(view.start)
        if (history.size > 300) history.removeAt(0)
        turnTo(n)
    }

    private fun prevPage() {
        val p = if (history.isNotEmpty()) history.removeAt(history.size - 1) else view.prevStart()
        if (p == null) { Toast.makeText(this, "已經是第一頁", Toast.LENGTH_SHORT).show(); return }
        turnTo(p)
    }

    private fun jumpTo(offset: Int) {
        history.clear()
        turnTo(offset, forceFlash = true)
    }

    private fun turnTo(offset: Int, forceFlash: Boolean = false) {
        view.showAt(offset)
        updateStatus()
        saveProgress()
        pageCount++
        val every = store.refreshEvery
        if (forceFlash || (every > 0 && pageCount % every == 0)) view.flash()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (loaded && toc == null && menu.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> { nextPage(); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { prevPage(); return true }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (toc != null) { closeToc(); return true }
            if (menu.visibility == View.VISIBLE) { hideMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 吃掉音量鍵放開事件，避免跳出音量條
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    // ============================ 狀態與進度 ============================

    private fun percent(): Float {
        val len = view.text.length
        return if (len <= 1) 100f else (view.end.toFloat() / len * 100f).coerceIn(0f, 100f)
    }

    private fun currentChapterIndex(): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = -1
        val pos = view.start
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chapters[mid].offset <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun battery(): Int {
        return try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level < 0) -1 else level * 100 / scale
        } catch (e: Exception) { -1 }
    }

    private fun updateStatus() {
        val ci = currentChapterIndex()
        view.statusLeft = if (ci >= 0) chapters[ci].title else title
        val b = battery()
        val bat = if (b >= 0) "  電$b%" else ""
        view.statusRight = String.format(Locale.US, "%.1f%%", percent()) + "  " + timeFmt.format(Date()) + bat
        view.invalidate()
    }

    private fun saveProgress() {
        if (!loaded) return
        store.setOffset(key, view.start)
        store.touch(Book(key, title, percent(), System.currentTimeMillis()))
    }

    // ============================ 選單 ============================

    private fun applySettings() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, store.fontSp.toFloat(), resources.displayMetrics)
        view.configure(px, store.spacing10 / 10f, Ui.dp(this, store.marginDp), store.bold)
    }

    private fun reflow() {
        applySettings()
        history.clear()
        if (loaded && view.ready) view.showAt(view.start)
        updateStatus()
        refreshMenuValues()
        view.flash()
    }

    private fun buildMenu(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            background = Ui.border(this@ReaderActivity, 3, 0)
            val pad = Ui.dp(this@ReaderActivity, 10)
            setPadding(pad, pad, pad, pad)
            isClickable = true   // 不讓點擊穿透到閱讀畫面
        }
        infoTv = Ui.label(this, "", 16f, true).apply { maxLines = 2 }
        p.addView(infoTv, Ui.matchWrap(this))

        val r1 = Ui.row(this)
        Ui.addWeighted(r1, Ui.button(this, "書架") { if (isTaskRoot) startActivity(Intent(this, ShelfActivity::class.java)); finish() })
        Ui.addWeighted(r1, Ui.button(this, "目錄") { hideMenu(); showToc() })
        Ui.addWeighted(r1, Ui.button(this, "跳到 %") { askJump() })
        Ui.addWeighted(r1, Ui.button(this, "刷新") { hideMenu(); view.flash() })
        p.addView(r1, Ui.matchWrap(this, 6))

        fontTv = Ui.label(this, "")
        p.addView(settingRow("字體", fontTv,
            { store.fontSp = (store.fontSp - 1).coerceAtLeast(10); reflow() },
            { store.fontSp = (store.fontSp + 1).coerceAtMost(48); reflow() }), Ui.matchWrap(this, 4))
        spacingTv = Ui.label(this, "")
        p.addView(settingRow("行距", spacingTv,
            { store.spacing10 = (store.spacing10 - 1).coerceAtLeast(10); reflow() },
            { store.spacing10 = (store.spacing10 + 1).coerceAtMost(30); reflow() }), Ui.matchWrap(this))
        marginTv = Ui.label(this, "")
        p.addView(settingRow("邊距", marginTv,
            { store.marginDp = (store.marginDp - 2).coerceAtLeast(0); reflow() },
            { store.marginDp = (store.marginDp + 2).coerceAtMost(60); reflow() }), Ui.matchWrap(this))
        refreshTv = Ui.label(this, "")
        p.addView(settingRow("殘影刷新", refreshTv,
            { store.refreshEvery = (store.refreshEvery - 1).coerceAtLeast(0); refreshMenuValues() },
            { store.refreshEvery = (store.refreshEvery + 1).coerceAtMost(30); refreshMenuValues() }), Ui.matchWrap(this))

        val r2 = Ui.row(this)
        boldTv = Ui.button(this, "") { store.bold = !store.bold; reflow() }
        Ui.addWeighted(r2, boldTv)
        Ui.addWeighted(r2, Ui.button(this, "關閉選單") { hideMenu() })
        p.addView(r2, Ui.matchWrap(this, 6))
        return p
    }

    private fun settingRow(name: String, valueTv: TextView, minus: () -> Unit, plus: () -> Unit): LinearLayout {
        val r = Ui.row(this)
        val n = Ui.label(this, name, 17f, true)
        Ui.addWeighted(r, n, 1.2f)
        Ui.addWeighted(r, Ui.button(this, "－", 20f, minus), 1f)
        valueTv.gravity = Gravity.CENTER
        valueTv.textSize = 17f
        Ui.addWeighted(r, valueTv, 1.4f)
        Ui.addWeighted(r, Ui.button(this, "＋", 20f, plus), 1f)
        return r
    }

    private fun refreshMenuValues() {
        fontTv.text = "${store.fontSp}"
        spacingTv.text = String.format(Locale.US, "%.1f", store.spacing10 / 10f)
        marginTv.text = "${store.marginDp}"
        refreshTv.text = if (store.refreshEvery == 0) "關閉" else "每 ${store.refreshEvery} 頁"
        boldTv.text = if (store.bold) "粗體：開" else "粗體：關"
        val ci = currentChapterIndex()
        val ch = if (ci >= 0) chapters[ci].title + "　" else ""
        infoTv.text = title + "\n" + ch + String.format(Locale.US, "%.1f%%", percent())
    }

    private fun showMenu() {
        refreshMenuValues()
        menu.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        view.flash()   // 選單蓋過的區域容易留殘影，順便刷新
    }

    private fun askJump() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0 ～ 100"
            setText(String.format(Locale.US, "%.1f", percent()))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("跳到百分比")
            .setView(et)
            .setPositiveButton("跳轉") { _, _ ->
                val v = et.text.toString().toFloatOrNull()
                if (v != null) {
                    var off = (view.text.length * (v.coerceIn(0f, 100f) / 100f)).toInt()
                    // 對齊到段落開頭，讀起來比較自然
                    val nl = view.text.lastIndexOf('\n', maxOf(0, off - 1))
                    if (nl >= 0 && off - nl < 2000) off = nl + 1
                    hideMenu()
                    jumpTo(off)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================ 目錄（分頁，不捲動） ============================

    private var tocPage = 0

    private fun showToc() {
        if (chapters.isEmpty()) {
            Toast.makeText(this, "這本書找不到章節標題", Toast.LENGTH_SHORT).show()
            return
        }
        val ci = currentChapterIndex().coerceAtLeast(0)
        tocPage = ci / tocRows()
        renderToc()
    }

    private fun tocRows(): Int {
        val rowH = Ui.dp(this, 52)
        val avail = root.height - Ui.dp(this, 140)
        return (avail / rowH).coerceIn(4, 30)
    }

    private fun renderToc() {
        toc?.let { root.removeView(it) }
        val rows = tocRows()
        val pages = (chapters.size + rows - 1) / rows
        tocPage = tocPage.coerceIn(0, maxOf(0, pages - 1))
        val cur = currentChapterIndex()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = Ui.dp(this@ReaderActivity, 14)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }
        panel.addView(Ui.label(this, "目錄　${tocPage + 1} / $pages", 20f, true), Ui.matchWrap(this))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val from = tocPage * rows
        for (i in from until minOf(chapters.size, from + rows)) {
            val c = chapters[i]
            val tv = Ui.label(this, (if (i == cur) "▶ " else "　") + c.title, 18f, i == cur).apply {
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { closeToc(); jumpTo(c.offset) }
            }
            list.addView(tv, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = Ui.row(this)
        Ui.addWeighted(nav, Ui.button(this, "上一頁") { if (tocPage > 0) { tocPage--; renderToc() } })
        Ui.addWeighted(nav, Ui.button(this, "下一頁") { if (tocPage < pages - 1) { tocPage++; renderToc() } })
        Ui.addWeighted(nav, Ui.button(this, "關閉") { closeToc() })
        panel.addView(nav, Ui.matchWrap(this))

        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toc = panel
    }

    private fun closeToc() {
        toc?.let { root.removeView(it) }
        toc = null
        view.flash()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }
}
