package com.ayou.elderplayer.core

/**
 * 自然排序：数字按数值比，「第2集」排在「第10集」前面。
 * 只看字符本身，不依赖系统语言和 Collator，所以在哪台机器上排出来都一样。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isAsciiDigit() && cy.isAsciiDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isAsciiDigit()) i++
                while (j < y.length && y[j].isAsciiDigit()) j++
                val c = compareNumber(x.substring(si, i), y.substring(sj, j))
                if (c != 0) return c
            } else {
                if (cx != cy) return cx.compareTo(cy)
                i++
                j++
            }
        }
        val rest = (x.length - i).compareTo(y.length - j)
        if (rest != 0) return rest
        // 只差在大小写、前导零、全角数字时，用原字符串兜底，保证顺序是确定的
        return a.compareTo(b)
    }

    private fun compareNumber(p: String, q: String): Int {
        val a = p.trimStart('0')
        val b = q.trimStart('0')
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }

    /** 全角数字转半角，英文字母转小写。 */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
