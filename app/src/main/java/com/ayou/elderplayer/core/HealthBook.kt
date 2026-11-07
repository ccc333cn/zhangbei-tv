package com.ayou.elderplayer.core

/**
 * 记哪些集放不了、哪些剧被跳过。
 * - 放不了的集记下来，以后直接跳过，不用每次再卡一遍；
 * - 同一部剧连续 [blockAfter] 集放不了，整部剧跳过；中间有一集正常放了就重新数；
 * - 家人在维护页可以一键恢复。
 */
class HealthBook(private val store: KeyValueStore, private val blockAfter: Int = 3) {

    private val bad = LinkedHashMap<String, LinkedHashSet<String>>()
    private val fails = LinkedHashMap<String, Int>()
    private val blocked = LinkedHashSet<String>()

    init {
        load(store.get(KEY) ?: "")
    }

    fun isBlocked(show: String) = show in blocked
    fun blockedShows(): Set<String> = blocked
    fun badEpisodes(show: String): Set<String> = bad[show] ?: emptySet()
    fun badCount(): Int = bad.values.sumOf { it.size }
    fun consecutiveFailures(show: String) = fails[show] ?: 0
    fun isEmpty() = blocked.isEmpty() && bad.isEmpty()

    /** 记一集放不了。返回 true 表示这部剧刚刚被跳过。 */
    fun recordFailure(show: String, relPath: String): Boolean {
        bad.getOrPut(show) { LinkedHashSet() }.add(relPath)
        val n = (fails[show] ?: 0) + 1
        fails[show] = n
        val nowBlocked = n >= blockAfter && blocked.add(show)
        save()
        return nowBlocked
    }

    /** 这部剧有一集正常放了：连续失败清零。 */
    fun recordHealthy(show: String) {
        if (fails.remove(show) != null) save()
    }

    fun clearAll() {
        bad.clear()
        fails.clear()
        blocked.clear()
        save()
    }

    /** 去掉被跳过的剧和放不了的集；一集都不剩的剧也去掉。 */
    fun playable(shows: List<Show>): List<Show> = shows
        .filter { it.name !in blocked }
        .map { s ->
            val skip = bad[s.name]
            if (skip.isNullOrEmpty()) s else s.copy(episodes = s.episodes.filter { it.relPath !in skip })
        }
        .filter { it.episodes.isNotEmpty() }

    private fun save() {
        val text = buildString {
            for (s in blocked) append("B\t").append(s).append('\n')
            for ((s, n) in fails) append("F\t").append(s).append('\t').append(n).append('\n')
            for ((s, eps) in bad) for (e in eps) append("X\t").append(s).append('\t').append(e).append('\n')
        }
        store.put(KEY, text.ifEmpty { null })
    }

    private fun load(text: String) {
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            when {
                p.size == 2 && p[0] == "B" -> blocked += p[1]
                p.size == 3 && p[0] == "F" -> p[2].toIntOrNull()?.let { fails[p[1]] = it }
                p.size == 3 && p[0] == "X" -> bad.getOrPut(p[1]) { LinkedHashSet() }.add(p[2])
            }
        }
    }

    companion object {
        const val KEY = "health"
    }
}
