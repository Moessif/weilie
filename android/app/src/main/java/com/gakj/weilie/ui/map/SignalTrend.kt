package com.gakj.weilie.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gakj.weilie.core.rating.SignalRating
import com.gakj.weilie.ui.theme.MonoText
import com.gakj.weilie.ui.theme.WColors

private const val RS_MIN = -140
private const val RS_MAX = -40

/**
 * RSRP 趋势曲线（Basement.md：下半=信号趋势曲线）。
 * Y 轴线性映射 -140..-40 dBm，X 轴按索引均分；三条浅色参考线（-120/-80/-40）+ 右侧刻度小字；
 * 折线颜色随 SignalRating 等级，下方半透明渐变填充；数据点叠加显示（最近 8 个）；
 * 点数不足 2 个时居中显示「等待采样…」。高度固定 48dp，不撑高数据栏。
 */
@Composable
fun SignalTrend(history: List<Int>, modifier: Modifier = Modifier, showScale: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        // ---------- 图表区（右侧留 22dp 给刻度小字） ----------
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (history.size < 2) {
                Text(
                    "等待采样…",
                    style = MaterialTheme.typography.labelSmall,
                    color = WColors.OnBgDim,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val last = history.last()
                val level = SignalRating.level(last)
                val lineColor = if (level < 0) WColors.OnBgDim
                else Color(SignalRating.colorArgb(level).toInt())
                Canvas(Modifier.fillMaxSize()) {
                    val span = (RS_MAX - RS_MIN).toFloat()
                    fun yOf(v: Int): Float =
                        size.height * (1f - (v.coerceIn(RS_MIN, RS_MAX) - RS_MIN) / span)
                    // 参考线：-40（顶）/ -80（0.4 高）/ -120（0.8 高）
                    listOf(0f, 0.4f, 0.8f).forEach { frac ->
                        val y = size.height * frac
                        drawLine(
                            WColors.GridLine,
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                    }
                    val stepX = size.width / (history.size - 1)
                    // 折线路径
                    val line = Path()
                    history.forEachIndexed { i, v ->
                        val x = i * stepX
                        if (i == 0) line.moveTo(x, yOf(v)) else line.lineTo(x, yOf(v))
                    }
                    // 曲线下方半透明渐变填充（折线色 alpha 0.18 → 0）
                    val fill = Path()
                    history.forEachIndexed { i, v ->
                        val x = i * stepX
                        if (i == 0) fill.moveTo(x, yOf(v)) else fill.lineTo(x, yOf(v))
                    }
                    fill.lineTo(size.width, size.height)
                    fill.lineTo(0f, size.height)
                    fill.close()
                    drawPath(
                        fill,
                        Brush.verticalGradient(
                            listOf(lineColor.copy(alpha = 0.18f), Color.Transparent),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )
                    drawPath(line, lineColor, style = Stroke(width = 1.5.dp.toPx()))
                    // 数据点：最近 8 个，半径 1.5dp
                    val from = (history.size - 8).coerceAtLeast(0)
                    for (i in from until history.size) {
                        drawCircle(
                            lineColor,
                            radius = 1.5.dp.toPx(),
                            center = Offset(i * stepX, yOf(history[i])),
                        )
                    }
                }
                // 右上角当前值 + 等级小字（普通模式只说人话，不露 dBm）
                Text(
                    if (showScale) "$last ${if (level < 0) "无信号" else SignalRating.label(level)}"
                    else if (level < 0) "无信号" else SignalRating.label(level),
                    style = MonoText.style.copy(fontSize = 10.sp),
                    color = lineColor,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .background(WColors.MapLabelBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        // ---------- 右侧刻度小字（与参考线对齐：-40 顶 / -80 0.4 高 / -120 0.8 高） ----------
        if (showScale) {
            Box(Modifier.width(22.dp).fillMaxHeight()) {
                Text(
                    "-40", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = WColors.OnBgDim,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-5).dp),
                )
                Text(
                    "-80", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = WColors.OnBgDim,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = 13.dp),
                )
                Text(
                    "-120", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = WColors.OnBgDim,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = 32.dp),
                )
            }
        }
    }
}
