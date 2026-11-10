package com.gakj.weilie.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gakj.weilie.ui.theme.WColors

/**
 * 圆圈感叹号：点开一段通俗说明（给民警看的，不讲技术细节）。
 * 用法：在标题/参数行后面放一个 [HelpHint]，文案集中写在调用点，避免散落。
 */
@Composable
fun HelpHint(
    title: String,
    sections: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    tint: Color = WColors.Primary,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(22.dp)
            .background(tint.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.55f), CircleShape)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = "说明", tint = tint, modifier = Modifier.size(14.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = WColors.Bg1,
            title = { Text(title, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    sections.forEachIndexed { i, (head, body) ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        if (head.isNotBlank()) {
                            Text(head, style = MaterialTheme.typography.titleSmall, color = WColors.Primary)
                            Spacer(Modifier.height(3.dp))
                        }
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("知道了", color = WColors.Primary)
                }
            },
        )
    }
}

/** 说明文案集中定义：新建任务页 */
object HelpTexts {

    val targetParams: List<Pair<String, String>> = listOf(
        "这个框填什么" to
            "上级（市局/分局）下发的目标基站编号，一般是一串数字，或者经纬度加半径。" +
            "把原文整段粘进来就行，不用自己拆。",
        "ECI（4G 小区编号）" to
            "4G 基站的「小区」编号，最长 9 位数字。例如 217546496。" +
            "一个基站通常带 3 个小区（各管 120°），编号不同，测哪个就填哪个。",
        "NCI（5G 小区编号）" to
            "5G 基站的「小区」编号，最长 12 位数字。例如 5677244420。" +
            "如果原文写成「1386046-4」这种两段式，直接粘进来也能认，会按 gNB-CID 拆。",
        "LAC + CI" to
            "老式写法：位置区号（LAC/TAC）+ 小区号（CI）。两段都要有，" +
            "例如「LAC 12345 CI 67890」。常见于 2G/3G 时代的通报。",
        "经纬度 + 半径" to
            "上级直接给坐标和范围时用，例如「31.2304 121.4737 R=800」。" +
            "半径不填也能识别，但覆盖范围只能按 1000 米参考圆显示，实测时要自己走边界。",
        "填错了会怎样" to
            "参数不符合规格时不会让你创建任务，下面会红字说明错在哪里，" +
            "比如编号超长、经纬度越界。改对之后按钮才会亮。",
    )

    val threshold: List<Pair<String, String>> = listOf(
        "这一条在算什么" to
            "走测时手机每秒记一个点，每个点带一个信号强度（RSRP，单位 dBm，是个负数）。" +
            "只有「强于等于这个阈值」的点才算有效覆盖，用来画覆盖范围、算面积。",
        "怎么算信号强、怎么算弱" to
            "RSRP 越接近 0 越强。给民警的土办法：" +
            "≥ -85 dBm 满格（手机贴着基站的感觉）；" +
            "-85 ~ -100 dBm 正常可通话、能上网；" +
            "-100 ~ -110 dBm 偏弱，通话可能断续、数据很慢；" +
            "低于 -110 dBm 基本就是边缘或脱网。",
        "阈值该选多少" to
            "默认 -110 dBm 是「还能用」的行业经验线，测出来的范围最接近实际可服务区域。" +
            "要更保守（只算信号好的地方）就往上推到 -100 或 -95；" +
            "要极宽松（连边缘都要算）就往下拉到 -120。",
        "两个特殊提醒" to
            "① 邻区（手机能看到但没连上的小区）要单独满足 RSRP 门槛才算数；" +
            "② 脱离目标的点只画轨迹、不参与面积，不会把范围撑大。",
    )

    val heatParams: List<Pair<String, String>> = listOf(
        "热力图是什么" to
            "把一路测来的信号强度，按位置铺成一张彩色底图：一眼看出哪片区域信号好、哪片是盲区。",
        "配色" to
            "青绿 = 信号强；黄色 = 中等；橙红 = 弱。" +
            "颜色是通过「强信号阈值 / 弱信号阈值」两条线算出来的，不是固定的。",
        "网格间隔" to
            "图上每隔多远算一个颜色点。15 米适合小区、街巷精细看；50 米适合大范围快速看。" +
            "调小更细腻但更吃内存。",
        "扩散半径" to
            "每个实测点向周围「晕开」多远。调大颜色更连贯（像真实覆盖），调小更贴近原始点位。",
        "透明度" to
            "热力图盖在地图上的浓淡。太浓会挡住路名和楼栋，看路时调淡一些。",
        "强 / 弱信号阈值" to
            "强阈值越低，越多区域被算成强信号；弱阈值越高，越多区域被算成弱（红）。" +
            "默认 -85 / -105 dBm，是按「满格」和「勉强能用」两条线定的。",
    )

    val silentTrack: List<Pair<String, String>> = listOf(
        "它是干什么的" to
            "不开屏幕、不看地图、手机揣兜里，靠震动告诉你「现在有没有盯着目标基站」。适合蹲守、夜间摸排。",
        "三种状态怎么震" to
            "① 主区命中（手机就挂在目标小区上）：每 1 秒短震一次，节奏稳，像心跳；" +
            "② 邻区命中（能看到目标、但没连上）：每 0.3 秒急促短震，提示「快到边界了」；" +
            "③ 完全脱离（既连不上也看不到）：一直震动不停，直到你走回去或者手动关掉。",
        "会不会响" to
            "不会。全程只震动、不出声、不弹窗、不点亮屏幕。通知栏有一条常驻通知，用来点「停止」和防止系统杀掉。",
        "费电吗" to
            "后台持续读基站信息 + 震动，比平时费电。长时间用建议带充电宝，测完记得关。",
        "关不掉怎么办" to
            "三个地方都能停：工具箱里的开关、地图底部的状态条（点一下就停）、通知栏的「停止」。",
    )
}
