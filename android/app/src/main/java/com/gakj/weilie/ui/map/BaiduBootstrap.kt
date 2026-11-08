package com.gakj.weilie.ui.map

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baidu.mapapi.SDKInitializer

/**
 * 百度地图 SDK 初始化（含隐私合规前置声明）。
 *
 * 合规顺序：首启向导的法律声明页即为隐私告知，用户勾选「我已阅读并同意」并点「进入围猎」之后
 * 才调用本对象的 [init]，形成「先同意、后初始化」的证据链（答辩材料可作合规点说明）。
 * 老用户（已同意过）在 Application 启动时补初始化。
 *
 * 任何初始化失败（AK 未绑定 / 无网络 / SO 缺失）都不影响业务：地图自动回退 Canvas 自绘，
 * 采集、拟合、导出、多人协同全部照常工作。
 */
object BaiduBootstrap {

    /** 地图 SDK 是否可用；Compose 状态，初始化完成后地图组件会自动从 Canvas 切换到百度地图 */
    var ready by mutableStateOf(false)
        private set

    /** 是否已执行过初始化（无论成功失败，避免重复调用 SDK） */
    var initialized: Boolean = false
        private set

    /** 最近一次初始化失败原因（排查用，不展示给用户） */
    @Volatile
    var lastError: String? = null
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        ready = runCatching {
            SDKInitializer.setAgreePrivacy(context.applicationContext, true)
            SDKInitializer.initialize(context.applicationContext)
            true
        }.onFailure { lastError = it.message }.getOrDefault(false)
    }
}
