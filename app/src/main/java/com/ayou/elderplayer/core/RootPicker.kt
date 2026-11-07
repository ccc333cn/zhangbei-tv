package com.ayou.elderplayer.core

import java.io.File

/**
 * 从一堆可能是硬盘的目录里挑出真正的硬盘根目录。
 *
 * 老电视常见 /mnt/usb/sda1 这种两层：/mnt/usb 只是挂载点的容器，
 * 要是把它也当硬盘，sda1 就会被当成一部剧。所以：
 * - 子目录名像设备名（sda1、0968-0D0C、usb1…）的，是容器，换成这些子目录；
 * - 否则它自己就是硬盘根目录；
 * - 最后，某个根目录里面还包着另一个根目录的，去掉外面那个。
 */
object RootPicker {

    private val DEVICE = Regex("""(?i)sd[a-z]\d*|[0-9a-f]{4}-[0-9a-f]{4}|[0-9a-f]{8,}|usb\w*|udisk\w*|disk\d*|part\d*""")

    fun isDeviceName(name: String) = DEVICE.matches(name)

    fun pick(candidates: List<File>): List<File> {
        val expanded = LinkedHashMap<String, File>()
        fun put(f: File) {
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (key !in expanded) expanded[key] = f
        }
        for (dir in candidates) {
            val devices = dir.listFiles()?.filter { it.isDirectory && isDeviceName(it.name) && it.listFiles() != null }
                ?.sortedWith(compareBy(NaturalOrder) { it.name })
                ?: continue
            if (devices.isEmpty()) put(dir) else devices.forEach(::put)
        }
        val keys = expanded.keys.toList()
        return expanded.filterKeys { k -> keys.none { other -> other != k && other.startsWith("$k/") } }.values.toList()
    }
}
