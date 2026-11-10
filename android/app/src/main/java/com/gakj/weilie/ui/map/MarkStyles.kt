package com.gakj.weilie.ui.map

import com.gakj.weilie.core.model.SampleRecord

/**
 * 采样点的三类语义（对应「路线显示逻辑图层」需求）：
 *
 * - [MAIN]     主区命中：当前服务小区就是目标小区 → **普通实心点**
 * - [NEIGHBOR] 邻区命中：未连上目标，但邻区列表里测到了目标 → **彩色实心点**
 *              （颜色按“测到它的那个服务基站”区分，不同基站不同颜色）
 * - [OUT]      脱离目标：既没连上、邻区里也没有目标 → **空心点**（只画轨迹，
 *              不参与覆盖面积估算）
 */
enum class SampleMark(val label: String) {
    MAIN("主区命中"),
    NEIGHBOR("邻区命中"),
    OUT("脱离目标"),
    ;

    companion object {
        fun of(s: SampleRecord): SampleMark = when {
            s.onTarget -> MAIN
            s.targetSeen -> NEIGHBOR
            else -> OUT
        }

        fun of(samples: List<SampleRecord>, mark: SampleMark): List<SampleRecord> =
            samples.filter { of(it) == mark }
    }
}

/** 采样点与图层的配色（统一入口，地图引擎与导出图共用） */
object MarkStyles {

    /** 主区命中：青蓝实心 */
    const val MAIN_FILL = 0xFF22D3EE.toInt()
    const val MAIN_CORE = 0xFF0EA5E9.toInt()

    /** 脱离目标：空心（描边） */
    const val OUT_RING = 0xB3E2E8F0.toInt()

    /**
     * 不同基站用不同颜色：按服务小区 CI 取调色板。
     * （同一基站的邻区命中点颜色一致，不同基站的明显区分）
     */
    private val BASE_PALETTE = intArrayOf(
        0xFFF87171.toInt(), // 红
        0xFFFB923C.toInt(), // 橙
        0xFFA3E635.toInt(), // 黄绿
        0xFF34D399.toInt(), // 绿
        0xFF38BDF8.toInt(), // 天蓝
        0xFF818CF8.toInt(), // 靛蓝
        0xFFC084FC.toInt(), // 紫
        0xFFF472B6.toInt(), // 粉
    )

    fun baseColor(servingCi: Long?): Int {
        if (servingCi == null) return BASE_PALETTE[0]
        // 稳定散列：同一 CI 永远同一颜色
        val h = (servingCi xor (servingCi ushr 17) xor (servingCi ushr 33)).toInt()
        return BASE_PALETTE[Math.floorMod(h, BASE_PALETTE.size)]
    }

    /** 点的填充色（OUT 为透明，只画描边） */
    fun fillOf(s: SampleRecord): Int = when (SampleMark.of(s)) {
        SampleMark.MAIN -> MAIN_FILL
        SampleMark.NEIGHBOR -> baseColor(s.servingCi)
        SampleMark.OUT -> 0x00000000
    }

    /** 点的描边色 */
    fun strokeOf(s: SampleRecord): Int = when (SampleMark.of(s)) {
        SampleMark.MAIN -> 0xFFFFFFFF.toInt()
        SampleMark.NEIGHBOR -> 0xCCFFFFFF.toInt()
        SampleMark.OUT -> OUT_RING
    }

    /**
     * 参与覆盖面积估算的有效点：
     * - 主区命中：直接算
     * - 邻区命中：要求目标 RSRP 高于门槛（默认 -110 dBm），避免“扫到一闪而过的弱信号”把面积撑大
     * - 脱离目标：不参与
     */
    fun areaPoints(samples: List<SampleRecord>, rsrpFloorDbm: Int = -110): List<SampleRecord> =
        samples.filter { s ->
            when (SampleMark.of(s)) {
                SampleMark.MAIN -> true
                SampleMark.NEIGHBOR -> (s.targetRsrp ?: s.rsrp ?: Int.MIN_VALUE) >= rsrpFloorDbm
                SampleMark.OUT -> false
            }
        }

    /** 图例文案（导出图 / 图层说明共用） */
    val LEGEND: List<Pair<Int, String>> = listOf(
        MAIN_FILL to "主区命中（连上目标）",
        BASE_PALETTE[0] to "邻区命中（不同基站不同色）",
        OUT_RING to "脱离目标（空心，不参与面积）",
    )
}
