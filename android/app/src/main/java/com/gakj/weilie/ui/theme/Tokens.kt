package com.gakj.weilie.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 设计令牌（v0.12 质感版）。
 *
 * 之前全工程的圆角/间距/描边是各页面随手写的 8/10/12/14/16/18/20/999，
 * 同一屏里能出现四五种圆角，视觉上会「散」。这里把尺度定死：
 *
 * - 间距只用 4 的倍数：4 / 8 / 12 / 16 / 20 / 24 / 32
 * - 圆角只有四档 + 胶囊：10（小标签/输入）→ 14（次级块）→ 20（卡片/面板）→ 26（大面板）→ 胶囊
 * - 描边一律 1dp 发丝线，靠透明度分层，不靠阴影
 *
 * 规则：新代码只用这里的常量；改视觉先改这里，不要在页面里再写魔法数。
 */
object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp

    /** 页面左右统一留白（营业级 App 的常见安全边距） */
    val screenH = 20.dp

    /** 卡片内边距 */
    val card = 16.dp

    /** 列表行内边距 */
    val row = 14.dp
}

object Radius {
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 26.dp
    val pill = 999.dp

    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
    val pillShape = RoundedCornerShape(pill)
}

object Hairline {
    val width = 1.dp

    /**
     * 发丝描边：深色界面上用「白 10%」的线分层面板，比阴影更干净、也不吃性能。
     * 在地图/相机这种背景不可控的页面上，用 [strong] 提高可读性。
     */
    fun Modifier.hairline(shape: Shape = Radius.lgShape, strong: Boolean = false): Modifier =
        this
            .clip(shape)
            .border(
                width = width,
                color = if (strong) WColors.GlassBorder else WColors.GlassBorderSoft,
                shape = shape,
            )

    fun Modifier.hairlineTop(shape: Shape = Radius.lgShape): Modifier =
        this.clip(shape).border(width = width, color = WColors.GlassBorderSoft, shape = shape)
}

/**
 * 布局节奏：所有主页面顶部留白、区块间距从这里取。
 * 参考 8pt 栅格，只保留三档：紧凑 12、标准 20、大区块 32。
 */
object Rhythm {
    val tight = Space.x3
    val normal = Space.x5
    val section = Space.x8
}
