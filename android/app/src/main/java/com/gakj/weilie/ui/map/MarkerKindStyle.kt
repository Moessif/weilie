package com.gakj.weilie.ui.map

import com.gakj.weilie.core.sync.WsMsg

/**
 * 地图上「拍照标点 / 手动旗标」的视觉区分（用户反馈：普通取证、隐蔽摄像头、
 * 可疑摄像头、可疑设备在地图上一模一样，现场分不清哪个是哪个）。
 *
 * 三种信息叠加，任何一种都能单独认出类型：
 * 1. **emoji 图形**：📷 普通取证 / 🕵 隐蔽摄像头 / 📹 可疑摄像头 / ⚠ 可疑设备 / 📄 其他 / 🚩 手动旗标；
 * 2. **底色**：青=普通、紫=隐蔽、红=可疑摄像头、橙=可疑设备，与「危险程度」一致；
 * 3. **图例文案**：[label]，用于详情/图例。
 *
 * 为什么用 emoji 而不是再画一套矢量图标：百度 Marker 的图标是位图，emoji 直接由系统字体
 * 渲染（Android 8+ 支持彩色 emoji），既省掉手绘路径，又和相机页的取证类型 chip 语义一致。
 * 注意：emoji 只在「图标/图例」这种强视觉元素上用，正文与按钮继续用 Material 图标 + 主题色，
 * 避免整页花花绿绿（P2「emoji 统一性」的结论）。
 */
enum class MarkerKindVisual(
    val emoji: String,
    /** 图标底色（与 MarkerKindVisual 语义绑定，不随明暗主题变化，保证地图上可辨认） */
    val bg: Int,
    /** 图标前景色（emoji 本身自带颜色，这里只用于文字标签） */
    val fg: Int,
    val label: String,
) {
    PHOTO("📷", 0xFF22D3EE.toInt(), 0xFF052E33.toInt(), "普通取证"),
    HIDDEN_CAM("🕵", 0xFFA855F7.toInt(), 0xFFFFFFFF.toInt(), "隐蔽摄像头"),
    SUSPICIOUS_CAM("📹", 0xFFF87171.toInt(), 0xFFFFFFFF.toInt(), "可疑摄像头"),
    SUSPICIOUS_DEVICE("⚠", 0xFFF59E0B.toInt(), 0xFF0B1020.toInt(), "可疑设备"),
    OTHER("📄", 0xFF94A3B8.toInt(), 0xFF0B1020.toInt(), "其他取证"),
    FLAG("🚩", 0xFFF59E0B.toInt(), 0xFF0B1020.toInt(), "手动标点"),
    ;

    companion object {
        /**
         * 口径与相机页 `PhotoKindChip` 写入的 (kind, deviceType) 对齐：
         * PHOTO + "" = 普通取证；SUSPICIOUS + HIDDEN_CAM / SUSPICIOUS_CAM / SUSPICIOUS_DEVICE；
         * PHOTO + OTHER = 其他。
         *
         * @param isPhoto 是否有取证照片（photoId 非空）。没有照片、又不是可疑设备 → 手动旗标。
         */
        fun of(kind: String, deviceType: String, isPhoto: Boolean): MarkerKindVisual {
            val k = kind.uppercase()
            val d = deviceType.uppercase()
            return when {
                !isPhoto && k != "SUSPICIOUS" -> FLAG
                d == "OTHER" -> OTHER
                d.contains("HIDDEN") -> HIDDEN_CAM
                d.contains("CAM") -> SUSPICIOUS_CAM
                d.isNotBlank() || k == "SUSPICIOUS" -> SUSPICIOUS_DEVICE
                else -> PHOTO
            }
        }
    }
}

/** 地图标点的视觉类型（双端渲染共用：百度覆盖物 + Canvas 兜底地图） */
fun WsMsg.Marker.visualKind(): MarkerKindVisual =
    MarkerKindVisual.of(kind = kind, deviceType = deviceType, isPhoto = photoId.isNotBlank())
