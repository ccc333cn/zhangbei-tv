package com.ayou.elderplayer.core

/**
 * 左右键快进快退的步长：
 * - 按一下 10 秒；
 * - 1 秒内同方向再按（连按）20 秒；
 * - 按住不放，每 0.25 秒走 20 秒。
 */
class SeekStepper(
    private val tapMs: Long = 10_000,
    private val fastMs: Long = 20_000,
    private val chainWindowMs: Long = 1_000,
    private val repeatIntervalMs: Long = 250,
) {
    private var lastDir = 0
    private var lastPressAt = Long.MIN_VALUE / 2
    private var lastActionAt = Long.MIN_VALUE / 2

    /** [dir] 为 +1 快进、-1 快退。返回要走的毫秒数（带方向），0 表示这次不动。 */
    fun onKeyDown(dir: Int, repeatCount: Int, eventTimeMs: Long): Long {
        if (repeatCount == 0) {
            val chained = dir == lastDir && eventTimeMs - lastPressAt <= chainWindowMs
            lastDir = dir
            lastPressAt = eventTimeMs
            lastActionAt = eventTimeMs
            return dir * if (chained) fastMs else tapMs
        }
        if (eventTimeMs - lastActionAt < repeatIntervalMs) return 0
        lastDir = dir
        lastPressAt = eventTimeMs
        lastActionAt = eventTimeMs
        return dir * fastMs
    }
}
