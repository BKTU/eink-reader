package tw.bktu.reader

import android.content.Context
import android.graphics.Typeface
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class FontItem(val id: String, val name: String)
data class RemoteFont(val fileName: String, val name: String, val url: String, val size: Long)

/** 字型：系統字型、已下載字型、/sdcard/Fonts 裡的字型，以及可從 GitHub 下載的字型 */
object Fonts {
    private const val RELEASE_API = "https://api.github.com/repos/BKTU/eink-reader/releases/tags/fonts"
    private val typefaces = HashMap<String, Typeface>()

    fun dir(ctx: Context): File = File(ctx.filesDir, "fonts").apply { mkdirs() }

    private fun isFontFile(f: File): Boolean {
        val n = f.name.lowercase()
        return f.isFile && (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))
    }

    /** 把檔名轉成好讀的中文名稱 */
    fun prettyName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val family = when {
            base.startsWith("NotoSerifTC", true) -> "思源宋體"
            base.startsWith("NotoSansTC", true) -> "思源黑體"
            base.startsWith("LXGWWenKaiTC", true) -> "霞鶩文楷"
            else -> return base
        }
        val weight = when {
            base.contains("300") -> " 細"
            base.contains("400") -> ""
            base.contains("500") -> " 中"
            base.contains("600") -> " 半粗"
            base.contains("700") -> " 粗"
            else -> ""
        }
        return family + weight
    }

    fun local(ctx: Context): List<FontItem> {
        val out = ArrayList<FontItem>()
        out.add(FontItem("sys:sans", "系統黑體"))
        out.add(FontItem("sys:serif", "系統明體"))
        dir(ctx).listFiles()?.filter { isFontFile(it) }?.sortedBy { it.name }?.forEach {
            out.add(FontItem("file:" + it.absolutePath, prettyName(it.name)))
        }
        // 使用者自己放的字型：內部儲存空間/Fonts
        try {
            val user = File(Environment.getExternalStorageDirectory(), "Fonts")
            user.listFiles()?.filter { isFontFile(it) }?.sortedBy { it.name }?.forEach {
                out.add(FontItem("file:" + it.absolutePath, prettyName(it.name) + "（自備）"))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun typeface(id: String): Typeface {
        typefaces[id]?.let { return it }
        val tf = when {
            id == "sys:serif" -> Typeface.SERIF
            id.startsWith("file:") -> try {
                Typeface.createFromFile(id.removePrefix("file:"))
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
            else -> Typeface.DEFAULT
        }
        typefaces[id] = tf
        return tf
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "eink-reader")
        return c
    }

    /** 讀取 GitHub 上可下載的字型清單（在背景執行緒呼叫） */
    fun remote(): List<RemoteFont> {
        val c = open(RELEASE_API)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val assets = JSONObject(body).getJSONArray("assets")
        return (0 until assets.length()).map {
            val a = assets.getJSONObject(it)
            val n = a.getString("name")
            RemoteFont(n, prettyName(n), a.getString("browser_download_url"), a.optLong("size", 0))
        }.filter { it.fileName.lowercase().endsWith(".ttf") || it.fileName.lowercase().endsWith(".otf") }
    }

    fun isDownloaded(ctx: Context, r: RemoteFont): Boolean = File(dir(ctx), r.fileName).let { it.exists() && it.length() > 0 }

    /** 下載字型（在背景執行緒呼叫），progress 0~100 */
    fun download(ctx: Context, r: RemoteFont, progress: (Int) -> Unit): File {
        val tmp = File(dir(ctx), r.fileName + ".part")
        val dst = File(dir(ctx), r.fileName)
        var c = open(r.url)
        // GitHub 會轉址到其他網域，HttpURLConnection 不會跨協定轉址，手動處理
        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val loc = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(loc)
            hops++
        }
        val total = if (c.contentLength > 0) c.contentLength.toLong() else r.size
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last && p % 5 == 0) { last = p; progress(p) }
                    }
                }
            }
        }
        if (!tmp.renameTo(dst)) throw Exception("無法儲存字型")
        typefaces.remove("file:" + dst.absolutePath)
        return dst
    }

    fun delete(ctx: Context, id: String) {
        if (!id.startsWith("file:")) return
        val f = File(id.removePrefix("file:"))
        if (f.parentFile == dir(ctx)) f.delete()
        typefaces.remove(id)
    }
}
