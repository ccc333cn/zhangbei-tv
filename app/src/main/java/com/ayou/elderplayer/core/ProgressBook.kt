package com.ayou.elderplayer.core

/** 最简单的键值存储，安卓上用 SharedPreferences 实现，测试里用 HashMap。 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MapStore(val map: MutableMap<String, String> = HashMap()) : KeyValueStore {
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * 每部剧各记各的：看到第几集、第几毫秒。另外记住最后看的是哪部剧。
 * 集按相对路径记，硬盘里后来加了文件也不会错位；文件找不到了再退回按序号。
 */
class ProgressBook(private val store: KeyValueStore) {

    data class Resume(val index: Int, val positionMs: Long)

    var lastShow: String?
        get() = store.get(KEY_LAST)
        set(value) = store.put(KEY_LAST, value)

    fun save(show: Show, index: Int, positionMs: Long, durationMs: Long) {
        val ep = show.episodes.getOrNull(index) ?: return
        val pos = positionMs.coerceAtLeast(0)
        val dur = durationMs.coerceAtLeast(0)
        store.put(key(show.name), "$pos\t$dur\t$index\t${ep.relPath}")
    }

    fun resume(show: Show): Resume {
        val raw = store.get(key(show.name)) ?: return Resume(0, 0)
        val parts = raw.split('\t', limit = 4)
        if (parts.size < 4) return Resume(0, 0)
        val pos = parts[0].toLongOrNull() ?: 0
        val dur = parts[1].toLongOrNull() ?: 0
        val savedIndex = parts[2].toIntOrNull() ?: 0
        val relPath = parts[3]

        var index = show.episodes.indexOfFirst { it.relPath == relPath }
        var position = pos
        if (index < 0) {
            // 文件改名或删了：退回到原来的序号，从头放
            index = savedIndex.coerceIn(0, show.episodes.lastIndex.coerceAtLeast(0))
            position = 0
        }
        // 上次停在片尾字幕那里，直接从下一集开始
        if (dur > 0 && position >= dur - NEAR_END_MS) {
            index += 1
            position = 0
            if (index > show.episodes.lastIndex) index = 0
        }
        return Resume(index, position)
    }

    /** 一部剧整部放完：下次轮到它从第一集开始。 */
    fun reset(show: Show) {
        store.put(key(show.name), null)
    }

    /** 开机先放哪部：上次看的那部，找不到就第一部。 */
    fun startShowIndex(shows: List<Show>): Int {
        val name = lastShow ?: return 0
        return shows.indexOfFirst { it.name == name }.coerceAtLeast(0)
    }

    companion object {
        const val KEY_LAST = "last_show"
        const val NEAR_END_MS = 15_000L
        fun key(showName: String) = "p:$showName"

        /** 上下键换剧：首尾相接，一直循环。 */
        fun wrap(index: Int, delta: Int, size: Int): Int {
            if (size <= 0) return 0
            return ((index + delta) % size + size) % size
        }
    }
}
