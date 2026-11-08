package com.gakj.weilie.core.rating

/** 信号 6 档评级（阈值可配，默认参考路测工具通行的 RSRP 分段） */
object SignalRating {

    val DEFAULT_RSRP_STEPS = listOf(-110, -95, -85, -75, -65) // 5 个分界 → 6 档

    /** 返回 0..5（0=极差, 5=极佳）；null/未知返回 -1 */
    fun level(rsrp: Int?, steps: List<Int> = DEFAULT_RSRP_STEPS): Int {
        val v = rsrp ?: return -1
        var lv = 0
        for (s in steps) if (v >= s) lv++
        return lv
    }

    fun label(level: Int): String = when (level) {
        -1 -> "无数据"; 0 -> "极差"; 1 -> "较弱"; 2 -> "中等"; 3 -> "良好"; 4 -> "优质"; else -> "极佳"
    }

    /** UI 配色（ARGB）：与主题语义一致的冷→暖渐变 */
    fun colorArgb(level: Int): Long = when (level) {
        0 -> 0xFF8B1E2D; 1 -> 0xFFC2410C; 2 -> 0xFFD97706
        3 -> 0xFF2E7D32; 4 -> 0xFF0E9F6E; 5 -> 0xFF06B6D4; else -> 0xFF64748B
    }

    /** 五格信号条：把 6 档映射为格数 0..5 */
    fun bars(level: Int): Int = level.coerceIn(0, 5)
}
