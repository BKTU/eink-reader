package tw.bktu.reader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** 內建資料夾瀏覽：只列出資料夾與 .txt，分頁顯示 */
class BrowserActivity : Activity() {

    private lateinit var store: Store
    private lateinit var pathTv: TextView
    private lateinit var list: LinearLayout
    private lateinit var pageLabel: TextView
    private var dir: File = Environment.getExternalStorageDirectory()
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val last = File(store.lastDir)
        if (store.lastDir.isNotEmpty() && last.isDirectory) dir = last

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = Ui.dp(this@BrowserActivity, 16)
            setPadding(pad, pad, pad, pad)
        }
        val top = Ui.row(this)
        Ui.addWeighted(top, Ui.button(this, "⬆ 上一層") { up() })
        Ui.addWeighted(top, Ui.button(this, "根目錄") { go(Environment.getExternalStorageDirectory()) })
        Ui.addWeighted(top, Ui.button(this, "返回書架") { finish() })
        root.addView(top, Ui.matchWrap(this))

        pathTv = Ui.label(this, "", 15f).apply { maxLines = 2 }
        root.addView(pathTv, Ui.matchWrap(this, 6))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(this@BrowserActivity, 8) })

        val nav = Ui.row(this)
        Ui.addWeighted(nav, Ui.button(this, "上一頁") { if (page > 0) { page--; render() } })
        pageLabel = Ui.label(this, "", 17f).apply { gravity = Gravity.CENTER }
        Ui.addWeighted(nav, pageLabel)
        Ui.addWeighted(nav, Ui.button(this, "下一頁") { page++; render() })
        root.addView(nav, Ui.matchWrap(this))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        list.post { render() }
    }

    private fun hasPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) {}
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun go(d: File) {
        dir = d
        page = 0
        store.lastDir = d.absolutePath
        render()
    }

    private fun up() {
        val parent = dir.parentFile
        if (parent != null && parent.canRead()) go(parent)
    }

    private fun rowsPerPage(): Int = (list.height / Ui.dp(this, 60)).coerceIn(3, 25)

    private fun render() {
        list.removeAllViews()
        if (!hasPermission()) {
            pathTv.text = ""
            pageLabel.text = ""
            list.addView(Ui.label(this, "需要「存取所有檔案」權限才能瀏覽資料夾。", 18f), Ui.matchWrap(this, 10))
            list.addView(Ui.button(this, "前往授權") { requestPermission() }, Ui.matchWrap(this, 16))
            return
        }
        pathTv.text = dir.absolutePath
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val txts = all.filter { it.isFile && it.name.lowercase().endsWith(".txt") }.sortedBy { it.name.lowercase() }
        val entries = dirs + txts
        if (entries.isEmpty()) {
            pageLabel.text = ""
            list.addView(Ui.label(this, "（這個資料夾沒有子資料夾或 TXT 檔）", 17f), Ui.matchWrap(this, 10))
            return
        }
        val rows = rowsPerPage()
        val pages = (entries.size + rows - 1) / rows
        page = page.coerceIn(0, pages - 1)
        pageLabel.text = "${page + 1} / $pages"
        for (f in entries.drop(page * rows).take(rows)) {
            val isDir = f.isDirectory
            val sizeKb = if (isDir) "" else "　" + (f.length() / 1024).toString() + " KB"
            val tv = Ui.label(this, (if (isDir) "📁 " else "📄 ") + f.name + sizeKb, 18f, isDir).apply {
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@BrowserActivity, 8), 0, 0, 0)
                background = Ui.border(this@BrowserActivity, 1, 4)
                setOnClickListener {
                    if (isDir) go(f)
                    else startActivity(Intent(this@BrowserActivity, ReaderActivity::class.java).putExtra("path", f.absolutePath))
                }
            }
            list.addView(tv, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)).apply { bottomMargin = Ui.dp(this@BrowserActivity, 8) })
        }
    }
}
