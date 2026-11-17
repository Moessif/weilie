package com.gakj.weilie.ui.map

/**
 * 任务区按「归属队长」着色（v1.2）：
 * 同一任队长固定的颜色，换队长后新区域自动换成另一种颜色，
 * 老的区域保留原色，方便一眼分清是谁划的。
 */
object ZoneStyle {

    private val palette = intArrayOf(
        0xFF34D399.toInt(), // 绿
        0xFF22D3EE.toInt(), // 青
        0xFFF59E0B.toInt(), // 橙
        0xFFA78BFA.toInt(), // 紫
        0xFFF472B6.toInt(), // 粉
        0xFF60A5FA.toInt(), // 蓝
        0xFFFDE047.toInt(), // 黄
        0xFFFB7185.toInt(), // 红
    )

    /** 描边色（不透明） */
    fun stroke(ownerId: String): Int =
        palette[Math.floorMod(ownerId.hashCode(), palette.size)]

    /** 填充色（默认 8% 透明度，避免盖住地图） */
    fun fill(ownerId: String, alpha: Int = 0x14): Int =
        (stroke(ownerId) and 0x00FFFFFF) or (alpha shl 24)
}
