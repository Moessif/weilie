package com.gakj.weilie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import com.gakj.weilie.ui.WeilieApp as WeilieNav
import com.gakj.weilie.ui.theme.ThemeController
import com.gakj.weilie.ui.theme.WColors
import com.gakj.weilie.ui.theme.WeilieTheme
import com.gakj.weilie.ui.theme.resolveDark

/**
 * 唯一 Activity：全应用 Compose 导航（ui.WeilieApp）。
 * 与 Manifest 的 Application 类 com.gakj.weilie.WeilieApp 同名不同物，
 * 故这里用 import alias `WeilieNav` 引用 Composable 版本。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 在 setContent 之前先按持久化主题着色，避免启动瞬间闪一帧默认配色
        ThemeController.install(this)
        // 窗口底色跟随主题：themes.xml 里固定写死的是深色，浅色主题下必须先改掉，
        // 否则系统栏区域、键盘弹出时的留白会露出深蓝底，看起来像「浅色主题没生效」。
        applyWindowChrome(dark = resolveDark(ThemeController.spec, ThemeController.systemDarkNow(this)))
        setContent {
            val spec = ThemeController.spec
            val systemDark = isSystemInDarkTheme()
            val dark = resolveDark(spec, systemDark)
            // 主题切换（手动或跟随系统）时同步系统栏图标明暗：浅色主题下白图标在浅色页面上看不见
            SideEffect {
                applyWindowChrome(dark)
            }
            WeilieTheme {
                WeilieNav()
            }
        }
    }

    /** 系统状态栏/导航栏图标明暗 + 窗口底色随主题切换。 */
    private fun applyWindowChrome(dark: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.decorView.setBackgroundColor(WColors.Bg0.toArgb())
    }
}
