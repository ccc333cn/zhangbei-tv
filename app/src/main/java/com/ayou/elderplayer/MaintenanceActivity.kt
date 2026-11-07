package com.ayou.elderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ayou.elderplayer.core.DecoderPick
import com.ayou.elderplayer.core.PlayStats
import com.ayou.elderplayer.core.Show

/** 家人用的维护页：选剧、选集、重新扫描、看播放统计、回原来的桌面。 */
class MaintenanceActivity : Activity() {

    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        bumpIdle()
    }

    override fun onPause() {
        handler.removeCallbacks(autoClose)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        bumpIdle()
        return super.dispatchKeyEvent(event)
    }

    /** 1 分钟不操作就自己回去播放，免得老人误进来出不去。 */
    private fun bumpIdle() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, 60_000)
    }

    private fun build(): View = with(ui) {
        val ctx = this@MaintenanceActivity
        val root = vertical(ctx, 28).apply {
            setBackgroundColor(Color.parseColor("#111317"))
            setPadding(px(64), px(48), px(64), px(48))
        }

        val header = horizontal(ctx)
        header.addView(text(ctx, 48, bold = true).apply { text = "维护（家人用）" }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text(ctx, 22, Ui.DIM).apply { text = "1 分钟不操作，自动回去播放" })
        root.addView(header)

        val body = horizontal(ctx, 44, Gravity.TOP)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // 左边：按钮
        val buttons = vertical(ctx, 10)
        val first = button("返回播放") { finish() }
        buttons.addView(first, btnParams())
        if (!AppState.hasStorageAccess(ctx)) buttons.addView(button("打开存储权限") { openStoragePermission() }, btnParams())
        buttons.addView(button("选剧") { pickShow() }, btnParams())
        buttons.addView(button("选集（改到第几集）") { pickEpisode() }, btnParams())
        val health = AppState.health(ctx)
        if (!health.isEmpty()) {
            buttons.addView(button("恢复跳过的剧和集") {
                health.clearAll()
                Toast.makeText(ctx, "已恢复，回到播放时重新扫描", Toast.LENGTH_SHORT).show()
                finish()
            }, btnParams())
        }
        buttons.addView(button("重新扫描硬盘") {
            Toast.makeText(ctx, "回到播放时会重新扫描", Toast.LENGTH_SHORT).show()
            finish()
        }, btnParams())
        buttons.addView(button("打开电视设置") { open(Intent(Settings.ACTION_SETTINGS)) }, btnParams())
        buttons.addView(button("回到电视原来的桌面") { otherLauncher() }, btnParams())
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(buttons)
        }
        body.addView(scroll, LinearLayout.LayoutParams(px(380), -1))

        // 右边：状态和播放统计
        val right = vertical(ctx, 16)
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        val shows = AppState.shows
        val status = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#1A1D22"), 18)
            setPadding(px(28), px(20), px(28), px(20))
        }
        val access = AppState.hasStorageAccess(ctx)
        val drives = AppState.roots.count { it != StorageRoots.internalFallback() }
        status.addView(statusRow(
            "存储权限 " to Ui.DIM, (if (access) "已开启" else "未开启") to (if (access) Ui.TEAL else Ui.ACCENT),
            "      移动硬盘 " to Ui.DIM, "找到 $drives 个" to Color.WHITE,
        ))
        status.addView(statusRow(
            "剧 " to Ui.DIM, "${shows.size} 部，共 ${shows.sumOf { it.episodes.size }} 集" to Color.WHITE,
            "      正在看 " to Ui.DIM, listOfNotNull(AppState.nowShow, AppState.nowLabel).joinToString(" ").ifEmpty { "—" } to Color.WHITE,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        for (row in listOf(
            statusRow("视频解码 " to Ui.DIM, DecoderPick.label(AppState.videoDecoder) to Color.WHITE),
            statusRow("声音解码 " to Ui.DIM, (AppState.audioDecoder ?: "—") to Color.WHITE),
        )) {
            row.maxLines = 1
            row.ellipsize = android.text.TextUtils.TruncateAt.END
            status.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
        if (!health.isEmpty()) {
            val blocked = health.blockedShows()
            val parts = mutableListOf<String>()
            if (blocked.isNotEmpty()) parts += blocked.joinToString("、") { "《$it》" }
            if (health.badCount() > 0) parts += "${health.badCount()} 集放不了"
            status.addView(statusRow("已跳过 " to Ui.DIM, parts.joinToString(" · ") to Ui.ACCENT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
        right.addView(status, LinearLayout.LayoutParams(-1, -2))

        right.addView(statsCard(), LinearLayout.LayoutParams(-1, 0, 1f))

        first.post { first.requestFocus() }
        FrameLayout(ctx).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun statsCard(): View = with(ui) {
        val ctx = this@MaintenanceActivity
        val stats = AppState.stats(ctx)
        val now = System.currentTimeMillis()
        val week = stats.lastDays(now, 7)
        val weekTotal = week.sumOf { it.second }

        val card = vertical(ctx, 14).apply {
            background = rounded(Color.parseColor("#1A1D22"), 18)
            setPadding(px(28), px(22), px(28), px(22))
        }
        val head = horizontal(ctx)
        head.addView(text(ctx, 28, bold = true).apply { text = "播放统计" }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text(ctx, 22, Ui.DIM).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("今天 ")
                val start = length
                append(PlayStats.durationLabel(stats.todayMs(now)))
                setSpan(android.text.style.ForegroundColorSpan(Ui.ACCENT), start, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                append(" · 近 7 天 ${PlayStats.durationLabel(weekTotal)}")
            }
        })
        card.addView(head)

        // 近 7 天柱状图
        val maxMs = week.maxOf { it.second }.coerceAtLeast(1)
        val bars = horizontal(ctx, 12, Gravity.BOTTOM)
        val labels = horizontal(ctx, 12)
        week.forEachIndexed { i, (label, ms) ->
            val h = if (ms == 0L) px(4) else (px(76) * ms / maxMs).toInt().coerceAtLeast(px(6))
            val isToday = i == week.lastIndex
            bars.addView(View(ctx).apply {
                background = rounded(if (isToday) Ui.ACCENT else Color.parseColor("#3A3F47"), 6)
            }, LinearLayout.LayoutParams(0, h, 1f))
            labels.addView(text(ctx, 18, if (isToday) Color.WHITE else Ui.DIM).apply {
                text = label
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(bars, LinearLayout.LayoutParams(-1, px(76)))
        card.addView(labels, LinearLayout.LayoutParams(-1, -2))

        // 最近几次
        val recent = stats.recent(3)
        if (recent.isEmpty()) {
            card.addView(text(ctx, 21, Ui.DIM).apply { text = "还没有播放记录" })
        }
        for (s in recent) {
            val row = horizontal(ctx, 16).apply {
                setPadding(0, px(7), 0, px(7))
            }
            row.addView(text(ctx, 21, Ui.DIM).apply { text = stats.whenLabel(now, s.startMs) }, LinearLayout.LayoutParams(px(170), -2))
            row.addView(text(ctx, 21).apply {
                text = "${s.show}  ${PlayStats.episodeRange(s)}"
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(ctx, 21).apply {
                text = PlayStats.durationLabel(s.watchedMs)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(px(130), -2))
            card.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#2A2E35")) }, LinearLayout.LayoutParams(-1, 1))
            card.addView(row)
        }
        card
    }

    private fun statusRow(vararg parts: Pair<String, Int>): TextView = with(ui) {
        text(this@MaintenanceActivity, 24).apply {
            text = android.text.SpannableStringBuilder().apply {
                for ((s, color) in parts) {
                    val start = length
                    append(s)
                    setSpan(android.text.style.ForegroundColorSpan(color), start, length, 0)
                }
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): TextView = with(ui) {
        text(this@MaintenanceActivity, 28).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(26), 0, px(26), 0)
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE, 16))
                addState(intArrayOf(), rounded(Color.parseColor("#23262C"), 16))
            }
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(Color.parseColor("#0B0C0E"), Color.WHITE),
                )
            )
            setOnClickListener { onClick() }
        }
    }

    private fun btnParams() = LinearLayout.LayoutParams(-1, ui.px(62))

    // ---- 动作 ----

    private fun pickShow() {
        val shows = AppState.health(this).playable(AppState.shows)
        if (shows.isEmpty()) return toast("还没找到剧")
        val now = AppState.nowShow
        val names = shows.map { if (it.name == now) "${it.name}  （正在看）" else it.name }.toTypedArray()
        dialog("选剧", names, shows.indexOfFirst { it.name == now }) { i ->
            AppState.book(this).lastShow = shows[i].name
            finish()
        }
    }

    private fun pickEpisode() {
        val shows = AppState.health(this).playable(AppState.shows)
        val book = AppState.book(this)
        val show: Show = shows.firstOrNull { it.name == (AppState.nowShow ?: book.lastShow) } ?: shows.firstOrNull()
            ?: return toast("还没找到剧")
        val current = book.resume(show).index
        val items = show.episodes.map { "${it.label}    ${it.file.name}" }.toTypedArray()
        dialog("选集：${show.name}", items, current) { i ->
            book.save(show, i, 0, 0)
            book.lastShow = show.name
            finish()
        }
    }

    private fun dialog(title: String, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setSingleChoiceItems(items, checked) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun otherLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // 设置里的备用桌面（优先级 -1000）不算
        val homes = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName && it.priority > -1000 }
        if (homes.isEmpty()) {
            // 按说明书把自带桌面停用了，就会走到这里：告诉家人怎么恢复
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("电视自带的桌面已经停用了")
                .setMessage("要恢复，用电脑执行：\n\nadb shell pm enable 自带桌面的包名\n\n小米电视一般是 com.mitv.tvhome")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val launch = { i: Int ->
            val a = homes[i].activityInfo
            open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(ComponentName(a.packageName, a.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Unit
        }
        if (homes.size == 1) launch(0)
        else dialog("回到哪个桌面", homes.map { it.loadLabel(packageManager).toString() }.toTypedArray(), -1, launch)
    }

    private fun openStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 &&
            open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), quiet = true)
        ) return
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun open(intent: Intent, quiet: Boolean = false): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            if (!quiet) toast("这台电视打不开这个页面")
            false
        }

    /** Theme_DeviceDefault_Dialog_Alert 要安卓 5.1，安卓 5.0 用老的深色对话框。 */
    @Suppress("DEPRECATION")
    private fun dialogTheme(): Int =
        if (Build.VERSION.SDK_INT >= 22) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else AlertDialog.THEME_DEVICE_DEFAULT_DARK

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
