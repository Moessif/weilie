package com.gakj.weilie.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gakj.weilie.ui.common.AvatarCircle
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.KeyValueRow
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.fmtTs
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.SectionHeader
import com.gakj.weilie.ui.theme.WColors

/**
 * 个人中心（STRICT_SPEC §1.6）：头像、昵称、组织、登录记录、已登录设备、退出登录。
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var nick by remember { mutableStateOf(AccountStore.nickname()) }
    var org by remember { mutableStateOf(AccountStore.org()) }
    val records = remember { AccountStore.records() }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(title = "个人中心", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        // 头像与身份卡
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(64.dp, nick, logged = true)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(nick, style = MaterialTheme.typography.titleLarge, color = WColors.OnBg)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (org.isBlank()) "未设置所在组织" else org,
                        style = MaterialTheme.typography.bodySmall,
                        color = WColors.OnBgDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 资料
        SectionHeader("资料", modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            Column {
                GlassTextField(nick, { nick = it }, label = "昵称", placeholder = "展示给队友的名字")
                Spacer(Modifier.height(12.dp))
                GlassTextField(org, { org = it }, label = "所在组织", placeholder = "如：XX市公安局刑侦支队")
                Spacer(Modifier.height(18.dp))
                GlowButton("保存资料", modifier = Modifier.fillMaxWidth()) {
                    if (nick.isBlank()) {
                        toast(ctx, "昵称不能为空")
                    } else {
                        AccountStore.saveProfile(nick, org)
                        toast(ctx, "资料已保存")
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 登录记录
        SectionHeader("登录记录", modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            if (records.isEmpty()) {
                Text("暂无登录记录", style = MaterialTheme.typography.bodyMedium, color = WColors.OnBgDim)
            } else {
                Column {
                    records.take(10).forEach { (ts, dev) ->
                        KeyValueRow(fmtTs(ts), dev)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (records.size > 10) {
                        Text(
                            "共 ${records.size} 条，仅显示最近 10 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = WColors.OnBgDim,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 已登录设备
        SectionHeader("已登录设备", modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.padding(horizontal = 20.dp)) {
            KeyValueRow("本机", AccountStore.deviceModel(), WColors.Success)
            Spacer(Modifier.height(6.dp))
            KeyValueRow("设备标识", AccountStore.deviceId())
        }
        Spacer(Modifier.height(24.dp))

        // 退出登录
        GlassCard(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            onClick = { confirmLogout = true },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = WColors.Danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("退出登录", style = MaterialTheme.typography.titleSmall, color = WColors.Danger)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后需重新登录才能同步组织信息，确定退出？") },
            confirmButton = {
                TextButton({
                    confirmLogout = false
                    AccountStore.logout()
                    toast(ctx, "已退出登录")
                    onBack()
                }) { Text("退出", color = WColors.Danger) }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("取消") } },
        )
    }
}
