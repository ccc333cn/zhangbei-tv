package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootPickerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(path: String) = File(tmp.root, path).apply { mkdirs() }
    private fun names(roots: List<File>) = roots.map { it.relativeTo(tmp.root).path }

    @Test
    fun containerIsReplacedByDeviceFolders() {
        // 老电视：/mnt/usb/sda1/剧名/…
        dir("mnt/usb/sda1/某某电视剧")
        dir("mnt/usb/sdb1/另一部剧")
        assertEquals(listOf("mnt/usb/sda1", "mnt/usb/sdb1"), names(RootPicker.pick(listOf(File(tmp.root, "mnt/usb")))))
    }

    @Test
    fun driveMountedDirectlyIsKept() {
        // 硬盘直接挂在 /mnt/usb，下面就是剧名文件夹
        dir("mnt/usb/某某电视剧/Season 1")
        dir("storage/0968-0D0C/美剧")
        val picked = RootPicker.pick(listOf(File(tmp.root, "mnt/usb"), File(tmp.root, "storage/0968-0D0C")))
        assertEquals(listOf("mnt/usb", "storage/0968-0D0C"), names(picked))
    }

    @Test
    fun outerFolderDroppedWhenItContainsAnotherRoot() {
        dir("mnt/usb/sda1/剧")
        // /mnt/usb 和 /mnt/usb/sda1 都被别的途径找到了
        val picked = RootPicker.pick(listOf(File(tmp.root, "mnt/usb"), File(tmp.root, "mnt/usb/sda1")))
        assertEquals(listOf("mnt/usb/sda1"), names(picked))
    }

    @Test
    fun showFoldersAreNotMistakenForDevices() {
        dir("usb/Friends/Season 1")
        dir("usb/三体")
        assertEquals(listOf("usb"), names(RootPicker.pick(listOf(File(tmp.root, "usb")))))
        assertEquals(true, RootPicker.isDeviceName("sda1"))
        assertEquals(true, RootPicker.isDeviceName("0968-0D0C"))
        assertEquals(false, RootPicker.isDeviceName("Season 1"))
        assertEquals(false, RootPicker.isDeviceName("某某电视剧"))
    }
}
