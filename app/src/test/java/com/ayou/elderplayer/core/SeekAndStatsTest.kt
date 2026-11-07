package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SeekAndStatsTest {

    @Test
    fun singleTapIsTenSeconds() {
        val s = SeekStepper()
        assertEquals(10_000, s.onKeyDown(+1, 0, 0))
        assertEquals(-10_000, s.onKeyDown(-1, 0, 5_000))
    }

    @Test
    fun quickTapsBecomeTwentySeconds() {
        val s = SeekStepper()
        assertEquals(10_000, s.onKeyDown(+1, 0, 0))
        assertEquals(20_000, s.onKeyDown(+1, 0, 400))
        assertEquals(20_000, s.onKeyDown(+1, 0, 900))
        // 换方向重新算
        assertEquals(-10_000, s.onKeyDown(-1, 0, 1_200))
        // 停了一会儿再按，回到 10 秒
        assertEquals(-10_000, s.onKeyDown(-1, 0, 5_000))
    }

    @Test
    fun holdingRepeatsTwentySecondsThrottled() {
        val s = SeekStepper()
        assertEquals(10_000, s.onKeyDown(+1, 0, 0))
        assertEquals(0, s.onKeyDown(+1, 1, 100))
        assertEquals(20_000, s.onKeyDown(+1, 2, 300))
        assertEquals(0, s.onKeyDown(+1, 3, 400))
        assertEquals(20_000, s.onKeyDown(+1, 4, 560))
    }

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(day: Int, hour: Int, min: Int): Long =
        Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, min) }.timeInMillis

    @Test
    fun statsGroupContinuousWatching() {
        val st = PlayStats(tz)
        var t = at(30, 20, 10)
        repeat(30) { t += 60_000; st.onPlayed(t, "某某电视剧", "第 9 集", 60_000) }
        repeat(30) { t += 60_000; st.onPlayed(t, "某某电视剧", "第 10 集", 60_000) }
        // 第二天晚上接着看，算新的一次
        t = at(31, 19, 20)
        repeat(20) { t += 60_000; st.onPlayed(t, "某某电视剧", "第 11 集", 60_000) }
        // 换了一部剧，也是新的一次
        repeat(10) { t += 60_000; st.onPlayed(t, "另一部剧", "第 6 集", 60_000) }

        val now = at(31, 22, 0)
        assertEquals(30 * 60_000L, st.todayMs(now))
        val recent = st.recent(5)
        assertEquals(3, recent.size)
        assertEquals("另一部剧", recent[0].show)
        assertEquals("第 9 集 – 第 10 集", PlayStats.episodeRange(recent[2]))
        assertEquals("昨天 20:10", st.whenLabel(now, recent[2].startMs))
        assertEquals("今天 19:20", st.whenLabel(now, recent[1].startMs))
        assertEquals("1小时", PlayStats.durationLabel(recent[2].watchedMs))

        val week = st.lastDays(now)
        assertEquals(7, week.size)
        assertEquals("今天" to 30 * 60_000L, week[6])
        assertEquals("昨天" to 60 * 60_000L, week[5])
        assertEquals("周日", week[0].first) // 2026-10-25 是周日
    }

    @Test
    fun statsSurviveSaveAndLoad() {
        val st = PlayStats(tz)
        st.onPlayed(at(31, 9, 0), "剧\t名", "第 1 集", 5_000)
        val copy = PlayStats(tz).apply { load(st.serialize()) }
        assertEquals(st.daily, copy.daily)
        assertEquals("剧 名", copy.sessions[0].show)
        assertEquals(5_000, copy.sessions[0].watchedMs)
    }

    @Test
    fun durationAndClockText() {
        assertEquals("不到1分钟", PlayStats.durationLabel(30_000))
        assertEquals("43分", PlayStats.durationLabel(43 * 60_000L))
        assertEquals("2小时15分", PlayStats.durationLabel(135 * 60_000L))
        assertEquals("23:41", TimeText.clock((23 * 60 + 41) * 1000L))
        assertEquals("1:02:03", TimeText.clock((3600 + 123) * 1000L))
    }
}

class PressCounterTest {

    @Test
    fun fivePressesWithinThreeSecondsFiresOnce() {
        val c = PressCounter()
        val fired = (0..4).map { c.press(it * 500L) }
        assertEquals(listOf(false, false, false, false, true), fired)
        // 按够以后重新数
        assertFalse(c.press(2_500))
    }

    @Test
    fun slowPressesNeverFire() {
        val c = PressCounter()
        // 每 1 秒按一下，任何 3 秒里最多 4 下
        assertTrue((0..20).none { c.press(it * 1_000L) })
    }

    @Test
    fun oldPressesExpire() {
        val c = PressCounter()
        c.press(0); c.press(100); c.press(200)
        // 过了 3 秒，前面三下作废，要重新按满 5 下
        assertFalse(c.press(5_000))
        assertFalse(c.press(5_100))
        assertFalse(c.press(5_200))
        assertFalse(c.press(5_300))
        assertTrue(c.press(5_400))
    }
}
