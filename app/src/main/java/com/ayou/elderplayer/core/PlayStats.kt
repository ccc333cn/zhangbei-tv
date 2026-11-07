package com.ayou.elderplayer.core

import java.util.Calendar
import java.util.TimeZone

/**
 * 播放统计：每天看了多久，以及最近的几次观看。
 * 一次观看 = 连着看同一部剧，中间停下不超过 [gapMs]。只存在电视本机。
 */
class PlayStats(private val tz: TimeZone = TimeZone.getDefault()) {

    data class Session(
        val startMs: Long,
        var endMs: Long,
        var watchedMs: Long,
        val show: String,
        val firstLabel: String,
        var lastLabel: String,
    )

    /** 日期（yyyy-MM-dd）→ 当天看了多少毫秒 */
    val daily = LinkedHashMap<String, Long>()
    val sessions = ArrayList<Session>()

    /** 播放中每隔一段时间调用一次，[deltaMs] 是这段真正在播的时间。 */
    fun onPlayed(nowMs: Long, show: String, episodeLabel: String, deltaMs: Long, gapMs: Long = 10 * 60_000L) {
        if (deltaMs <= 0) return
        val day = dayKey(nowMs)
        daily[day] = (daily[day] ?: 0) + deltaMs

        val last = sessions.lastOrNull()
        if (last != null && last.show == show && nowMs - last.endMs <= gapMs) {
            last.endMs = nowMs
            last.watchedMs += deltaMs
            last.lastLabel = episodeLabel
        } else {
            sessions += Session(nowMs - deltaMs, nowMs, deltaMs, show, episodeLabel, episodeLabel)
        }
        trim(nowMs)
    }

    fun todayMs(nowMs: Long) = daily[dayKey(nowMs)] ?: 0

    /** 最近 [days] 天（含今天），从早到晚。 */
    fun lastDays(nowMs: Long, days: Int = 7): List<Pair<String, Long>> {
        val cal = calendar(nowMs)
        val out = ArrayList<Pair<String, Long>>()
        for (i in days - 1 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, -i)
            val label = when (i) {
                0 -> "今天"
                1 -> "昨天"
                else -> WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
            }
            out += label to (daily[dayKey(c.timeInMillis)] ?: 0)
        }
        return out
    }

    fun recent(n: Int): List<Session> = sessions.takeLast(n).reversed()

    /** 「今天 19:20」「昨天 20:10」「10月28日 20:10」 */
    fun whenLabel(nowMs: Long, atMs: Long): String {
        val now = calendar(nowMs)
        val at = calendar(atMs)
        val hm = "%02d:%02d".format(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))
        val days = dayNumber(now) - dayNumber(at)
        return when (days) {
            0L -> "今天 $hm"
            1L -> "昨天 $hm"
            else -> "${at.get(Calendar.MONTH) + 1}月${at.get(Calendar.DAY_OF_MONTH)}日 $hm"
        }
    }

    fun serialize(): String = buildString {
        for ((d, ms) in daily) append("D\t").append(d).append('\t').append(ms).append('\n')
        for (s in sessions) {
            append("S\t${s.startMs}\t${s.endMs}\t${s.watchedMs}\t")
            append(clean(s.show)).append('\t').append(clean(s.firstLabel)).append('\t').append(clean(s.lastLabel)).append('\n')
        }
    }

    fun load(text: String) {
        daily.clear()
        sessions.clear()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            when {
                p.size == 3 && p[0] == "D" -> p[2].toLongOrNull()?.let { daily[p[1]] = it }
                p.size == 7 && p[0] == "S" -> {
                    val start = p[1].toLongOrNull() ?: continue
                    val end = p[2].toLongOrNull() ?: continue
                    val watched = p[3].toLongOrNull() ?: continue
                    sessions += Session(start, end, watched, p[4], p[5], p[6])
                }
            }
        }
    }

    private fun trim(nowMs: Long) {
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        if (daily.size > KEEP_DAYS) {
            val c = calendar(nowMs)
            c.add(Calendar.DAY_OF_MONTH, -KEEP_DAYS)
            val oldest = dayKey(c.timeInMillis)
            daily.keys.removeAll { it < oldest }
        }
    }

    private fun calendar(ms: Long) = Calendar.getInstance(tz).apply { timeInMillis = ms }

    private fun dayNumber(c: Calendar): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        return utc.timeInMillis / 86_400_000L
    }

    fun dayKey(ms: Long): String {
        val c = calendar(ms)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    companion object {
        const val MAX_SESSIONS = 50
        const val KEEP_DAYS = 30
        private val WEEKDAYS = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

        /** 「2小时15分」「43分」「不到1分钟」 */
        fun durationLabel(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 1 -> "不到1分钟"
                min < 60 -> "${min}分"
                min % 60 == 0L -> "${min / 60}小时"
                else -> "${min / 60}小时${min % 60}分"
            }
        }

        fun episodeRange(s: Session): String =
            if (s.firstLabel == s.lastLabel) s.firstLabel else "${s.firstLabel} – ${s.lastLabel}"
    }
}

object TimeText {
    /** 播放时间：「23:41」或「1:02:03」 */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
