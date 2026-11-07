package com.ayou.elderplayer.core

import java.io.File

data class Episode(
    val file: File,
    /** 相对剧名文件夹的路径，用来记进度，换硬盘挂载点也不受影响。 */
    val relPath: String,
    /** 画面上显示的「第 12 集」或「第 2 季 · 第 5 集」。 */
    val label: String,
    val subtitles: List<File> = emptyList(),
)

data class Show(
    /** 第一层文件夹名，也是记进度用的 key。 */
    val name: String,
    val dir: File,
    val episodes: List<Episode>,
)

/**
 * 扫描移动硬盘：第一层是剧名文件夹，下面要么直接是视频，要么还有一层（或几层）季文件夹。
 */
class LibraryScanner(private val maxDepth: Int = 3) {

    fun scan(roots: List<File>): List<Show> {
        val seen = HashSet<String>()
        val shows = ArrayList<Show>()
        for (root in roots) {
            val dirs = root.listFiles()?.filter { it.isDirectory && !isIgnored(it.name) } ?: continue
            for (dir in dirs) {
                val show = scanShow(dir) ?: continue
                if (seen.add(show.name)) shows += show
            }
        }
        return shows.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    fun scanShow(dir: File): Show? {
        val groups = ArrayList<Group>()
        val direct = folderEpisodes(dir, dir.name)
        if (direct.isNotEmpty()) groups += Group(null, null, direct)

        val subdirs = listDirs(dir)
        val seasonGroups = subdirs.mapNotNull { sub ->
            val eps = collect(sub, dir.name, 1)
            if (eps.isEmpty()) null else Group(sub.name, NameParser.seasonNumber(sub.name), eps)
        }.sortedWith(SEASON_ORDER)
        groups += seasonGroups
        if (groups.isEmpty()) return null

        val multiSeason = groups.size > 1 ||
            groups[0].items.mapNotNull { it.seasonInName }.distinct().size > 1

        val episodes = ArrayList<Episode>()
        for (g in groups) {
            g.items.forEachIndexed { i, item ->
                val number = item.parsedEpisode ?: (i + 1)
                val season = item.seasonInName ?: g.season
                val prefix = when {
                    !multiSeason -> null
                    season == 0 -> "特别篇"
                    season != null -> "第 $season 季"
                    else -> g.folderName
                }
                val label = if (prefix == null) "第 $number 集" else "$prefix · 第 $number 集"
                episodes += Episode(
                    file = item.file,
                    relPath = item.file.relativeTo(dir).path,
                    label = label,
                    subtitles = subtitlesFor(item.file),
                )
            }
        }
        return Show(dir.name, dir, episodes)
    }

    private class Item(val file: File, val parsedEpisode: Int?, val seasonInName: Int?)

    private class Group(val folderName: String?, val season: Int?, val items: List<Item>)

    /** 一个文件夹里的视频，加上更深层文件夹里的（按文件夹名自然排序接在后面）。 */
    private fun collect(dir: File, showName: String, depth: Int): List<Item> {
        val items = ArrayList(folderEpisodes(dir, showName))
        if (depth < maxDepth) {
            for (sub in listDirs(dir).sortedWith(compareBy(NaturalOrder) { it.name })) {
                items += collect(sub, showName, depth + 1)
            }
        }
        return items
    }

    /**
     * 同一个文件夹里的视频怎么排：每个文件都认得出集数，就按集数排；
     * 有一个认不出，整个文件夹退回按名称自然排序，免得认错的混进去打乱顺序。
     */
    private fun folderEpisodes(dir: File, showName: String): List<Item> {
        val files = dir.listFiles()?.filter { it.isFile && isVideo(it.name) && !isIgnored(it.name) && !isSample(it.name) }
            ?: return emptyList()
        if (files.isEmpty()) return emptyList()
        val parsed = files.map { it to NameParser.episodeNumber(it.name, showName) }
        val seasons = files.map { NameParser.seasonInFileName(it.name) }
        // 季数只有每个文件都写了才参与排序，免得「01.mkv」和「S01E03.mkv」混放时被拆开
        val useSeason = seasons.all { it != null }
        return if (parsed.all { it.second != null }) {
            files.indices
                .sortedWith(
                    compareBy<Int> { if (useSeason) seasons[it]!! else 0 }
                        .thenBy { parsed[it].second!! }
                        .thenComparator { a, b -> NaturalOrder.compare(files[a].name, files[b].name) }
                )
                .map { Item(files[it], parsed[it].second, seasons[it]) }
        } else {
            files.indices
                .sortedWith { a, b -> NaturalOrder.compare(files[a].name, files[b].name) }
                .map { Item(files[it], null, seasons[it]) }
        }
    }

    private fun listDirs(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory && !isIgnored(it.name) && !isSample(it.name) } ?: emptyList()

    private fun subtitlesFor(video: File): List<File> {
        val base = NameParser.stripExtension(video.name)
        return video.parentFile?.listFiles()
            ?.filter { it.isFile && isSubtitle(it.name) && it.name.startsWith("$base.") && !isIgnored(it.name) }
            ?.sortedWith(compareBy(NaturalOrder) { it.name })
            ?: emptyList()
    }

    companion object {
        val VIDEO_EXT = setOf(
            "mkv", "mp4", "m4v", "mov", "ts", "m2ts", "mts", "avi", "flv", "webm", "mpg", "mpeg", "3gp",
        )
        val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt")

        private val SYSTEM_DIRS = setOf(
            "system volume information", "lost.dir", "found.000", "recycler", "android", "dcim",
        )

        /** 季文件夹的顺序：认得出季数的按季数排，认不出的按名称排在后面，特别篇放最后。 */
        private val SEASON_ORDER = Comparator<Group> { a, b ->
            fun rank(g: Group) = when {
                g.season == null -> 1
                g.season == 0 -> 2
                else -> 0
            }
            val r = rank(a).compareTo(rank(b))
            if (r != 0) return@Comparator r
            val s = (a.season ?: 0).compareTo(b.season ?: 0)
            if (s != 0) return@Comparator s
            NaturalOrder.compare(a.folderName ?: "", b.folderName ?: "")
        }

        fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
        fun isVideo(name: String) = extension(name) in VIDEO_EXT
        fun isSubtitle(name: String) = extension(name) in SUBTITLE_EXT

        /** 隐藏文件（含 Mac 拷贝时留下的 ._ 文件）、回收站和系统文件夹。 */
        fun isIgnored(name: String): Boolean =
            name.startsWith(".") || name.startsWith("$") || name.lowercase() in SYSTEM_DIRS

        private val SAMPLE = Regex("""(?i)(^|[ ._\-\[(])sample($|[ ._\-\])])""")
        fun isSample(name: String) = SAMPLE.containsMatchIn(NameParser.stripExtension(name))
    }
}
