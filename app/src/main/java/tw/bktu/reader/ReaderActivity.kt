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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private var toc: LinearLayout? = null

    // 選單：上方工具列 + 下方面板
    private lateinit var topBar: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var sub: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var progressTv: TextView
    private lateinit var tabFont: TextView
    private lateinit var tabLayout: TextView
    private lateinit var tabNight: TextView
    private var subMode = ""          // "", "font", "layout", "download"
    private var menuOpen = false

    private var key = ""
    private var title = ""
    private var chapters: List<Chapter> = emptyList()
    private var loaded = false
    private var pageCount = 0
    private val history = ArrayList<Int>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.TAIWAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        hideSystemBars()

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        view = ReaderView(this)
        applySettings()
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        bottom = buildBottom()
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        topBar.visibility = View.GONE
        bottom.visibility = View.GONE
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

        val path = intent.getStringExtra("path")
        val uri = intent.data
        when {
            path != null -> { key = path; title = File(path).name }
            uri != null && uri.scheme == "file" && uri.path != null -> { key = uri.path!!; title = File(key).name }
            uri != null -> { key = uri.toString(); title = displayName(uri) }
            else -> { finish(); return }
        }
        title = title.removeSuffix(".txt").removeSuffix(".TXT")
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
                    val show = { view.showAt(off); updateStatus(); saveProgress() }
                    if (view.ready) show() else view.post { show() }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    view.message = "無法開啟檔案（點一下返回）"
                    view.invalidate()
                    Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ============================ 翻頁 ============================

    private fun onTap(x: Float, y: Float, w: Int, h: Int) {
        if (!loaded) { if (view.message != "載入中…") finish(); return }
        if (toc != null) return
        if (menuOpen) { hideMenu(); return }
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
        if (loaded && toc == null && !menuOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> { nextPage(); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { prevPage(); return true }
                KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (toc != null) { closeToc(); return true }
            if (menuOpen) { hideMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    // ============================ 狀態與進度 ============================

    private fun percentOf(pos: Int): Float {
        val len = view.text.length
        return if (len <= 1) 100f else (pos.toFloat() / len * 100f).coerceIn(0f, 100f)
    }

    private fun percent(): Float = percentOf(view.end)

    private fun chapterIndexAt(pos: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chapters[mid].offset <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun currentChapterIndex(): Int = chapterIndexAt(view.start)

    private fun battery(): Int = try {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0) -1 else level * 100 / scale
    } catch (e: Exception) { -1 }

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

    private fun applySettings() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, store.fontSp.toFloat(), resources.displayMetrics)
        view.configure(px, store.spacing10 / 10f, Ui.dp(this, store.marginDp), store.bold,
            store.indent, store.paraGap10 / 10f, store.night, Fonts.typeface(store.fontId))
        if (::root.isInitialized) root.setBackgroundColor(view.bgColor)
    }

    /** 設定改變後重新排版（保留目前位置） */
    private fun reflow() {
        applySettings()
        history.clear()
        if (loaded && view.ready) view.showAt(view.start)
        updateStatus()
        refreshMenu()
    }

    // ============================ 選單 ============================

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.border(this@ReaderActivity, 2, 0)
        val pad = Ui.dp(this@ReaderActivity, 10)
        setPadding(pad, pad, pad, pad)
        isClickable = true    // 不讓點擊穿透到閱讀畫面
    }

    private fun buildTopBar(): LinearLayout {
        val p = panel()
        val r = Ui.row(this)
        Ui.addWeighted(r, Ui.button(this, "‹ 書架") {
            if (isTaskRoot) startActivity(Intent(this, ShelfActivity::class.java))
            finish()
        }, 1f)
        val t = Ui.label(this, "", 17f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = "title"
        }
        Ui.addWeighted(r, t, 2.2f)
        Ui.addWeighted(r, Ui.button(this, "目錄") { hideMenu(flash = false); showToc() }, 1f)
        p.addView(r, Ui.matchWrap(this))
        return p
    }

    private fun buildBottom(): LinearLayout {
        val p = panel()

        sub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(sub, Ui.matchWrap(this))

        // 進度：上一章 ─ 拖曳條 ─ 下一章
        val pr = Ui.row(this)
        Ui.addWeighted(pr, Ui.button(this, "‹ 上章", 15f) { prevChapter() }, 1f)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) progressTv.text = "放開跳到 " + String.format(Locale.US, "%.1f%%", v / 10f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    jumpPercent((s?.progress ?: 0) / 10f)
                }
            })
        }
        Ui.addWeighted(pr, seek, 2.6f, 2)
        Ui.addWeighted(pr, Ui.button(this, "下章 ›", 15f) { nextChapter() }, 1f)
        p.addView(pr, Ui.matchWrap(this, 4))

        progressTv = Ui.label(this, "", 15f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { askJump() }
        }
        p.addView(progressTv, Ui.matchWrap(this, 2))

        // 主要功能
        val tabs = Ui.row(this)
        tabFont = Ui.button(this, "字型") { toggleSub("font") }
        tabLayout = Ui.button(this, "排版") { toggleSub("layout") }
        tabNight = Ui.button(this, "夜間") { store.night = !store.night; reflow(); view.flash() }
        Ui.addWeighted(tabs, tabFont)
        Ui.addWeighted(tabs, tabLayout)
        Ui.addWeighted(tabs, tabNight)
        Ui.addWeighted(tabs, Ui.button(this, "刷新") { hideMenu() })
        p.addView(tabs, Ui.matchWrap(this, 4))
        return p
    }

    private fun toggleSub(mode: String) {
        subMode = if (subMode == mode) "" else mode
        refreshMenu()
    }

    private fun showMenu() {
        subMode = ""
        menuOpen = true
        refreshMenu()
        topBar.visibility = View.VISIBLE
        bottom.visibility = View.VISIBLE
    }

    private fun hideMenu(flash: Boolean = true) {
        menuOpen = false
        topBar.visibility = View.GONE
        bottom.visibility = View.GONE
        if (flash) view.flash()   // 選單蓋過的區域容易留殘影
    }

    private fun refreshMenu() {
        if (!menuOpen && toc == null) return
        topBar.findViewWithTag<TextView>("title")?.text = title
        val ci = currentChapterIndex()
        val ch = if (ci >= 0) chapters[ci].title + "　" else ""
        progressTv.text = ch + String.format(Locale.US, "%.1f%%", percent()) + "（點這裡輸入 %）"
        seek.progress = (percent() * 10).toInt()
        Ui.select(tabFont, subMode == "font" || subMode == "download")
        Ui.select(tabLayout, subMode == "layout")
        Ui.select(tabNight, store.night)
        sub.removeAllViews()
        when (subMode) {
            "font" -> buildFontPanel()
            "layout" -> buildLayoutPanel()
            "download" -> buildDownloadPanel()
        }
    }

    private fun stepper(name: String, value: String, minus: () -> Unit, plus: () -> Unit): LinearLayout {
        val r = Ui.row(this)
        Ui.addWeighted(r, Ui.label(this, name, 17f, true), 1.3f)
        Ui.addWeighted(r, Ui.button(this, "－", 20f) { minus(); reflow() }, 1f)
        Ui.addWeighted(r, Ui.label(this, value, 18f, true).apply { gravity = Gravity.CENTER }, 1.4f)
        Ui.addWeighted(r, Ui.button(this, "＋", 20f) { plus(); reflow() }, 1f)
        return r
    }

    private fun buildFontPanel() {
        sub.addView(stepper("字體大小", "${store.fontSp}",
            { store.fontSp = (store.fontSp - 1).coerceAtLeast(10) },
            { store.fontSp = (store.fontSp + 1).coerceAtMost(60) }), Ui.matchWrap(this))

        // 快速大小
        val quick = Ui.row(this)
        for ((label, size) in listOf("小" to 16, "中" to 20, "大" to 24, "特大" to 30)) {
            val b = Ui.button(this, label, 16f) { store.fontSp = size; reflow() }
            Ui.select(b, store.fontSp == size)
            Ui.addWeighted(quick, b)
        }
        sub.addView(quick, Ui.matchWrap(this))

        sub.addView(Ui.label(this, "字型（長按已下載的可刪除）", 15f, true), Ui.matchWrap(this, 8))
        val fonts = Fonts.local(this)
        var r: LinearLayout? = null
        fonts.forEachIndexed { i, f ->
            if (i % 2 == 0) {
                val nr = Ui.row(this)
                sub.addView(nr, Ui.matchWrap(this))
                r = nr
            }
            val b = Ui.button(this, f.name, 16f) { store.fontId = f.id; reflow() }.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // 用該字型顯示名稱，選之前就看得到樣子
                typeface = Fonts.typeface(f.id)
                if (f.id.startsWith("file:")) setOnLongClickListener { askDeleteFont(f); true }
            }
            Ui.select(b, store.fontId == f.id)
            Ui.addWeighted(r!!, b)
        }
        if (fonts.size % 2 == 1) Ui.addWeighted(r!!, View(this))
        sub.addView(Ui.button(this, "⬇ 下載更多字型", 16f) { subMode = "download"; refreshMenu() }, Ui.matchWrap(this, 6))
    }

    private fun askDeleteFont(f: FontItem) {
        AlertDialog.Builder(this)
            .setTitle("刪除字型？")
            .setMessage(f.name)
            .setPositiveButton("刪除") { _, _ ->
                Fonts.delete(this, f.id)
                if (store.fontId == f.id) store.fontId = "sys:sans"
                reflow()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildDownloadPanel() {
        val status = Ui.label(this, "讀取字型清單中…", 16f)
        sub.addView(status, Ui.matchWrap(this))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sub.addView(list, Ui.matchWrap(this))
        sub.addView(Ui.button(this, "‹ 返回字型", 16f) { subMode = "font"; refreshMenu() }, Ui.matchWrap(this, 6))

        Thread {
            try {
                val remote = Fonts.remote()
                runOnUiThread {
                    if (subMode != "download") return@runOnUiThread
                    status.text = if (remote.isEmpty()) "目前沒有可下載的字型" else "點一下下載（需要網路）"
                    for (rf in remote) {
                        val done = Fonts.isDownloaded(this, rf)
                        val mb = String.format(Locale.US, "%.1f MB", rf.size / 1048576f)
                        val b = Ui.button(this, (if (done) "✓ " else "") + rf.name + "　" + mb, 16f) {}
                        b.setOnClickListener {
                            if (Fonts.isDownloaded(this, rf)) { status.text = rf.name + " 已下載，請回字型選擇"; return@setOnClickListener }
                            downloadFont(rf, status, b)
                        }
                        list.addView(b, Ui.matchWrap(this, 4))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "無法連線：" + (e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun downloadFont(rf: RemoteFont, status: TextView, btn: TextView) {
        status.text = "下載 ${rf.name}… 0%"
        Thread {
            try {
                val f = Fonts.download(this, rf) { p -> runOnUiThread { status.text = "下載 ${rf.name}… $p%" } }
                runOnUiThread {
                    btn.text = "✓ " + rf.name
                    status.text = "${rf.name} 下載完成，已套用"
                    store.fontId = "file:" + f.absolutePath
                    applySettings()
                    if (loaded && view.ready) view.showAt(view.start)
                    updateStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "下載失敗：" + (e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun buildLayoutPanel() {
        sub.addView(stepper("行距", String.format(Locale.US, "%.1f", store.spacing10 / 10f),
            { store.spacing10 = (store.spacing10 - 1).coerceAtLeast(10) },
            { store.spacing10 = (store.spacing10 + 1).coerceAtMost(30) }), Ui.matchWrap(this))
        sub.addView(stepper("段距", String.format(Locale.US, "%.1f", store.paraGap10 / 10f),
            { store.paraGap10 = (store.paraGap10 - 1).coerceAtLeast(0) },
            { store.paraGap10 = (store.paraGap10 + 1).coerceAtMost(20) }), Ui.matchWrap(this))
        sub.addView(stepper("邊距", "${store.marginDp}",
            { store.marginDp = (store.marginDp - 2).coerceAtLeast(0) },
            { store.marginDp = (store.marginDp + 2).coerceAtMost(60) }), Ui.matchWrap(this))
        sub.addView(stepper("殘影刷新", if (store.refreshEvery == 0) "關閉" else "每${store.refreshEvery}頁",
            { store.refreshEvery = (store.refreshEvery - 1).coerceAtLeast(0) },
            { store.refreshEvery = (store.refreshEvery + 1).coerceAtMost(30) }), Ui.matchWrap(this))
        val r = Ui.row(this)
        val bold = Ui.button(this, "粗體") { store.bold = !store.bold; reflow() }
        val indent = Ui.button(this, "首行縮排") { store.indent = !store.indent; reflow() }
        Ui.select(bold, store.bold)
        Ui.select(indent, store.indent)
        Ui.addWeighted(r, bold)
        Ui.addWeighted(r, indent)
        sub.addView(r, Ui.matchWrap(this, 4))
    }

    // ============================ 跳轉 ============================

    private fun jumpPercent(pct: Float) {
        var off = (view.text.length * (pct.coerceIn(0f, 100f) / 100f)).toInt()
        val nl = view.text.lastIndexOf('\n', maxOf(0, off - 1))
        if (nl >= 0 && off - nl < 2000) off = nl + 1
        jumpTo(off)
        refreshMenu()
    }

    private fun prevChapter() {
        if (chapters.isEmpty()) { Toast.makeText(this, "這本書沒有章節", Toast.LENGTH_SHORT).show(); return }
        val ci = currentChapterIndex()
        val target = when {
            ci < 0 -> 0
            view.start > chapters[ci].offset + 50 -> chapters[ci].offset   // 先回到本章開頭
            ci > 0 -> chapters[ci - 1].offset
            else -> 0
        }
        jumpTo(target)
        refreshMenu()
    }

    private fun nextChapter() {
        if (chapters.isEmpty()) { Toast.makeText(this, "這本書沒有章節", Toast.LENGTH_SHORT).show(); return }
        val ci = currentChapterIndex()
        if (ci + 1 >= chapters.size) { Toast.makeText(this, "已經是最後一章", Toast.LENGTH_SHORT).show(); return }
        jumpTo(chapters[ci + 1].offset)
        refreshMenu()
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
            .setPositiveButton("跳轉") { _, _ -> et.text.toString().toFloatOrNull()?.let { jumpPercent(it) } }
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
        tocPage = currentChapterIndex().coerceAtLeast(0) / tocRows()
        renderToc()
    }

    private fun tocRows(): Int = ((root.height - Ui.dp(this, 150)) / Ui.dp(this, 52)).coerceIn(4, 30)

    private fun renderToc() {
        toc?.let { root.removeView(it) }
        val rows = tocRows()
        val pages = (chapters.size + rows - 1) / rows
        tocPage = tocPage.coerceIn(0, maxOf(0, pages - 1))
        val cur = currentChapterIndex()

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = Ui.dp(this@ReaderActivity, 14)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }
        p.addView(Ui.label(this, "目錄　${tocPage + 1} / $pages　（共 ${chapters.size} 章）", 19f, true), Ui.matchWrap(this))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val from = tocPage * rows
        for (i in from until minOf(chapters.size, from + rows)) {
            val c = chapters[i]
            val tv = Ui.label(this, (if (i == cur) "▶ " else "　") + c.title, 18f, i == cur).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { closeToc(); jumpTo(c.offset) }
            }
            list.addView(tv, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)))
        }
        p.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = Ui.row(this)
        Ui.addWeighted(nav, Ui.button(this, "上一頁") { if (tocPage > 0) { tocPage--; renderToc() } })
        Ui.addWeighted(nav, Ui.button(this, "下一頁") { if (tocPage < pages - 1) { tocPage++; renderToc() } })
        Ui.addWeighted(nav, Ui.button(this, "關閉") { closeToc() })
        p.addView(nav, Ui.matchWrap(this))

        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toc = p
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
