package tw.bktu.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一本書：key 是檔案路徑（/ 開頭）或 content:// URI */
data class Book(val key: String, val title: String, val percent: Float, val time: Long)

class Store(context: Context) {
    private val p = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var fontSp: Int
        get() = p.getInt("font", 20)
        set(v) = p.edit().putInt("font", v).apply()

    /** 行距倍數 x10（15 = 1.5 倍） */
    var spacing10: Int
        get() = p.getInt("spacing", 15)
        set(v) = p.edit().putInt("spacing", v).apply()

    var marginDp: Int
        get() = p.getInt("margin", 18)
        set(v) = p.edit().putInt("margin", v).apply()

    var bold: Boolean
        get() = p.getBoolean("bold", true)
        set(v) = p.edit().putBoolean("bold", v).apply()

    /** 每幾頁全畫面刷新一次（0 = 關閉） */
    var refreshEvery: Int
        get() = p.getInt("refresh", 6)
        set(v) = p.edit().putInt("refresh", v).apply()

    var indent: Boolean
        get() = p.getBoolean("indent", true)
        set(v) = p.edit().putBoolean("indent", v).apply()

    /** 段落間距，單位 0.1 字高 */
    var paraGap10: Int
        get() = p.getInt("paraGap", 5)
        set(v) = p.edit().putInt("paraGap", v).apply()

    var night: Boolean
        get() = p.getBoolean("night", false)
        set(v) = p.edit().putBoolean("night", v).apply()

    var lastDir: String
        get() = p.getString("lastDir", "") ?: ""
        set(v) = p.edit().putString("lastDir", v).apply()

    fun offset(key: String): Int = p.getInt("off_" + key.hashCode(), 0)
    fun setOffset(key: String, off: Int) = p.edit().putInt("off_" + key.hashCode(), off).apply()

    var books: List<Book>
        get() {
            val raw = p.getString("books", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Book(o.getString("key"), o.getString("title"),
                        o.optDouble("percent", 0.0).toFloat(), o.optLong("time", 0L))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        private set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("key", it.key).put("title", it.title)
                    .put("percent", it.percent.toDouble()).put("time", it.time))
            }
            p.edit().putString("books", arr.toString()).apply()
        }

    /** 更新（或新增）一本書並移到最前面 */
    fun touch(book: Book) {
        books = listOf(book) + books.filter { it.key != book.key }.take(199)
    }

    fun remove(key: String) {
        books = books.filter { it.key != key }
    }
}
