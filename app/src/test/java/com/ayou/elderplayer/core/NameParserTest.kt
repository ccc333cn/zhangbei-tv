package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameParserTest {

    @Test
    fun naturalOrderPutsSmallNumbersFirst() {
        val names = listOf("第10集.mp4", "第2集.mp4", "第1集.mp4", "S01E10.mkv", "S01E02.mkv", "10.mp4", "02.mp4", "1.mp4")
        val sorted = names.sortedWith(NaturalOrder)
        assertEquals(listOf("1.mp4", "02.mp4", "10.mp4", "S01E02.mkv", "S01E10.mkv", "第1集.mp4", "第2集.mp4", "第10集.mp4"), sorted)
        assertTrue(NaturalOrder.compare("第2集", "第10集") < 0)
        assertTrue(NaturalOrder.compare("S01E02", "S01E10") < 0)
        assertTrue(NaturalOrder.compare("ep9", "EP10") < 0)
    }

    @Test
    fun naturalOrderIsTotalAndStable() {
        // 只差前导零、大小写、全角数字时也要有确定顺序
        assertTrue(NaturalOrder.compare("02", "2") != 0)
        assertEquals(0, NaturalOrder.compare("a", "a"))
        assertTrue(NaturalOrder.compare("第２集", "第10集") < 0)
        val a = listOf("B剧", "a剧", "A剧", "b剧").sortedWith(NaturalOrder)
        val b = listOf("b剧", "A剧", "B剧", "a剧").sortedWith(NaturalOrder)
        assertEquals(a, b)
    }

    @Test
    fun episodeNumberCommonPatterns() {
        assertEquals(2, NameParser.episodeNumber("某某电视剧.S01E02.1080p.WEB-DL.H265.AAC.mkv"))
        assertEquals(10, NameParser.episodeNumber("s02e10.mkv"))
        assertEquals(5, NameParser.episodeNumber("Show.1x05.mp4"))
        assertEquals(2, NameParser.episodeNumber("第2集.mp4"))
        assertEquals(12, NameParser.episodeNumber("某某电视剧 第十二集.mp4"))
        assertEquals(20, NameParser.episodeNumber("第二十集.mp4"))
        assertEquals(36, NameParser.episodeNumber("第三十六集.mp4"))
        assertEquals(7, NameParser.episodeNumber("某某剧07集.mp4"))
        assertEquals(3, NameParser.episodeNumber("EP03.mp4"))
        assertEquals(3, NameParser.episodeNumber("Show.E03.2160p.mkv"))
        assertEquals(2, NameParser.episodeNumber("02.mp4"))
        assertEquals(15, NameParser.episodeNumber("某某电视剧（2026）15.mkv"))
        assertEquals(8, NameParser.episodeNumber("[字幕组] 某某电视剧 08 [1080p].mp4"))
        assertEquals(4, NameParser.episodeNumber("某某电视剧.2026.04.1080p.x264.AAC.5.1.mkv"))
    }

    @Test
    fun showTitleDigitsAreIgnored() {
        assertEquals(1, NameParser.episodeNumber("三体2 01.mp4", "三体2"))
        assertEquals(6, NameParser.episodeNumber("三体2.06.mp4", "三体2（2026）"))
    }

    @Test
    fun episodeNumberUnknown() {
        assertNull(NameParser.episodeNumber("花絮.mp4"))
        assertNull(NameParser.episodeNumber("预告片.mkv"))
        assertNull(NameParser.episodeNumber("片段 3 和 4.mp4"))
    }

    @Test
    fun seasonFolders() {
        assertEquals(1, NameParser.seasonNumber("Season 1"))
        assertEquals(2, NameParser.seasonNumber("Season02"))
        assertEquals(1, NameParser.seasonNumber("S01"))
        assertEquals(3, NameParser.seasonNumber("第三季"))
        assertEquals(2, NameParser.seasonNumber("某某电视剧 第2季"))
        assertEquals(0, NameParser.seasonNumber("Specials"))
        assertEquals(0, NameParser.seasonNumber("番外"))
        assertNull(NameParser.seasonNumber("其他"))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(1, NameParser.parseNumber("一"))
        assertEquals(10, NameParser.parseNumber("十"))
        assertEquals(15, NameParser.parseNumber("十五"))
        assertEquals(40, NameParser.parseNumber("四十"))
        assertEquals(2, NameParser.parseNumber("两"))
        assertEquals(105, NameParser.parseNumber("一百零五"))
        assertNull(NameParser.parseNumber("abc"))
    }
}
