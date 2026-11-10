package com.gakj.weilie.core.fitting

/**
 * 热力图栅格着色（纯函数，输出 ARGB Int 数组，UI 再转 Bitmap）。
 *
 * 色带：
 * - 强（≥ [Options.strongDbm]）：蓝绿渐变 #22D3EE → #34D399；
 * - 中（[Options.weakDbm] .. [Options.strongDbm]）：黄色 #FDE047；
 * - 弱（≤ [Options.weakDbm]）：橙红渐变 #F97316 → #EF4444；
 * - NaN（无数据）：0（透明）。
 *
 * alpha 由 [Options.alpha] 统一决定（0..255）。
 */
object HeatPalette {

    data class Options(
        val strongDbm: Double = -85.0,
        val weakDbm: Double = -105.0,
        val alpha: Int = 150,
        val minValueDbm: Double = -120.0,
        val maxValueDbm: Double = -60.0,
    )

    private const val CYAN = 0x22D3EE
    private const val GREEN = 0x34D399
    private const val YELLOW = 0xFDE047
    private const val ORANGE = 0xF97316
    private const val RED = 0xEF4444

    /** 单点取色，便于图例与单测；NaN → 0（透明） */
    fun colorFor(rsrpDbm: Double, opt: Options = Options()): Int {
        if (rsrpDbm.isNaN()) return 0
        val a = opt.alpha.coerceIn(0, 255)
        val rgb = when {
            rsrpDbm >= opt.strongDbm -> {
                val span = (opt.maxValueDbm - opt.strongDbm).coerceAtLeast(1e-9)
                lerp(CYAN, GREEN, ((rsrpDbm - opt.strongDbm) / span).coerceIn(0.0, 1.0))
            }
            rsrpDbm <= opt.weakDbm -> {
                val span = (opt.weakDbm - opt.minValueDbm).coerceAtLeast(1e-9)
                lerp(ORANGE, RED, ((opt.weakDbm - rsrpDbm) / span).coerceIn(0.0, 1.0))
            }
            else -> YELLOW
        }
        return (a shl 24) or rgb
    }

    /**
     * values：行优先网格（NaN 表示无数据，输出 0 透明）；返回 width*height 的 ARGB int 数组。
     * values 比 width*height 短时缺的格按 NaN（透明）处理。
     */
    fun render(values: DoubleArray, width: Int, height: Int, opt: Options = Options()): IntArray {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val n = w * h
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = colorFor(if (i < values.size) values[i] else Double.NaN, opt)
        }
        return out
    }

    private fun lerp(c1: Int, c2: Int, t: Double): Int {
        val r = lerpChannel((c1 shr 16) and 0xFF, (c2 shr 16) and 0xFF, t)
        val g = lerpChannel((c1 shr 8) and 0xFF, (c2 shr 8) and 0xFF, t)
        val b = lerpChannel(c1 and 0xFF, c2 and 0xFF, t)
        return (r shl 16) or (g shl 8) or b
    }

    private fun lerpChannel(a: Int, b: Int, t: Double): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)
}
