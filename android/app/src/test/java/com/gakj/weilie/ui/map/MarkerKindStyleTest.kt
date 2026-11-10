package com.gakj.weilie.ui.map

import com.gakj.weilie.core.sync.WsMsg
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 地图标点视觉分类：取证类型 → emoji/配色。
 *
 * 这里的映射是用户可见的「一眼区分」契约（普通取证/隐蔽摄像头/可疑摄像头/可疑设备/其他），
 * 相机 chip 写入的 deviceType 历史上存在 `HIDDEN_CAM` 与 `HIDDEN_CAMERA` 两套写法，
 * 分类必须都能认出来，否则地图上会退化成统一的旗子。
 */
class MarkerKindStyleTest {

    @Test
    fun `普通取证照片`() {
        assertEquals(MarkerKindVisual.PHOTO, MarkerKindVisual.of("PHOTO", "", isPhoto = true))
        assertEquals("📷", MarkerKindVisual.of("PHOTO", "", isPhoto = true).emoji)
    }

    @Test
    fun `隐蔽摄像头两种历史写法都识别`() {
        assertEquals(MarkerKindVisual.HIDDEN_CAM, MarkerKindVisual.of("SUSPICIOUS", "HIDDEN_CAM", true))
        assertEquals(MarkerKindVisual.HIDDEN_CAM, MarkerKindVisual.of("SUSPICIOUS", "HIDDEN_CAMERA", true))
    }

    @Test
    fun `可疑摄像头两种历史写法都识别`() {
        assertEquals(MarkerKindVisual.SUSPICIOUS_CAM, MarkerKindVisual.of("SUSPICIOUS", "SUSPICIOUS_CAM", true))
        assertEquals(MarkerKindVisual.SUSPICIOUS_CAM, MarkerKindVisual.of("SUSPICIOUS", "SUSPICIOUS_CAMERA", true))
    }

    @Test
    fun `可疑设备与其他`() {
        assertEquals(MarkerKindVisual.SUSPICIOUS_DEVICE, MarkerKindVisual.of("SUSPICIOUS", "SUSPICIOUS_DEVICE", true))
        assertEquals(MarkerKindVisual.SUSPICIOUS_DEVICE, MarkerKindVisual.of("SUSPICIOUS", "", true))
        assertEquals(MarkerKindVisual.OTHER, MarkerKindVisual.of("PHOTO", "OTHER", true))
    }

    @Test
    fun `手动标点不是照片时统一为旗子`() {
        assertEquals(MarkerKindVisual.FLAG, MarkerKindVisual.of("FLAG", "", isPhoto = false))
        assertEquals(MarkerKindVisual.FLAG, MarkerKindVisual.of("FLAG", "HIDDEN_CAM", isPhoto = false))
    }

    @Test
    fun `Marker 扩展按 photoId 判断是否照片`() {
        val flag = WsMsg.Marker(by = "u1", byName = "张三", lat = 39.9, lon = 116.4, ts = 1L)
        assertEquals(MarkerKindVisual.FLAG, flag.visualKind())

        val photo = WsMsg.Marker(
            by = "u1", byName = "张三", lat = 39.9, lon = 116.4, ts = 2L,
            photoId = "ph-1", deviceType = "SUSPICIOUS_CAM",
        )
        assertEquals(MarkerKindVisual.SUSPICIOUS_CAM, photo.visualKind())
    }
}
