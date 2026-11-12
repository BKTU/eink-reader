package tw.bktu.reader

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Chapter(val title: String, val offset: Int)

object TextLoader {

    /** 自動判斷編碼：BOM → UTF-8 → UTF-16 → Big5 / GB18030（以常用字比例判斷） */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)

        // UTF-16 沒有 BOM：大量 0x00
        val sampleLen = minOf(b.size, 4000)
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until sampleLen) if (b[i].toInt() == 0) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
        if (sampleLen > 0 && zeroOdd > sampleLen / 5) return String(b, Charsets.UTF_16LE)
        if (sampleLen > 0 && zeroEven > sampleLen / 5) return String(b, Charsets.UTF_16BE)

        // UTF-8：錯誤極少（檔尾被截斷的半個字容許）就當 UTF-8
        val utf8Errors = countErrors(b, Charsets.UTF_8, b.size)
        if (utf8Errors <= 1) return String(b, Charsets.UTF_8)

        // Big5 與 GB18030：取樣比較常用字命中率
        val big5 = charset("Big5")
        val gb = charset("GB18030")
        val sample = minOf(b.size, 300_000)
        val sBig5 = score(b, big5, sample)
        val sGb = score(b, gb, sample)
        val best = if (big5 != null && (gb == null || sBig5 >= sGb)) big5 else gb
        return if (best != null) String(b, best) else String(b, Charsets.UTF_8)
    }

    private fun charset(name: String): Charset? = try { Charset.forName(name) } catch (e: Exception) { null }

    private fun countErrors(b: ByteArray, cs: Charset, len: Int): Int {
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("�")
        val s = dec.decode(ByteBuffer.wrap(b, 0, len)).toString()
        var n = 0
        for (c in s) if (c == '�') n++
        return n
    }

    private const val COMMON =
        "的一是不了在人有我他你她們们這这個个說说來来為为會会時时過过還还麼么對对開开關关" +
        "見见讓让問问頭头現现發发樣样後后裡里進进點点上中大小到就出也要可以下那天心想看道去好得之"

    private fun score(b: ByteArray, cs: Charset?, len: Int): Int {
        if (cs == null) return Int.MIN_VALUE
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("�")
        val s = dec.decode(ByteBuffer.wrap(b, 0, len)).toString()
        var hit = 0
        var bad = 0
        for (c in s) {
            if (c == '�') bad++
            else if (COMMON.indexOf(c) >= 0) hit++
        }
        return hit - bad * 5
    }

    /** 統一換行、去掉多餘空行 */
    fun normalize(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n').replace("﻿", "")
        s = s.replace(Regex("[ \\t\\u3000]+\\n"), "\n")       // 行尾空白
        s = s.replace(Regex("\\n{3,}"), "\n\n")                // 最多一個空行
        return s.trimEnd() + "\n"
    }

    private const val SP = "[ \\t\\u3000]*"
    private const val NUM = "[0-9０-９零〇一二兩两三四五六七八九十百千萬万壹貳贰參叁肆伍陸陆柒捌玖拾佰仟]"

    /** 主要規則：第X章／回／節／卷…、序章、楔子、番外、Chapter N、卷一… */
    private val PRIMARY = Regex(
        "(?m)^" + SP + "(?:[【\\[〔「]" + SP + ")?" +
        "(" +
            "(?:正文|VIP|vip)?" + SP + "第" + SP + NUM + "{1,12}" + SP + "[章回節节卷集部篇話话幕折讲講]" + "[^\\n]{0,40}" +
            "|(?:序章|序幕|楔子|序言|序|前言|引子|引言|後記|后记|尾聲|尾声|終章|终章|番外|外傳|外传|完結感言|完结感言|作者的話|作者的话)(?:[ \\t\\u3000:：、·.．\\-—][^\\n]{0,30}|" + NUM + "{1,4}[^\\n]{0,30})?" +
            "|(?:Chapter|CHAPTER|chapter)" + SP + "[0-9IVXLC]+[^\\n]{0,40}" +
            "|卷" + SP + NUM + "{1,4}(?:[ \\t\\u3000:：、][^\\n]{0,30})?" +
        ")" +
        SP + "(?:[】\\]〕」]" + SP + ")?$"
    )

    /** 備用規則（主要規則抓不到時才用）：「1、標題」「一、標題」「001 標題」 */
    private val FALLBACK = Regex(
        "(?m)^" + SP + "(" +
            "[0-9０-９]{1,4}" + SP + "[、.．]" + SP + "[^\\n]{1,30}" +
            "|[一二三四五六七八九十百]{1,4}" + SP + "、" + SP + "[^\\n]{1,30}" +
            "|[0-9]{2,4}[ \\t\\u3000]+[^\\n]{1,30}" +
        ")" + SP + "$"
    )

    private fun scan(text: String, re: Regex): List<Chapter> {
        val out = ArrayList<Chapter>()
        for (m in re.findAll(text)) {
            val title = m.groupValues[1].trim().trimEnd('】', ']', '〕', '」').trim()
            if (title.isEmpty() || title.length > 45) continue
            // 太像內文的長句（含句號、很多逗號）就跳過
            if (title.endsWith("。") || title.count { it == '，' } >= 2) continue
            if (out.isNotEmpty() && out.last().title == title) continue
            // 行首位置（對齊段落開頭）
            val ls = text.lastIndexOf('\n', m.range.first).let { if (it < 0) 0 else it + 1 }
            val off = if (ls <= m.range.first) maxOf(ls, m.range.first) else m.range.first
            out.add(Chapter(title, off))
            if (out.size >= 10000) break
        }
        return out
    }

    fun chapters(text: String): List<Chapter> {
        val primary = scan(text, PRIMARY)
        if (primary.size >= 3) return primary
        val fb = scan(text, FALLBACK)
        return if (fb.size > primary.size) (primary + fb).sortedBy { it.offset } else primary
    }
}
