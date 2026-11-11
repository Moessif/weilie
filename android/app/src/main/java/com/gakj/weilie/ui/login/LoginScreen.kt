package com.gakj.weilie.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.gakj.weilie.ui.common.GlassTextButton
import com.gakj.weilie.ui.common.GlassTextField
import com.gakj.weilie.ui.common.ScreenTopBar
import com.gakj.weilie.ui.common.toast
import com.gakj.weilie.ui.theme.GlassCard
import com.gakj.weilie.ui.theme.GlowButton
import com.gakj.weilie.ui.theme.Space
import com.gakj.weilie.ui.theme.WColors

/**
 * 登录页（STRICT_SPEC §1.6）：账号/密码 + 登录 + 游客模式。
 * 本期本地校验：账号 >=4 字符即登录成功，字段落 weilie_prefs。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf(AccountStore.account()) }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTopBar(title = "登录", onBack = onBack)
        Spacer(Modifier.height(Space.x6))

        // 头像与欢迎语
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AvatarCircle(84.dp, null, logged = false)
        }
        Spacer(Modifier.height(Space.x4))
        Text(
            "欢迎来到围猎",
            style = MaterialTheme.typography.headlineSmall,
            color = WColors.OnBg,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "登录后可同步组织信息与协同身份",
            style = MaterialTheme.typography.bodySmall,
            color = WColors.OnBgDim,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Space.x6))

        GlassCard(Modifier.padding(horizontal = Space.screenH)) {
            Column {
                GlassTextField(user, { user = it; err = null }, label = "账号", placeholder = "至少 4 个字符")
                Spacer(Modifier.height(Space.x3))
                GlassTextField(pass, { pass = it; err = null }, label = "密码", placeholder = "请输入密码", isPassword = true)
                if (err != null) {
                    Spacer(Modifier.height(Space.x2))
                    Text(err!!, style = MaterialTheme.typography.bodySmall, color = WColors.Danger)
                }
                Spacer(Modifier.height(Space.x4))
                GlowButton(
                    "登 录",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = user.isNotBlank() && pass.isNotBlank(),
                ) {
                    val u = user.trim()
                    if (u.length < 4) {
                        err = "账号至少需要 4 个字符"
                    } else {
                        AccountStore.login(u)
                        toast(ctx, "登录成功，欢迎 $u")
                        onDone()
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.x4))
        // 游客模式：不登录直接返回大厅
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GlassTextButton("游客模式（暂不登录）") { onDone() }
        }
        Spacer(Modifier.height(Space.x8))
        Text(
            "账号仅保存在本机，用于生成协同身份与导出署名",
            style = MaterialTheme.typography.labelSmall,
            color = WColors.OnBgDim.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
}
