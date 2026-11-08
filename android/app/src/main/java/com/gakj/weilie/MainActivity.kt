package com.gakj.weilie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gakj.weilie.ui.WeilieApp as WeilieNav
import com.gakj.weilie.ui.theme.ThemeController
import com.gakj.weilie.ui.theme.WeilieTheme

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
        setContent {
            WeilieTheme {
                WeilieNav()
            }
        }
    }
}
