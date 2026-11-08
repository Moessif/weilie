package com.gakj.weilie.ui.team

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 队伍码二维码编码器。
 *
 * 早期版本是本地手写的 Version 1/2 编码器，Reed-Solomon 实现有缺陷，创建房间进入
 * 房间管理页时必崩（java 栈：QrCodecKt.gfMul → reedSolomon → qrMatrix → QrCodeImage）。
 * 现已改为调用工程里已有的 ZXing core（扫码那边本来就用它），零新增依赖：
 * 版本 / 纠错等级 / 掩码全部由库自动选择，容量不再受限。
 */

// ---------- 对外 API ----------

/** 生成二维码布尔矩阵（行优先，true = 深色模块）；返回 null 表示编码失败。 */
fun qrMatrix(text: String): List<BooleanArray>? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = matrix.width
    val h = matrix.height
    List(h) { y -> BooleanArray(w) { x -> matrix.get(x, y) } }
}.getOrNull()

// ---------- 二维码展示组件 ----------

/** 玻璃底白面二维码卡片（扫描器要求暗模块在浅底上） */
@Composable
fun QrCodeImage(text: String, size: Int = 200, modifier: Modifier = Modifier) {
    val matrix = androidx.compose.runtime.remember(text) { qrMatrix(text) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Canvas(Modifier.size(size.dp)) {
                val m = matrix ?: return@Canvas
                val n = m.size
                if (n <= 0) return@Canvas
                val cell = this.size.minDimension / n
                val dark = Color(0xFF0B1020)
                for (y in 0 until n) for (x in 0 until n) {
                    if (m[y][x]) {
                        drawRect(
                            dark,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
        if (matrix == null) {
            // 编码失败时降级为文字展示，绝不抛异常
            androidx.compose.material3.Text(
                text,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}
