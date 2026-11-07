package com.ayou.elderplayer.core

/**
 * 连按检测：[windowMs] 内按满 [count] 下算一次，用来进维护页。
 * 只数按下的那一下，按住不放产生的重复事件由调用方过滤掉。
 */
class PressCounter(private val count: Int = 5, private val windowMs: Long = 3_000) {

    private val presses = ArrayDeque<Long>()

    /** 记一下按键，返回 true 表示刚好按够了（之后重新数）。 */
    fun press(nowMs: Long): Boolean {
        presses.addLast(nowMs)
        while (presses.isNotEmpty() && nowMs - presses.first() > windowMs) presses.removeFirst()
        if (presses.size < count) return false
        presses.clear()
        return true
    }
}
