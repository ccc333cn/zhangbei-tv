package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackWatchdogTest {

    /** 每秒喂一次，返回第一个不是 Ok 的结果和它出现在第几秒。 */
    private fun run(
        w: PlaybackWatchdog,
        seconds: Int,
        playing: (Int) -> Boolean = { true },
        pos: (Int) -> Long,
        hasVideo: Boolean = true,
        frames: (Int) -> Long = { it.toLong() * 25 },
    ): Pair<Int, PlaybackWatchdog.Result>? {
        for (t in 0..seconds) {
            val r = w.sample(t * 1000L, playing(t), pos(t), hasVideo, frames(t))
            if (r != PlaybackWatchdog.Result.Ok) return t to r
        }
        return null
    }

    @Test
    fun normalPlaybackBecomesHealthy() {
        val r = run(PlaybackWatchdog(), 60, pos = { it * 1000L })
        assertEquals(20 to PlaybackWatchdog.Result.Healthy, r)
    }

    @Test
    fun stuckPositionIsStall() {
        // 放了 5 秒后进度不动了
        val r = run(PlaybackWatchdog(), 60, pos = { minOf(it, 5) * 1000L })
        assertEquals(25 to PlaybackWatchdog.Result.Stalled("卡住不动"), r)
    }

    @Test
    fun neverStartsIsStall() {
        // 一直在缓冲，进度一直是 0
        val r = run(PlaybackWatchdog(), 60, pos = { 0L })
        assertEquals(20 to PlaybackWatchdog.Result.Stalled("卡住不动"), r)
    }

    @Test
    fun pausedTimeDoesNotCount() {
        // 第 5 秒暂停，暂停了 100 秒再继续
        val w = PlaybackWatchdog()
        val r = run(w, 200, playing = { it < 5 || it > 105 }, pos = { t ->
            when {
                t < 5 -> t * 1000L
                t <= 105 -> 5000L
                else -> (t - 100) * 1000L
            }
        })
        assertEquals(PlaybackWatchdog.Result.Healthy, r?.second)
    }

    @Test
    fun frozenPictureWhileSoundPlays() {
        // 进度在走，画面从第 3 秒起没出新帧
        val r = run(PlaybackWatchdog(), 60, pos = { it * 1000L }, frames = { minOf(it, 3) * 25L })
        assertEquals(18 to PlaybackWatchdog.Result.Stalled("画面不动"), r)
    }

    @Test
    fun audioOnlyFileIsNotFrozen() {
        val r = run(PlaybackWatchdog(), 60, pos = { it * 1000L }, hasVideo = false, frames = { 0L })
        assertEquals(PlaybackWatchdog.Result.Healthy, r?.second)
    }

    @Test
    fun seekJumpIsNotFrozenPicture() {
        val w = PlaybackWatchdog()
        for (t in 0..4) w.sample(t * 1000L, true, t * 1000L, true, t * 25L)
        // 第 5 秒一下快进了 60 秒，这一秒画面还没跟上
        w.onSeek()
        assertEquals(PlaybackWatchdog.Result.Ok, w.sample(5000, true, 65_000, true, 100))
        assertEquals(PlaybackWatchdog.Result.Ok, w.sample(6000, true, 66_000, true, 125))
    }

    @Test
    fun reportsOnlyOnceUntilReset() {
        val w = PlaybackWatchdog()
        run(w, 30, pos = { 0L })
        assertEquals(PlaybackWatchdog.Result.Ok, w.sample(31_000, true, 0, true, 0))
        w.reset()
        val r = run(w, 30, pos = { 0L })
        assertEquals(PlaybackWatchdog.Result.Stalled("卡住不动"), r?.second)
    }
}

class HealthBookTest {

    private fun show(name: String, vararg eps: String) =
        Show(name, File("/usb/$name"), eps.map { Episode(File("/usb/$name/$it"), it, it) })

    @Test
    fun threeFailuresInARowBlocksShow() {
        val h = HealthBook(MapStore())
        assertFalse(h.recordFailure("A", "1.mkv"))
        assertFalse(h.recordFailure("A", "2.mkv"))
        assertTrue(h.recordFailure("A", "3.mkv"))
        assertTrue(h.isBlocked("A"))
        // 再记也不会重复报「刚被跳过」
        assertFalse(h.recordFailure("A", "4.mkv"))
    }

    @Test
    fun healthyEpisodeResetsTheCount() {
        val h = HealthBook(MapStore())
        h.recordFailure("A", "1.mkv")
        h.recordFailure("A", "2.mkv")
        h.recordHealthy("A")
        assertFalse(h.recordFailure("A", "5.mkv"))
        assertFalse(h.recordFailure("A", "6.mkv"))
        assertFalse(h.isBlocked("A"))
        assertEquals(4, h.badEpisodes("A").size)
    }

    @Test
    fun countsArePerShow() {
        val h = HealthBook(MapStore())
        h.recordFailure("A", "1.mkv")
        h.recordFailure("B", "1.mkv")
        h.recordFailure("A", "2.mkv")
        h.recordFailure("B", "2.mkv")
        assertFalse(h.isBlocked("A"))
        assertFalse(h.isBlocked("B"))
    }

    @Test
    fun playableSkipsBadEpisodesAndBlockedShows() {
        val h = HealthBook(MapStore())
        val a = show("A", "1.mkv", "2.mkv", "3.mkv")
        val b = show("B", "1.mkv", "2.mkv")
        val c = show("C", "1.mkv")
        h.recordFailure("A", "2.mkv")
        repeat(3) { h.recordFailure("B", "x$it") }
        h.recordFailure("C", "1.mkv")
        val p = h.playable(listOf(a, b, c))
        assertEquals(listOf("A"), p.map { it.name })               // B 被跳过，C 一集不剩
        assertEquals(listOf("1.mkv", "3.mkv"), p[0].episodes.map { it.relPath })
    }

    @Test
    fun survivesRestartAndCanBeCleared() {
        val store = MapStore()
        val h = HealthBook(store)
        repeat(3) { h.recordFailure("某某电视剧（2026）", "Season 1/S01E0$it.mkv") }
        h.recordFailure("美剧", "1.mkv")

        val again = HealthBook(store)
        assertTrue(again.isBlocked("某某电视剧（2026）"))
        assertEquals(1, again.consecutiveFailures("美剧"))
        assertEquals(4, again.badCount())

        again.clearAll()
        assertTrue(HealthBook(store).isEmpty())
    }
}
