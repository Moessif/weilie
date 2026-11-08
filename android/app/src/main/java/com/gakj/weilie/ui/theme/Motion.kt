package com.gakj.weilie.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 「围猎」动效规范：统一时长/曲线/弹簧，全 App 复用。
 * 原则：进场快出场慢；位移用缓动、微交互用弹簧； Recording 类常驻动效用 repeatable。
 */
object Motion {

    // 时长
    val fast = 160
    val normal = 260
    val slow = 420

    // 曲线
    val EaseEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    // 常用 spec
    fun <T> enter() = tween<T>(normal, easing = EaseEmphasized)
    fun <T> exit() = tween<T>(fast, easing = EaseOut)
    fun <T> fade() = tween<T>(normal, easing = EaseInOut)

    // 弹簧：按钮按压、卡片拖拽、信号条
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    // 导航转场：滑入 + 淡入
    const val NAV_DURATION = 300
}
