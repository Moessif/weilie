package com.gakj.weilie.core.photo

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * 拍照标点「归属任务」的兼容性测试。
 *
 * 背景：v0.11.0 给 [PhotoRecord] 加了 taskId（不同任务不再显示彼此的拍照标点）。
 * index.jsonl 是追加写的，老版本写的行没有这个字段，必须能继续读出来（默认空串），
 * 否则升级后历史取证记录会整段解析失败。
 */
class PhotoTaskIdTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun legacy_record_without_task_id_decodes_with_blank_task_id() {
        val raw = """
            {"id":"old-1","ts":1700000000000,"kind":"PHOTO","deviceType":"","note":"取证",
             "lat":39.9,"lon":116.4,"accuracyM":5.0,"file":"old-1.jpg","lines":[],"markerId":"m-1"}
        """.trimIndent()
        val rec = json.decodeFromString<PhotoRecord>(raw)
        assertEquals("", rec.taskId)
        assertEquals("old-1", rec.id)
    }

    @Test
    fun task_id_round_trips() {
        val rec = PhotoRecord(
            id = "p-1",
            ts = 1700000000000,
            kind = "SUSPICIOUS",
            deviceType = "HIDDEN_CAM",
            note = "隐蔽摄像头",
            lat = 39.9,
            lon = 116.4,
            accuracyM = 4f,
            file = "p-1.jpg",
            lines = listOf("测试"),
            markerId = "ph-1",
            taskId = "20261110-2151",
        )
        val back = json.decodeFromString<PhotoRecord>(json.encodeToString(PhotoRecord.serializer(), rec))
        assertEquals("20261110-2151", back.taskId)
        assertEquals("可疑设备 · 隐蔽摄像头", back.title)
    }
}
