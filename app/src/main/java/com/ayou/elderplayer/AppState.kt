package com.ayou.elderplayer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.ayou.elderplayer.core.HealthBook
import com.ayou.elderplayer.core.KeyValueStore
import com.ayou.elderplayer.core.PlayStats
import com.ayou.elderplayer.core.RootPicker
import com.ayou.elderplayer.core.ProgressBook
import com.ayou.elderplayer.core.Show
import java.io.File

/** 播放页和维护页共用的状态。 */
object AppState {
    @Volatile var shows: List<Show> = emptyList()
    @Volatile var roots: List<File> = emptyList()
    var nowShow: String? = null
    var nowLabel: String? = null
    /** 正在用的解码器名字，维护页显示 */
    @Volatile var videoDecoder: String? = null
    @Volatile var audioDecoder: String? = null

    private var book: ProgressBook? = null
    private var health: HealthBook? = null
    private var stats: PlayStats? = null

    fun book(ctx: Context): ProgressBook =
        book ?: ProgressBook(PrefsStore(ctx.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)))
            .also { book = it }

    fun health(ctx: Context): HealthBook =
        health ?: HealthBook(PrefsStore(ctx.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)))
            .also { health = it }

    fun stats(ctx: Context): PlayStats =
        stats ?: PlayStats().also { st ->
            runCatching { statsFile(ctx).takeIf { it.exists() }?.readText()?.let(st::load) }
            stats = st
        }

    fun saveStats(ctx: Context) {
        val st = stats ?: return
        runCatching { statsFile(ctx).writeText(st.serialize()) }
    }

    private fun statsFile(ctx: Context) = File(ctx.filesDir, "stats.txt")

    fun hasStorageAccess(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        if (Build.VERSION.SDK_INT < 23) return true
        return ctx.checkSelfPermission(runtimeStoragePermission()) == PackageManager.PERMISSION_GRANTED
    }

    fun runtimeStoragePermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
}

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** 找移动硬盘挂在哪。各家电视路径不一样，几种办法一起用，按真实路径去重。 */
object StorageRoots {

    /** 电视内部存储里的备用位置，模拟器测试也用它。 */
    fun internalFallback(): File = File(Environment.getExternalStorageDirectory(), "TVShows")

    fun find(ctx: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        fun add(f: File?) {
            if (f == null || !f.isDirectory || f.listFiles() == null) return
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (key !in out) out[key] = f // putIfAbsent 要安卓 7，老电视上会崩
        }

        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (Build.VERSION.SDK_INT >= 24) {
            sm?.storageVolumes?.forEach { v ->
                if (v.isPrimary) return@forEach
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else volumePath(v)
                add(dir)
            }
        } else if (sm != null) {
            // 安卓 5、6：系统隐藏接口 getVolumeList()，挂没挂上看目录能不能列出来
            runCatching {
                val list = sm.javaClass.getMethod("getVolumeList").invoke(sm) as Array<*>
                list.forEach { v ->
                    // 内置存储（sdcard0 之类）也在列表里，不算移动硬盘
                    if (v == null || flag(v, "isPrimary") || flag(v, "isEmulated")) return@forEach
                    add(volumePath(v))
                }
            }
        }
        File("/storage").listFiles()?.forEach {
            if (it.name !in setOf("emulated", "self", "sdcard0", "sdcard", "legacy")) add(it)
        }
        // 老电视（MStar、MediaTek 方案）常挂在 /mnt/usb/sda1、/mnt/sda1、/mnt/usb_xxx，
        // 容器还是硬盘本身交给 RootPicker 判断
        for (p in listOf("/mnt/usbhost", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/udisk")) add(File(p))
        File("/mnt").listFiles()?.forEach { if (RootPicker.isDeviceName(it.name)) add(it) }
        // 内部存储本身不算移动硬盘，免得把手机式的 Download、Movies 当成剧
        val internal = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
        if (internal != null) out.remove(internal)
        return RootPicker.pick(out.values.toList()) + listOfNotNull(internalFallback().takeIf { it.isDirectory })
    }

    private fun flag(v: Any, method: String): Boolean =
        runCatching { v.javaClass.getMethod(method).invoke(v) as Boolean }.getOrDefault(false)

    private fun volumePath(v: Any): File? = runCatching {
        val m = v.javaClass.getMethod("getPathFile")
        m.invoke(v) as? File
    }.recoverCatching {
        File(v.javaClass.getMethod("getPath").invoke(v) as String)
    }.getOrNull()
}
