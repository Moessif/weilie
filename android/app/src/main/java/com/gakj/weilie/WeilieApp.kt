package com.gakj.weilie

import android.app.Application
import android.content.Context
import com.gakj.weilie.service.SamplingForegroundService
import com.gakj.weilie.ui.AppGraph
import com.gakj.weilie.ui.Route
import com.gakj.weilie.ui.map.BaiduBootstrap

/**
 * Application 入口（Manifest 中 android:name=".WeilieApp" 指定）。
 * 负责持有全局 Context，并提前注册走测采样的常驻通知渠道。
 */
class WeilieApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        // 提前注册低重要性通知渠道，避免首次启动前台服务时渠道未建
        SamplingForegroundService.ensureChannel(this)
        // 老用户（已阅法律声明）补初始化百度地图；新用户由向导同意后再初始化（合规顺序）
        val legalAccepted = getSharedPreferences(AppGraph.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Route.LEGAL_KEY, false)
        if (legalAccepted) BaiduBootstrap.init(this)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
