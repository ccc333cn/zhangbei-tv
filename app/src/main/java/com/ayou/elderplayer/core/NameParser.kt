package com.ayou.elderplayer.core

/**
 * 从文件名里认集数、从文件夹名里认季数。
 * 认不出来就返回 null，由调用方退回到按名称自然排序。
 */
object NameParser {

    private const val CN_NUM = "零〇一二两三四五六七八九十百"

    private val SXXEXX = Regex("""(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?!\d)""")
    private val NXNN = Regex("""(?<![0-9a-z])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val DI_JI = Regex("""第\s*(\d{1,4}|[$CN_NUM]{1,6})\s*[集话話回期]""")
    private val NUM_JI = Regex("""(?<!\d)(\d{1,4})\s*[集话話]""")
    private val EP = Regex("""(?<![a-z])(?:ep|e)[ ._-]?(\d{1,4})(?!\d)""")

    // 去掉这些干扰数字后，如果只剩一个数字，就把它当集数
    private val NOISE = listOf(
        Regex("""[(（\[【][^)）\]】]*[)）\]】]"""),               // 括号里的内容：年份、字幕组、分辨率
        Regex("""(?<!\d)(19|20)\d{2}(?!\d)"""),                   // 年份
        Regex("""\d{3,4}[pi](?![a-z])"""),                       // 1080p、720p、1080i
        Regex("""(?<![a-z0-9])[248]k(?![a-z])"""),               // 4k
        Regex("""[xh][ .]?26[45]"""),                            // x264、h.265
        Regex("""\d+\s*bits?"""),                                // 10bit
        Regex("""(ddp?|aac|ac-?3|e-?ac-?3|dts(-hd)?|truehd|atmos|flac|opus)[ .]?\d([ .]\d)?"""),
        Regex("""(?<!\d)[257]\.[01](?!\d)"""),                   // 5.1、2.0、7.1 声道
        Regex("""\d+(\.\d+)?\s*(mb|gb|fps|kbps|mbps|hz)"""),
        Regex("""(?<![a-z])(mp4|mp3|m4a|hevc|avc|web-?dl|webrip|bluray|hdtv)"""),
    )

    private val SEASON_EN = Regex("""(?<![a-z])(?:season|series)[ ._-]*(\d{1,3})(?!\d)""")
    private val SEASON_S = Regex("""(?<![a-z0-9])s(\d{1,2})(?![0-9e])""")
    private val SEASON_CN = Regex("""第\s*(\d{1,3}|[$CN_NUM]{1,4})\s*[季部]""")
    private val SPECIALS = Regex("""(?<![a-z])(specials?|sp|extras?|ova|oad)(?![a-z])|特别篇|特別篇|番外|花絮|特辑|特輯""")

    /** 文件名（可带扩展名）里的集数。[showTitle] 是剧名，剧名里自带的数字（比如「三体2」）会先去掉。 */
    fun episodeNumber(fileName: String, showTitle: String? = null): Int? {
        var s = NaturalOrder.normalize(stripExtension(fileName))
        SXXEXX.find(s)?.let { return it.groupValues[2].toInt() }
        NXNN.find(s)?.let { return it.groupValues[2].toInt() }
        DI_JI.find(s)?.let { return parseNumber(it.groupValues[1]) }
        NUM_JI.find(s)?.let { return it.groupValues[1].toInt() }
        EP.find(s)?.let { return it.groupValues[1].toInt() }

        if (showTitle != null) {
            val core = titleCore(showTitle)
            if (core.isNotEmpty()) s = s.replaceFirst(core, " ")
        }
        for (r in NOISE) s = r.replace(s, " ")
        val nums = Regex("""\d+""").findAll(s).map { it.value }.toList()
        if (nums.size == 1 && nums[0].length <= 4) return nums[0].toInt()
        return null
    }

    /** 文件名里写的季数（S02E05 里的 2），没有就是 null。 */
    fun seasonInFileName(fileName: String): Int? {
        val s = NaturalOrder.normalize(stripExtension(fileName))
        SXXEXX.find(s)?.let { return it.groupValues[1].toInt() }
        NXNN.find(s)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** 季文件夹名里的季数。特别篇返回 0，认不出返回 null。 */
    fun seasonNumber(folderName: String): Int? {
        val s = NaturalOrder.normalize(folderName)
        SEASON_EN.find(s)?.let { return it.groupValues[1].toInt() }
        SEASON_CN.find(s)?.let { return parseNumber(it.groupValues[1]) }
        if (SPECIALS.containsMatchIn(s)) return 0
        SEASON_S.find(s)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** 剧名去掉括号部分，例如「某某电视剧（2026）」→「某某电视剧」。 */
    fun titleCore(showName: String): String =
        NaturalOrder.normalize(showName)
            .replace(Regex("""[(（\[【][^)）\]】]*[)）\]】]"""), " ")
            .trim()

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
    }

    /** 阿拉伯数字或中文数字（一到九百九十九）。 */
    fun parseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        var total = 0
        var digit = -1
        for (c in s) {
            val d = "零〇一二三四五六七八九".indexOf(c).let { idx ->
                when {
                    idx < 0 -> null
                    idx <= 1 -> 0
                    else -> idx - 1
                }
            } ?: if (c == '两') 2 else null
            when {
                d != null -> digit = d
                c == '十' -> { total += (if (digit < 0) 1 else digit) * 10; digit = -1 }
                c == '百' -> { total += (if (digit < 0) 1 else digit) * 100; digit = -1 }
                else -> return null
            }
        }
        if (digit > 0) total += digit
        return if (total > 0 || s.all { it == '零' || it == '〇' }) total else null
    }
}
