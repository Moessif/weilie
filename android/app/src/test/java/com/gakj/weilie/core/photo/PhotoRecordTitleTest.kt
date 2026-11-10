package com.gakj.weilie.core.photo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 取证照片标题口径：相机 chip 写入的是 `HIDDEN_CAM` / `SUSPICIOUS_CAM`，
 * 老版本写的是 `*_CAMERA`。两者都必须翻成中文，否则可疑摄像头会被显示成「可疑设备 · HIDDEN_CAM」。
 */
class PhotoRecordTitleTest {

    private fun record(kind: String, deviceType: String) = PhotoRecord(
        ts = 1L,
        kind = kind,
        deviceType = deviceType,
        lat = 39.9,
        lon = 116.4,
        file = "x.jpg",
    )

    @Test
    fun `普通取证照片`() {
        assertEquals("取证照片", record("PHOTO", "").title)
    }

    @Test
    fun `隐蔽摄像头新旧写法`() {
        assertEquals("可疑设备 · 隐蔽摄像头", record("SUSPICIOUS", "HIDDEN_CAM").title)
        assertEquals("可疑设备 · 隐蔽摄像头", record("SUSPICIOUS", "HIDDEN_CAMERA").title)
    }

    @Test
    fun `可疑摄像头新旧写法`() {
        assertEquals("可疑设备 · 可疑摄像头", record("SUSPICIOUS", "SUSPICIOUS_CAM").title)
        assertEquals("可疑设备 · 可疑摄像头", record("SUSPICIOUS", "SUSPICIOUS_CAMERA").title)
    }

    @Test
    fun `可疑设备与无类型`() {
        assertEquals("可疑设备 · 可疑设备", record("SUSPICIOUS", "SUSPICIOUS_DEVICE").title)
        assertEquals("可疑设备", record("SUSPICIOUS", "").title)
    }
}
