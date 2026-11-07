package com.ayou.elderplayer.core

/**
 * 判断一集是不是「放不动」：播放器没报错，但其实卡住了。
 *
 * 播放页每秒喂一次状态。只在「应该在放」的时候计时，暂停的时间不算：
 * - 进度连续 [stallMs] 没动（包括一直在缓冲）→ 卡住；
 * - 有画面的片子，进度走了 [frozenVideoMs] 但一帧画面都没出 → 画面冻住；
 * - 正常放满 [healthyMs] → 这一集是好的（用来把「连续几集放不了」清零）。
 * 每集只报一次卡住、一次正常，换集或重新开始时调 [reset]。
 */
class PlaybackWatchdog(
    private val stallMs: Long = 20_000,
    private val frozenVideoMs: Long = 15_000,
    private val healthyMs: Long = 20_000,
) {
    sealed interface Result {
        data object Ok : Result
        data object Healthy : Result
        data class Stalled(val reason: String) : Result
    }

    private var lastSampleAt = -1L
    private var lastPos = 0L
    private var sinceMove = 0L
    private var progress = 0L
    private var lastFrames = 0L
    private var posAtFrames = 0L
    private var remarkFrames = false
    private var healthy = false
    private var done = false

    fun reset() {
        lastSampleAt = -1
        sinceMove = 0
        progress = 0
        healthy = false
        done = false
    }

    /** 快进快退以后，画面要过一会儿才跟上，别把这段当成画面冻住。 */
    fun onSeek() {
        remarkFrames = true
        sinceMove = 0
    }

    /**
     * [playing]：应该在放（没暂停、在缓冲或在放）。
     * [renderedFrames]：到现在一共出了几帧画面，只看它变没变。
     */
    fun sample(nowMs: Long, playing: Boolean, positionMs: Long, hasVideo: Boolean, renderedFrames: Long): Result {
        if (done) return Result.Ok
        if (lastSampleAt < 0) {
            lastSampleAt = nowMs
            lastPos = positionMs
            lastFrames = renderedFrames
            posAtFrames = positionMs
            return Result.Ok
        }
        val dt = (nowMs - lastSampleAt).coerceIn(0, 2_000)
        lastSampleAt = nowMs
        if (!playing) {
            lastPos = positionMs
            return Result.Ok
        }
        if (positionMs != lastPos) {
            progress += dt
            sinceMove = 0
        } else {
            sinceMove += dt
        }
        lastPos = positionMs
        if (remarkFrames || renderedFrames != lastFrames) {
            remarkFrames = false
            lastFrames = renderedFrames
            posAtFrames = positionMs
        }

        if (sinceMove >= stallMs) {
            done = true
            return Result.Stalled("卡住不动")
        }
        if (hasVideo && positionMs - posAtFrames >= frozenVideoMs) {
            done = true
            return Result.Stalled("画面不动")
        }
        if (!healthy && progress >= healthyMs) {
            healthy = true
            return Result.Healthy
        }
        return Result.Ok
    }
}
