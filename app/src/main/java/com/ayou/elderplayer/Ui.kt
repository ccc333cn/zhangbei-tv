package com.ayou.elderplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 界面按原型的 1280×720 坐标来写，再按屏幕宽度等比放大，
 * 这样不管电视的 dpi 是多少，字的大小和原型看起来都一样。
 */
class Ui(ctx: Context) {
    private val k: Float = ctx.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) / 1280f }

    fun px(v: Number): Int = (v.toFloat() * k).roundToInt()
    fun pxf(v: Number): Float = v.toFloat() * k

    fun text(ctx: Context, size: Number, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    fun TextView.size(size: Number) = setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))

    fun rounded(color: Int, radius: Number, strokeColor: Int? = null, strokeWidth: Number = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = pxf(radius)
            if (strokeColor != null) setStroke(px(strokeWidth), strokeColor)
        }

    fun vertical(ctx: Context, gap: Number = 0, gravity: Int = Gravity.START): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = gravity
            if (gap.toFloat() > 0) {
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = GradientDrawable().apply { setSize(1, px(gap)); setColor(Color.TRANSPARENT) }
            }
        }

    fun horizontal(ctx: Context, gap: Number = 0, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            if (gap.toFloat() > 0) {
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = GradientDrawable().apply { setSize(px(gap), 1); setColor(Color.TRANSPARENT) }
            }
        }

    companion object {
        val ACCENT = Color.parseColor("#F5B83D")
        val TEAL = Color.parseColor("#4FD1C5")
        val GRAY = Color.parseColor("#A9B0B9")
        val DIM = Color.parseColor("#8A9099")
        val LIGHT = Color.parseColor("#D7DBE0")
        val PANEL = Color.parseColor("#DB000000")
        val CARD = Color.parseColor("#F514161A")
    }
}

/** 粗进度条：灰色底、琥珀色已播部分、白色圆点。 */
class BarView(ctx: Context, private val ui: Ui) : View(ctx) {
    var fraction: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color: Int = Ui.ACCENT

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = ui.pxf(18)
        val knob = ui.pxf(18)
        val cy = height / 2f
        val left = knob
        val right = width - knob
        rect.set(left, cy - h / 2, right, cy + h / 2)
        paint.color = Color.parseColor("#38FFFFFF")
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val x = left + (right - left) * fraction
        rect.set(left, cy - h / 2, x, cy + h / 2)
        paint.color = color
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, cy, knob, paint)
    }
}
