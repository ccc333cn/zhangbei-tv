package com.ayou.elderplayer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.ayou.elderplayer.core.Episode
import com.ayou.elderplayer.core.HealthBook
import com.ayou.elderplayer.core.LibraryScanner
import com.ayou.elderplayer.core.PlaybackWatchdog
import com.ayou.elderplayer.core.PressCounter
import com.ayou.elderplayer.core.ProgressBook
import com.ayou.elderplayer.core.SeekStepper
import com.ayou.elderplayer.core.Show
import com.ayou.elderplayer.core.TimeText
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private enum class Screen { SCANNING, NO_PERMISSION, NO_MEDIA, ALL_BROKEN, PLAYING }

    private lateinit var ui: Ui
    private lateinit var book: ProgressBook
    private lateinit var health: HealthBook
    private val watchdog = PlaybackWatchdog()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val stepper = SeekStepper()

    private var player: ExoPlayer? = null
    private var shows: List<Show> = emptyList()
    private var showIndex = -1
    private var pendingIndex = -1
    private var screen = Screen.SCANNING
    private var scanning = false
    private var started = false
    private var askedPermission = false

    private var seekTarget = -1L
    private var lastSeekAt = 0L
    private val menuPresses = PressCounter()
    private var lastTickAt = 0L
    private var ticks = 0

    // ---- 界面 ----
    private lateinit var playerView: PlayerView
    private lateinit var pauseIcon: View
    private lateinit var seekBadge: LinearLayout
    private lateinit var seekIcon: ImageView
    private lateinit var seekText: TextView
    private lateinit var switchLayer: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var prevHint: TextView
    private lateinit var nextHint: TextView
    private lateinit var cardTop: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardEpisode: TextView
    private lateinit var cardSub: TextView
    private lateinit var info: LinearLayout
    private lateinit var titleRow: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoEpisode: TextView
    private lateinit var bar: BarView
    private lateinit var infoTime: TextView
    private lateinit var infoHint: TextView
    private lateinit var message: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var msgTitle: TextView
    private lateinit var msgSub: TextView
    private lateinit var msgFoot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        book = AppState.book(this)
        health = AppState.health(this)
        setContentView(buildLayout())
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerMountReceiver()
        handler.post(tick)
        if (!AppState.hasStorageAccess(this)) {
            showNoPermission()
            if (!askedPermission && Build.VERSION.SDK_INT >= 23) {
                askedPermission = true
                requestPermissions(arrayOf(AppState.runtimeStoragePermission()), REQ_STORAGE)
            }
            return
        }
        rescan()
    }

    override fun onStop() {
        started = false
        saveProgress()
        AppState.saveStats(this)
        handler.removeCallbacksAndMessages(null)
        // 上面把扫描线程回来的消息也清掉了，这里复位，不然以后再也不会扫描
        scanning = false
        runCatching { unregisterReceiver(mountReceiver) }
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 已经在前台时又按了主页键：给他看一眼现在在放什么
        if (screen == Screen.PLAYING) showInfo(4000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && AppState.hasStorageAccess(this)) rescan()
    }

    // ================= 扫描与硬盘挂载 =================

    private val retryScan = Runnable { if (started) rescan() }

    private fun rescan() {
        if (scanning || !started) return
        if (!AppState.hasStorageAccess(this)) {
            showNoPermission()
            return
        }
        scanning = true
        if (shows.isEmpty()) showMessage(Screen.SCANNING)
        io.execute {
            val roots = runCatching { StorageRoots.find(this) }.getOrDefault(emptyList())
            val found = runCatching { LibraryScanner().scan(roots) }.getOrDefault(emptyList())
            handler.post {
                scanning = false
                if (started) onScanned(roots, found)
            }
        }
    }

    private fun onScanned(roots: List<java.io.File>, found: List<Show>) {
        AppState.roots = roots
        AppState.shows = found
        handler.removeCallbacks(retryScan)
        if (found.isEmpty()) {
            shows = emptyList()
            releasePlayer()
            showMessage(Screen.NO_MEDIA)
            // 硬盘可能还在挂载，一直隔几秒再找一次；挂载广播来了也会马上找
            handler.postDelayed(retryScan, 3000)
            return
        }
        val playingName = shows.getOrNull(showIndex)?.name
        // 放不了的集、被跳过的剧不进播放列表
        val playable = health.playable(found)
        shows = playable
        if (playable.isEmpty()) {
            releasePlayer()
            showAllBroken()
            return
        }
        hideMessage()
        val keep = if (player != null && playingName != null) playable.indexOfFirst { it.name == playingName } else -1
        if (keep >= 0) {
            showIndex = keep
        } else {
            startShow(book.startShowIndex(playable), Reason.BOOT)
        }
    }

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_MEDIA_MOUNTED -> handler.postDelayed({ rescan() }, 800)
                else -> {
                    // 硬盘拔了：记下进度，停掉，回到「正在找硬盘」
                    saveProgress()
                    releasePlayer()
                    shows = emptyList()
                    showIndex = -1
                    handler.postDelayed({ rescan() }, 1000)
                }
            }
        }
    }

    private fun registerMountReceiver() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file") // 不加这一行收不到挂载广播
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mountReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(mountReceiver, f)
    }

    // ================= 播放 =================

    private enum class Reason { BOOT, SWITCH, AUTO_NEXT }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // 视频只走电视硬解；电视解不了的 AC3 / DTS 等音轨交给 ffmpeg
        val renderers = TvRenderersFactory(this)
        val tracks = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    // 老电视常把 4K H265 报成「超出解码能力」，其实硬解放得动：照样选硬解
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setPreferredAudioLanguages("zh", "chi", "zho", "cmn")
                    .setPreferredTextLanguages("zh", "chi", "zho")
                    .setSelectUndeterminedTextLanguage(true)
            )
        }
        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(tracks)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
        p.addListener(listener)
        // 记下实际用的解码器，维护页上能看出是硬解还是软解
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                AppState.videoDecoder = decoderName
                Log.i(TAG, "视频解码器 $decoderName")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                AppState.audioDecoder = decoderName
                Log.i(TAG, "音频解码器 $decoderName")
            }
        })
        // 调试版把播放器事件打到 logcat（选了哪个解码器、卡在哪），正式版不打
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            p.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        }
        playerView.player = p
        player = p
        return p
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startShow(index: Int, reason: Reason, finishedName: String? = null, note: String? = null) {
        val show = shows.getOrNull(index) ?: return
        showIndex = index
        pendingIndex = -1
        watchdog.reset()
        book.lastShow = show.name
        val r = book.resume(show)
        val p = ensurePlayer()
        p.setMediaItems(show.episodes.map(::mediaItem), r.index, r.positionMs)
        p.prepare()
        p.play()
        updateNow()

        hideInfo()
        when (reason) {
            Reason.BOOT -> showCard(index, r, null, 5000, note)
            Reason.SWITCH -> showCard(index, r, null, 2500, note)
            Reason.AUTO_NEXT -> showCard(index, r, finishedName, 4000, note)
        }
    }

    private fun mediaItem(ep: Episode): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.fromFile(ep.file)).setMediaId(ep.relPath)
        val sub = ep.subtitles.firstOrNull { n -> listOf("chs", "sc", "zh", "简").any { n.name.lowercase().contains(it) } }
            ?: ep.subtitles.firstOrNull()
        if (sub != null) {
            val mime = when (LibraryScanner.extension(sub.name)) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            b.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                        .setMimeType(mime)
                        .setLanguage("zh")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return b.build()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            watchdog.reset()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val show = currentShow() ?: return
                player?.let { book.save(show, it.currentMediaItemIndex, 0, 0) }
                updateNow()
                showInfo(4000)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onShowFinished()
            updatePausedUi()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePausedUi()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onPlayerError(error: PlaybackException) {
            val key = currentKey() ?: return
            // 4001～4005：解码器起不来、解不了、超出能力，这类可以交给电视自带播放器试试
            val decodeProblem = error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            handler.post { failCurrent("出错：${error.errorCodeName}", key, decodeProblem) }
        }

        /** 有画面（或声音）轨道，但电视和 ffmpeg 都解不了：黑屏或没声音，也算放不了。 */
        override fun onTracksChanged(tracks: Tracks) {
            val p = player ?: return
            if (tracks.isEmpty || p.playbackState == Player.STATE_IDLE) return
            fun broken(type: Int) = tracks.groups.any { it.type == type } && !tracks.isTypeSelected(type)
            val reason = when {
                broken(C.TRACK_TYPE_VIDEO) -> "画面解不了"
                broken(C.TRACK_TYPE_AUDIO) -> "声音解不了"
                else -> return
            }
            val key = currentKey() ?: return
            handler.post { failCurrent(reason, key, decodeProblem = true) }
        }
    }

    private fun currentKey(): String? {
        val show = currentShow() ?: return null
        val ep = currentEpisode() ?: return null
        return show.name + "\n" + ep.relPath
    }

    /**
     * 这一集放不了：记下来，跳到下一集。同一部剧连续 3 集放不了，整部剧跳过。
     * [expectedKey] 是出问题时正在放的那一集；等真正处理时已经换了集，就不管了。
     */
    private fun failCurrent(reason: String, expectedKey: String? = currentKey(), decodeProblem: Boolean = false) {
        val p = player ?: return
        val show = currentShow() ?: return
        val idx = p.currentMediaItemIndex
        val ep = show.episodes.getOrNull(idx) ?: return
        if (expectedKey == null || currentKey() != expectedKey) return
        Log.w(TAG, "放不了 [$reason] ${show.name} / ${ep.relPath}")
        if (decodeProblem && handOffToSystemPlayer(show, idx, ep)) return

        val justBlocked = health.recordFailure(show.name, ep.relPath)
        val next = show.episodes.getOrNull(idx + 1)
        if (next != null) book.save(show, idx + 1, 0, 0) else book.reset(show)

        val oldIndex = showIndex
        shows = health.playable(AppState.shows)
        if (shows.isEmpty()) {
            releasePlayer()
            showAllBroken()
            return
        }
        val same = shows.indexOfFirst { it.name == show.name }
        when {
            // 这部剧被跳过了（或者一集都不剩了）：原来位置上现在就是下一部
            same < 0 -> startShow(
                oldIndex % shows.size, Reason.AUTO_NEXT,
                note = if (justBlocked) "《${show.name}》连续 3 集放不了，先跳过这部" else "《${show.name}》放不了，先跳过这部",
            )
            next == null -> startShow(ProgressBook.wrap(same, 1, shows.size), Reason.AUTO_NEXT, finishedName = show.name)
            else -> startShow(same, Reason.AUTO_NEXT, note = "${ep.label} 放不了，自动跳到下一集")
        }
    }

    /**
     * 硬解真放不了：交给电视自带的播放器放这一集。先把进度记到下一集，
     * 看完按返回回来，就接着放下一集，不会再跳出去。
     * 7.0 起不能把 file:// 地址交给别的 App，只在老电视上用。
     */
    private fun handOffToSystemPlayer(show: Show, idx: Int, ep: Episode): Boolean {
        if (Build.VERSION.SDK_INT >= 24) return false
        val intent = SYSTEM_PLAYERS.asSequence()
            .map { Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(ep.file), "video/*").setPackage(it) }
            .firstOrNull { it.resolveActivity(packageManager) != null } ?: return false
        if (idx + 1 < show.episodes.size) {
            book.save(show, idx + 1, 0, 0)
        } else {
            book.reset(show)
            book.lastShow = shows[ProgressBook.wrap(showIndex, 1, shows.size)].name
        }
        Log.w(TAG, "交给 ${intent.`package`} 放：${show.name} / ${ep.relPath}")
        // 先停掉自己的播放器，不然 onStop 存进度时又把这一集记回去，回来又会跳出去
        releasePlayer()
        if (runCatching { startActivity(intent) }.isSuccess) return true
        startShow(book.startShowIndex(shows), Reason.AUTO_NEXT, note = "${ep.label} 放不了，自动跳到下一集")
        return true
    }

    /** 一部剧最后一集放完：这部从头记起，接着放下一部；最后一部放完回到第一部。 */
    private fun onShowFinished() {
        val show = currentShow() ?: return
        book.reset(show)
        val next = ProgressBook.wrap(showIndex, 1, shows.size)
        startShow(next, Reason.AUTO_NEXT, finishedName = show.name)
    }

    private fun currentShow(): Show? = shows.getOrNull(showIndex)

    private fun currentEpisode(): Episode? {
        val p = player ?: return null
        return currentShow()?.episodes?.getOrNull(p.currentMediaItemIndex)
    }

    private fun updateNow() {
        AppState.nowShow = currentShow()?.name
        AppState.nowLabel = currentEpisode()?.label
    }

    private fun saveProgress() {
        val p = player ?: return
        val show = currentShow() ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
        book.save(show, p.currentMediaItemIndex, p.currentPosition, dur)
    }

    /** 每秒一次：记播放统计、每 5 秒存一次进度、刷新进度条。 */
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val p = player
            if (p != null && p.isPlaying && lastTickAt > 0) {
                val delta = (now - lastTickAt).coerceAtMost(2000)
                val show = currentShow()
                val ep = currentEpisode()
                if (show != null && ep != null) {
                    AppState.stats(this@MainActivity).onPlayed(System.currentTimeMillis(), show.name, ep.label, delta)
                }
            }
            lastTickAt = now
            ticks++
            if (ticks % 5 == 0 && p?.isPlaying == true) saveProgress()
            if (ticks % 30 == 0) AppState.saveStats(this@MainActivity)
            if (p != null && screen == Screen.PLAYING && pendingIndex < 0) checkHealth(p, now)
            if (info.visibility == View.VISIBLE) refreshInfo()
            handler.postDelayed(this, 1000)
        }
    }

    /** 每秒看一眼：卡住了就跳集；正常放满 20 秒，这部剧的「连续放不了」清零。 */
    private fun checkHealth(p: ExoPlayer, now: Long) {
        val show = currentShow() ?: return
        val playing = p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        val frames = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() } ?: 0L
        val hasVideo = p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        when (val r = watchdog.sample(now, playing, p.currentPosition, hasVideo, frames)) {
            is PlaybackWatchdog.Result.Stalled -> failCurrent(r.reason)
            PlaybackWatchdog.Result.Healthy -> health.recordHealthy(show.name)
            PlaybackWatchdog.Result.Ok -> Unit
        }
    }

    // ================= 遥控器 =================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in PASS_THROUGH) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN

        // 确认键按下就算一下，按住不放不会触发别的（长辈常常按住不松手）
        if (code in CENTER_KEYS) {
            if (down && event.repeatCount == 0) onCenter()
            return true
        }
        // 3 秒内连按 5 下菜单键：进维护页（任何画面下都可以）
        if (code == KeyEvent.KEYCODE_MENU && down && event.repeatCount == 0 && menuPresses.press(event.eventTime)) {
            openMaintenance()
            return true
        }

        if (screen != Screen.PLAYING) return true
        if (!down) return true

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP ->
                if (event.repeatCount == 0) switchBy(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN ->
                if (event.repeatCount == 0) switchBy(+1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1, event)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(+1, event)
            KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); saveProgress() }
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0) player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
            // 返回键、菜单键：不退出，只显示一下现在在放什么
            else -> if (event.repeatCount == 0) showInfo(4000)
        }
        return true
    }

    private fun onCenter() {
        when (screen) {
            Screen.NO_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(AppState.runtimeStoragePermission()), REQ_STORAGE)
            }
            Screen.PLAYING -> {
                if (pendingIndex >= 0) {
                    handler.removeCallbacks(commitSwitch)
                    commitSwitch.run()
                    return
                }
                val p = player ?: return
                if (p.playWhenReady) {
                    p.pause()
                    saveProgress()
                } else {
                    p.play()
                }
            }
            else -> rescan()
        }
    }

    private fun switchBy(delta: Int) {
        if (shows.size <= 1) {
            showInfo(3000)
            return
        }
        if (pendingIndex < 0) {
            saveProgress()
            player?.pause()
        }
        val base = if (pendingIndex >= 0) pendingIndex else showIndex
        pendingIndex = ProgressBook.wrap(base, delta, shows.size)
        hideInfo()
        showCard(pendingIndex, book.resume(shows[pendingIndex]), null, 0)
        handler.removeCallbacks(commitSwitch)
        // 停手 0.8 秒再开始放，连按几下可以一口气跳过好几部
        handler.postDelayed(commitSwitch, 800)
    }

    private val commitSwitch = Runnable {
        val idx = pendingIndex
        pendingIndex = -1
        if (idx < 0) return@Runnable
        if (idx == showIndex) {
            player?.play()
            hideCardLater(1500)
        } else {
            startShow(idx, Reason.SWITCH)
        }
    }

    private fun seek(dir: Int, event: KeyEvent) {
        val p = player ?: return
        if (pendingIndex >= 0) return
        val step = stepper.onKeyDown(dir, event.repeatCount, event.eventTime)
        if (step == 0L) return
        val now = SystemClock.uptimeMillis()
        val base = if (seekTarget >= 0 && now - lastSeekAt < 1500) seekTarget else p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        var target = base + step
        if (dur != null) target = target.coerceAtMost(dur - 3000)
        target = target.coerceAtLeast(0)
        seekTarget = target
        lastSeekAt = now
        p.seekTo(target)
        watchdog.onSeek()

        seekIcon.setImageResource(if (step > 0) R.drawable.ic_ff else R.drawable.ic_rew)
        seekText.text = "${if (step > 0) "快进" else "快退"} ${abs(step) / 1000} 秒"
        seekBadge.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekBadge)
        handler.postDelayed(hideSeekBadge, 1500)
        showInfo(3000, compact = true)
    }

    private val hideSeekBadge = Runnable { seekBadge.visibility = View.GONE }

    private fun openMaintenance() {
        saveProgress()
        AppState.saveStats(this)
        startActivity(Intent(this, MaintenanceActivity::class.java))
    }

    // ================= 画面状态 =================

    private fun showNoPermission() {
        showMessage(
            Screen.NO_PERMISSION,
            title = "需要读取硬盘的权限",
            sub = "按遥控器中间的确认键，在弹出来的窗口里选「允许」",
            foot = "家人也可以用电脑执行：adb shell appops set $packageName MANAGE_EXTERNAL_STORAGE allow",
        )
    }

    private fun showAllBroken() {
        showMessage(
            Screen.ALL_BROKEN,
            title = "这些剧都放不了",
            sub = "放不了的剧和集都先跳过了，请家人看一下",
            foot = "家人看这里：3 秒内连按 5 下遥控器菜单键进维护页，里面可以恢复",
        )
    }

    private fun showMessage(s: Screen, title: String? = null, sub: String? = null, foot: String? = null) {
        screen = s
        hideInfo()
        switchLayer.visibility = View.GONE
        pauseIcon.visibility = View.GONE
        message.visibility = View.VISIBLE
        spinner.visibility = if (s == Screen.SCANNING || s == Screen.NO_MEDIA) View.VISIBLE else View.GONE
        msgTitle.text = title ?: "正在找移动硬盘…"
        msgSub.text = sub ?: if (s == Screen.NO_MEDIA && AppState.roots.isNotEmpty()) {
            "找到了硬盘，但里面没有剧。剧要放在硬盘最外层，一部剧一个文件夹"
        } else {
            "硬盘插在电视的 USB 口上，等一会儿就好"
        }
        msgFoot.text = foot ?: "家人看这里：一直找不到的话，3 秒内连按 5 下遥控器菜单键进维护页"
    }

    private fun hideMessage() {
        screen = Screen.PLAYING
        message.visibility = View.GONE
    }

    private val hideInfoRunnable = Runnable { if (player?.playWhenReady != false) hideInfo() }
    private val hideCardRunnable = Runnable { switchLayer.visibility = View.GONE }

    private fun showInfo(ms: Long, compact: Boolean = false) {
        if (screen != Screen.PLAYING || pendingIndex >= 0) return
        setCompact(compact)
        info.visibility = View.VISIBLE
        refreshInfo()
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, ms)
    }

    private fun hideInfo() {
        handler.removeCallbacks(hideInfoRunnable)
        info.visibility = View.GONE
    }

    private fun updatePausedUi() {
        val p = player ?: return
        val paused = !p.playWhenReady && screen == Screen.PLAYING && pendingIndex < 0
        pauseIcon.visibility = if (paused) View.VISIBLE else View.GONE
        if (paused) {
            handler.removeCallbacks(hideInfoRunnable)
            setCompact(false)
            info.visibility = View.VISIBLE
            refreshInfo()
        } else if (info.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideInfoRunnable)
            handler.postDelayed(hideInfoRunnable, 2000)
        }
    }

    private fun refreshInfo() {
        val p = player ?: return
        infoTitle.text = currentShow()?.name ?: ""
        infoEpisode.text = currentEpisode()?.label ?: ""
        val recentSeek = seekTarget >= 0 && SystemClock.uptimeMillis() - lastSeekAt < 1500
        val pos = if (recentSeek) seekTarget else p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        bar.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        infoTime.text = if (dur > 0) "${TimeText.clock(pos)}  /  ${TimeText.clock(dur)}" else TimeText.clock(pos)
        infoHint.visibility = if (!p.playWhenReady) View.VISIBLE else View.GONE
    }

    private fun setCompact(compact: Boolean) {
        with(ui) {
            if (compact) {
                titleRow.orientation = LinearLayout.HORIZONTAL
                infoTitle.size(44)
                infoEpisode.size(40)
                (infoEpisode.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = px(28); topMargin = 0 }
            } else {
                titleRow.orientation = LinearLayout.VERTICAL
                infoTitle.size(76)
                infoEpisode.size(54)
                (infoEpisode.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = 0; topMargin = px(14) }
            }
            infoEpisode.requestLayout()
        }
    }

    /** 换剧卡片 / 开机提示 / 放完接下一部的提示。[hideAfter] 为 0 时一直显示。 */
    private fun showCard(index: Int, r: ProgressBook.Resume, finishedName: String?, hideAfter: Long, note: String? = null) {
        val show = shows.getOrNull(index) ?: return
        val ep = show.episodes.getOrNull(r.index)
        val switching = pendingIndex >= 0
        cardTop.text = when {
            note != null -> note
            finishedName != null -> "《$finishedName》放完了，接着看"
            else -> "第 ${index + 1} 部 · 共 ${shows.size} 部"
        }
        cardTitle.text = show.name
        cardEpisode.text = ep?.label ?: ""
        cardSub.text = if (r.positionMs > 5000) "从 ${TimeText.clock(r.positionMs)} 接着看" else "从头开始看"
        if (switching && shows.size > 1) {
            prevHint.text = "↑  上一部：${shows[ProgressBook.wrap(index, -1, shows.size)].name}"
            nextHint.text = "↓  下一部：${shows[ProgressBook.wrap(index, 1, shows.size)].name}"
            prevHint.visibility = View.VISIBLE
            nextHint.visibility = View.VISIBLE
            switchLayer.setBackgroundColor(Color.parseColor("#C7000000"))
            card.background = ui.rounded(Ui.CARD, 28, Ui.ACCENT, 4)
        } else {
            prevHint.visibility = View.INVISIBLE
            nextHint.visibility = View.INVISIBLE
            switchLayer.setBackgroundColor(Color.TRANSPARENT)
            card.background = ui.rounded(Ui.CARD, 28)
        }
        cardSub.visibility = View.VISIBLE
        switchLayer.visibility = View.VISIBLE
        pauseIcon.visibility = View.GONE
        handler.removeCallbacks(hideCardRunnable)
        if (hideAfter > 0) handler.postDelayed(hideCardRunnable, hideAfter)
    }

    private fun showCardText(top: String, title: String, sub: String, hideAfter: Long) {
        cardTop.text = top
        cardTitle.text = title
        cardEpisode.text = sub
        cardSub.visibility = View.GONE
        prevHint.visibility = View.INVISIBLE
        nextHint.visibility = View.INVISIBLE
        switchLayer.setBackgroundColor(Color.TRANSPARENT)
        card.background = ui.rounded(Ui.CARD, 28)
        switchLayer.visibility = View.VISIBLE
        hideCardLater(hideAfter)
    }

    private fun hideCardLater(ms: Long) {
        handler.removeCallbacks(hideCardRunnable)
        handler.postDelayed(hideCardRunnable, ms)
    }

    // ================= 布局（坐标照原型 1280×720） =================

    private fun buildLayout(): View = with(ui) {
        val ctx = this@MainActivity
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(ctx).apply {
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            subtitleView?.apply {
                setApplyEmbeddedFontSizes(false)
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.35f)
                setStyle(
                    CaptionStyleCompat(
                        Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
                    )
                )
            }
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // 暂停图标
        pauseIcon = horizontal(ctx, 22, Gravity.CENTER).apply {
            background = GradientDrawableCircle(Color.parseColor("#99000000"), Color.parseColor("#D9FFFFFF"), px(4))
            repeat(2) {
                addView(View(ctx).apply { background = rounded(Color.WHITE, 4) }, LinearLayout.LayoutParams(px(22), px(64)))
            }
            visibility = View.GONE
        }
        root.addView(pauseIcon, FrameLayout.LayoutParams(px(140), px(140), Gravity.CENTER_HORIZONTAL).apply { topMargin = px(150) })

        // 快进快退提示
        seekBadge = horizontal(ctx, 18, Gravity.CENTER).apply {
            background = rounded(Color.parseColor("#C7000000"), 60)
            setPadding(px(44), px(22), px(44), px(22))
            visibility = View.GONE
        }
        seekIcon = ImageView(ctx)
        seekText = text(ctx, 60, bold = true)
        seekBadge.addView(seekIcon, LinearLayout.LayoutParams(px(64), px(64)))
        seekBadge.addView(seekText)
        root.addView(seekBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = px(120) })

        // 底部信息栏
        info = vertical(ctx).apply {
            setBackgroundColor(Ui.PANEL)
            setPadding(px(72), px(40), px(72), px(44))
            visibility = View.GONE
        }
        titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        infoTitle = text(ctx, 76, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        infoEpisode = text(ctx, 54, Ui.ACCENT, bold = true)
        titleRow.addView(infoTitle, LinearLayout.LayoutParams(-2, -2))
        titleRow.addView(infoEpisode, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14) })
        info.addView(titleRow)
        bar = BarView(ctx, ui)
        info.addView(bar, LinearLayout.LayoutParams(-1, px(40)).apply { topMargin = px(26) })
        val timeRow = horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        infoTime = text(ctx, 48, bold = true)
        infoHint = text(ctx, 34, Ui.LIGHT).apply { text = "按 确认键 继续看" }
        timeRow.addView(infoTime, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(infoHint)
        info.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 换剧卡片
        switchLayer = vertical(ctx, 28, Gravity.CENTER).apply { visibility = View.GONE }
        prevHint = text(ctx, 34, Ui.DIM)
        nextHint = text(ctx, 34, Ui.DIM)
        card = vertical(ctx, 18, Gravity.CENTER_HORIZONTAL).apply { setPadding(px(60), px(44), px(60), px(44)) }
        cardTop = text(ctx, 32, Ui.GRAY)
        cardTitle = text(ctx, 84, bold = true).apply { maxLines = 2 }
        cardEpisode = text(ctx, 50, Ui.ACCENT, bold = true)
        cardSub = text(ctx, 36, Ui.LIGHT)
        listOf(cardTop, cardTitle, cardEpisode, cardSub).forEach {
            it.gravity = Gravity.CENTER
            card.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        prevHint.gravity = Gravity.CENTER
        nextHint.gravity = Gravity.CENTER
        switchLayer.addView(prevHint, LinearLayout.LayoutParams(-1, -2))
        switchLayer.addView(card, LinearLayout.LayoutParams(px(1000), -2))
        switchLayer.addView(nextHint, LinearLayout.LayoutParams(-1, -2))
        root.addView(switchLayer, FrameLayout.LayoutParams(-1, -1))

        // 整屏提示：找硬盘、要权限
        message = vertical(ctx, 30, Gravity.CENTER).apply { setBackgroundColor(Color.parseColor("#0B0C0E")) }
        spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Ui.ACCENT)
        }
        msgTitle = text(ctx, 72, bold = true).apply { gravity = Gravity.CENTER }
        msgSub = text(ctx, 38, Color.parseColor("#C3C8CE")).apply { gravity = Gravity.CENTER; setPadding(px(80), 0, px(80), 0) }
        message.addView(spinner, LinearLayout.LayoutParams(px(120), px(120)))
        message.addView(msgTitle)
        message.addView(msgSub)
        root.addView(message, FrameLayout.LayoutParams(-1, -1))
        msgFoot = text(ctx, 24, Ui.DIM).apply { gravity = Gravity.CENTER }
        message.addView(msgFoot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(120) })

        root.layoutParams = ViewGroup.LayoutParams(-1, -1)
        root
    }

    companion object {
        private const val REQ_STORAGE = 1
        private const val TAG = "ElderPlayer"
        /** 硬解出错时交给它们放（按顺序找第一个装了的） */
        private val SYSTEM_PLAYERS = listOf("com.tcl.videoplayer")
        private val CENTER_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        )
        private val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_TV_POWER,
            KeyEvent.KEYCODE_HOME,
        )
    }
}

private fun GradientDrawableCircle(fill: Int, stroke: Int, strokeWidth: Int) =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(fill)
        setStroke(strokeWidth, stroke)
    }
