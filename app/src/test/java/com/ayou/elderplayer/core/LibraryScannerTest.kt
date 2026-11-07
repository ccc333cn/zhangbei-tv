package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(path: String): File {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeText("x")
        return f
    }

    private fun names(show: Show) = show.episodes.map { it.relPath }

    @Test
    fun flatShowSortedByEpisode() {
        listOf("第10集.mp4", "第2集.mp4", "第1集.mp4", "第11集.mp4").forEach { touch("某某电视剧（2026）/$it") }
        val shows = LibraryScanner().scan(listOf(tmp.root))
        assertEquals(1, shows.size)
        assertEquals("某某电视剧（2026）", shows[0].name)
        assertEquals(listOf("第1集.mp4", "第2集.mp4", "第10集.mp4", "第11集.mp4"), names(shows[0]))
        assertEquals("第 10 集", shows[0].episodes[2].label)
    }

    @Test
    fun webDlNamesSortByEpisodeNotByYearOrResolution() {
        // 用户实际的命名：文件名里只有 SxxEyy，后面跟年份、分辨率、编码
        val eps = listOf(30, 2, 10, 1, 11, 3, 29)
        eps.forEach { touch("某某电视剧/S01E%02d.2026.2160p.WEB-DL.H265.AAC.mp4".format(it)) }
        val show = LibraryScanner().scanShow(File(tmp.root, "某某电视剧"))!!
        assertEquals(eps.sorted().map { "S01E%02d.2026.2160p.WEB-DL.H265.AAC.mp4".format(it) }, names(show))
        assertEquals(listOf("第 1 集", "第 2 集", "第 3 集", "第 10 集", "第 11 集", "第 29 集", "第 30 集"),
            show.episodes.map { it.label })
    }

    @Test
    fun differentNamingStillSortsByEpisode() {
        // 同一个文件夹里写法不统一，也按集数排
        listOf("剧名.E10.mkv", "剧名 第2集.mkv", "S01E03.mkv", "01.mkv").forEach { touch("剧名/$it") }
        val show = LibraryScanner().scanShow(File(tmp.root, "剧名"))!!
        assertEquals(listOf("01.mkv", "剧名 第2集.mkv", "S01E03.mkv", "剧名.E10.mkv"), names(show))
    }

    @Test
    fun unparsedFileMakesFolderFallBackToNaturalOrder() {
        listOf("02.mp4", "10.mp4", "1.mp4", "花絮.mp4").forEach { touch("剧/$it") }
        val show = LibraryScanner().scanShow(File(tmp.root, "剧"))!!
        assertEquals(listOf("1.mp4", "02.mp4", "10.mp4", "花絮.mp4"), names(show))
        // 退回自然排序后，集数按位置算
        assertEquals("第 4 集", show.episodes[3].label)
    }

    @Test
    fun seasonFoldersAreMergedInOrder() {
        listOf("S02E01.mkv", "S02E02.mkv").forEach { touch("美剧/Season 2/$it") }
        listOf("S01E10.mkv", "S01E02.mkv", "S01E01.mkv").forEach { touch("美剧/Season 1/$it") }
        listOf("S10E01.mkv").forEach { touch("美剧/Season 10/$it") }
        touch("美剧/Specials/S00E01.mkv")
        val show = LibraryScanner().scanShow(File(tmp.root, "美剧"))!!
        assertEquals(
            listOf(
                "Season 1/S01E01.mkv", "Season 1/S01E02.mkv", "Season 1/S01E10.mkv",
                "Season 2/S02E01.mkv", "Season 2/S02E02.mkv",
                "Season 10/S10E01.mkv",
                "Specials/S00E01.mkv",
            ),
            names(show),
        )
        assertEquals("第 1 季 · 第 10 集", show.episodes[2].label)
        assertEquals("第 2 季 · 第 1 集", show.episodes[3].label)
        assertEquals("特别篇 · 第 1 集", show.episodes[6].label)
    }

    @Test
    fun chineseSeasonFoldersAndS01Folders() {
        touch("剧A/第二季/01.mp4")
        touch("剧A/第一季/01.mp4")
        touch("剧A/第一季/02.mp4")
        touch("剧A/第十季/01.mp4")
        val a = LibraryScanner().scanShow(File(tmp.root, "剧A"))!!
        assertEquals(listOf("第一季/01.mp4", "第一季/02.mp4", "第二季/01.mp4", "第十季/01.mp4"), names(a))

        touch("剧B/S02/第1集.mp4")
        touch("剧B/S01/第1集.mp4")
        val b = LibraryScanner().scanShow(File(tmp.root, "剧B"))!!
        assertEquals(listOf("S01/第1集.mp4", "S02/第1集.mp4"), names(b))
    }

    @Test
    fun bothLayoutsInOneDrive() {
        touch("B剧/第2集.mp4")
        touch("B剧/第1集.mp4")
        touch("A剧/Season 1/S01E01.mkv")
        touch("C剧/S01/01.mkv")
        val shows = LibraryScanner().scan(listOf(tmp.root))
        assertEquals(listOf("A剧", "B剧", "C剧"), shows.map { it.name })
        // 只有一季时不显示「第 1 季」
        assertEquals("第 1 集", shows[0].episodes[0].label)
    }

    @Test
    fun junkIsSkipped() {
        touch("剧/第1集.mp4")
        touch("剧/._第1集.mp4")          // Mac 拷贝留下的
        touch("剧/第1集.srt")
        touch("剧/第10集.srt")
        touch("剧/sample.mkv")
        touch("剧/封面.jpg")
        touch("剧/Sample/x.mkv")
        touch("System Volume Information/a.mp4")
        touch("\$RECYCLE.BIN/a/b.mp4")
        touch("空文件夹/说明.txt")
        touch("根目录视频.mp4")
        val shows = LibraryScanner().scan(listOf(tmp.root))
        assertEquals(listOf("剧"), shows.map { it.name })
        assertEquals(listOf("第1集.mp4"), names(shows[0]))
        assertEquals(listOf("第1集.srt"), shows[0].episodes[0].subtitles.map { it.name })
    }

    @Test
    fun sameShowOnTwoDrivesKeepsFirst() {
        val d1 = tmp.newFolder("usb1")
        val d2 = tmp.newFolder("usb2")
        File(d1, "剧/1.mp4").apply { parentFile!!.mkdirs(); writeText("x") }
        File(d2, "剧/1.mp4").apply { parentFile!!.mkdirs(); writeText("x") }
        File(d2, "另一部/1.mp4").apply { parentFile!!.mkdirs(); writeText("x") }
        val shows = LibraryScanner().scan(listOf(d1, d2))
        assertEquals(listOf("剧", "另一部"), shows.map { it.name })
        assertEquals(d1, shows[0].dir.parentFile)
    }

    @Test
    fun scanIsStableAcrossRuns() {
        listOf("第3集.mp4", "第1集.mp4", "第12集.mp4", "第2集.mp4").forEach { touch("剧/$it") }
        val first = names(LibraryScanner().scanShow(File(tmp.root, "剧"))!!)
        repeat(5) { assertEquals(first, names(LibraryScanner().scanShow(File(tmp.root, "剧"))!!)) }
    }

    @Test
    fun emptyShowFolderIsNull() {
        tmp.newFolder("空")
        assertNull(LibraryScanner().scanShow(File(tmp.root, "空")))
    }
}
