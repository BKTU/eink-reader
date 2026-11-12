package tw.bktu.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 書架：最近讀過的書（分頁顯示，不捲動） */
class ShelfActivity : Activity() {

    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var pageLabel: android.widget.TextView
    private var page = 0
    private val dateFmt = SimpleDateFormat("M/d HH:mm", Locale.TAIWAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = Ui.dp(this@ShelfActivity, 16)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(Ui.label(this, "書架", 26f, true), Ui.matchWrap(this))

        val actions = Ui.row(this)
        Ui.addWeighted(actions, Ui.button(this, "瀏覽資料夾") {
            startActivity(Intent(this, BrowserActivity::class.java))
        })
        Ui.addWeighted(actions, Ui.button(this, "系統選檔") { pickFile() })
        root.addView(actions, Ui.matchWrap(this, 8))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(this@ShelfActivity, 10) })

        val nav = Ui.row(this)
        Ui.addWeighted(nav, Ui.button(this, "上一頁") { if (page > 0) { page--; render() } })
        pageLabel = Ui.label(this, "", 17f).apply { gravity = Gravity.CENTER }
        Ui.addWeighted(nav, pageLabel)
        Ui.addWeighted(nav, Ui.button(this, "下一頁") { page++; render() })
        root.addView(nav, Ui.matchWrap(this))

        setContentView(root)
        list.post { render() }
    }

    override fun onResume() {
        super.onResume()
        if (list.height > 0) render()
    }

    private fun rowsPerPage(): Int = (list.height / Ui.dp(this, 76)).coerceIn(3, 20)

    private fun render() {
        list.removeAllViews()
        val books = store.books
        if (books.isEmpty()) {
            list.addView(Ui.label(this, "還沒有書。\n\n按「瀏覽資料夾」或「系統選檔」開啟 TXT 檔。\n也可以從檔案管理器用「閱讀器」開啟。", 18f),
                Ui.matchWrap(this, 20))
            pageLabel.text = ""
            return
        }
        val rows = rowsPerPage()
        val pages = (books.size + rows - 1) / rows
        page = page.coerceIn(0, pages - 1)
        pageLabel.text = "${page + 1} / $pages"
        for (b in books.drop(page * rows).take(rows)) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.border(this@ShelfActivity, 1, 4)
                val pad = Ui.dp(this@ShelfActivity, 10)
                setPadding(pad, 0, pad, 0)
                setOnClickListener { open(b) }
                setOnLongClickListener { askRemove(b); true }
            }
            item.addView(Ui.label(this, b.title.removeSuffix(".txt").removeSuffix(".TXT"), 19f, true).apply { maxLines = 1 })
            item.addView(Ui.label(this, String.format(Locale.US, "%.1f%%", b.percent) + "　" + dateFmt.format(Date(b.time)), 14f))
            list.addView(item, LinearLayout.LayoutParams(-1, Ui.dp(this, 68)).apply { bottomMargin = Ui.dp(this@ShelfActivity, 8) })
        }
    }

    private fun open(b: Book) {
        val i = Intent(this, ReaderActivity::class.java)
        if (b.key.startsWith("/")) i.putExtra("path", b.key) else i.data = Uri.parse(b.key)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun askRemove(b: Book) {
        AlertDialog.Builder(this)
            .setTitle("從書架移除？")
            .setMessage(b.title + "\n（不會刪除檔案）")
            .setPositiveButton("移除") { _, _ -> store.remove(b.key); render() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream", "text/*"))
        }
        try {
            startActivityForResult(i, 1)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "這台裝置沒有系統選檔器，請用「瀏覽資料夾」", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        startActivity(Intent(this, ReaderActivity::class.java).setData(uri))
    }
}
