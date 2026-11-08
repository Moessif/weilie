package com.gakj.weilie.ui.map

/**
 * 地图图层类型抽象：
 * NORMAL = 普通地图（默认，Canvas 自绘网格底图）；
 * SATELLITE = 卫星图层（需接入真实地图 SDK 渲染）。
 */
enum class MapLayerType(val label: String) {
    NORMAL("普通"),
    SATELLITE("卫星"),
}

/**
 * 地图引擎抽象：UI 侧只负责把用户选择的图层类型通知引擎。
 * 接入百度地图时实现此接口（渲染 NORMAL / SATELLITE 底图），
 * 并把实现实例赋值给 [activeMapEngine]，UI 无需改动。
 */
interface MapEngine {
    /** 切换底图图层类型 */
    fun setLayer(type: MapLayerType)

    /** 生命周期钩子（引擎需要时覆写） */
    fun onResume() {}

    fun onPause() {}
}

/** 空实现：Canvas 自绘模式下无操作 */
object NoopMapEngine : MapEngine {
    override fun setLayer(type: MapLayerType) = Unit
}

/** 当前激活的地图引擎；默认空实现，lead 接入百度 SDK 后替换 */
var activeMapEngine: MapEngine = NoopMapEngine
