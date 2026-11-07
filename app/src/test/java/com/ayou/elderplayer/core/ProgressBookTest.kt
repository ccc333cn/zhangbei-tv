package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ProgressBookTest {

    private fun show(name: String, vararg eps: String) =
        Show(name, File("/usb/$name"), eps.map { Episode(File("/usb/$name/$it"), it, it) })

    private val a = show("A", "1.mp4", "2.mp4", "3.mp4")
    private val b = show("B", "1.mp4", "2.mp4", "3.mp4", "4.mp4")
    private val c = show("C", "1.mp4")

    @Test
    fun newShowStartsAtBeginning() {
        val book = ProgressBook(MapStore())
        assertEquals(ProgressBook.Resume(0, 0), book.resume(a))
        assertNull(book.lastShow)
    }

    @Test
    fun eachShowKeepsItsOwnProgress() {
        val store = MapStore()
        val book = ProgressBook(store)
        book.save(b, 1, 600_000, 2_700_000)   // B 第 2 集 10 分钟
        book.save(c, 0, 30_000, 2_700_000)
        book.lastShow = "C"

        // 换回 B，还是 10 分钟
        assertEquals(ProgressBook.Resume(1, 600_000), book.resume(b))
        assertEquals(ProgressBook.Resume(0, 30_000), book.resume(c))

        // 重开 App（新对象，同一个存储）也一样
        val again = ProgressBook(store)
        assertEquals(ProgressBook.Resume(1, 600_000), again.resume(b))
        assertEquals("C", again.lastShow)
        assertEquals(2, again.startShowIndex(listOf(a, b, c)))
    }

    @Test
    fun episodeRememberedByPathNotIndex() {
        val book = ProgressBook(MapStore())
        book.save(b, 2, 100_000, 2_000_000)                     // 3.mp4
        val withNewFirst = show("B", "0.mp4", "1.mp4", "2.mp4", "3.mp4", "4.mp4")
        assertEquals(ProgressBook.Resume(3, 100_000), book.resume(withNewFirst))
    }

    @Test
    fun missingFileFallsBackToSameIndexFromStart() {
        val book = ProgressBook(MapStore())
        book.save(b, 2, 100_000, 2_000_000)
        val renamed = show("B", "e1.mp4", "e2.mp4", "e3.mp4", "e4.mp4")
        assertEquals(ProgressBook.Resume(2, 0), book.resume(renamed))
        val shorter = show("B", "e1.mp4")
        assertEquals(ProgressBook.Resume(0, 0), book.resume(shorter))
    }

    @Test
    fun stoppedInEndCreditsGoesToNextEpisode() {
        val book = ProgressBook(MapStore())
        book.save(a, 0, 2_690_000, 2_700_000)
        assertEquals(ProgressBook.Resume(1, 0), book.resume(a))
        // 最后一集的片尾 → 回到第一集
        book.save(a, 2, 2_695_000, 2_700_000)
        assertEquals(ProgressBook.Resume(0, 0), book.resume(a))
    }

    @Test
    fun resetAfterWholeShowFinished() {
        val book = ProgressBook(MapStore())
        book.save(a, 2, 50_000, 2_700_000)
        book.reset(a)
        assertEquals(ProgressBook.Resume(0, 0), book.resume(a))
    }

    @Test
    fun lastShowMissingStartsFromFirst() {
        val book = ProgressBook(MapStore())
        book.lastShow = "已经删掉的剧"
        assertEquals(0, book.startShowIndex(listOf(a, b)))
    }

    @Test
    fun showSwitchWrapsAround() {
        assertEquals(1, ProgressBook.wrap(0, 1, 3))
        assertEquals(0, ProgressBook.wrap(2, 1, 3))   // C 往下回到 A
        assertEquals(2, ProgressBook.wrap(0, -1, 3))  // A 往上到 C
        assertEquals(0, ProgressBook.wrap(0, 1, 1))
        assertEquals(0, ProgressBook.wrap(0, 1, 0))
    }
}
