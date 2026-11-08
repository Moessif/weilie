package com.gakj.weilie.core.photo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 一张取证照片的档案（与磁盘上的 jpg 一一对应）。
 *
 * 之所以要「记录」而不只是存图：现场拍照的价值一半在画面、一半在「拍的时候站在哪、驻留在哪个小区」。
 * 所以坐标、水印文本、可疑设备类型、关联的地图标点 ID 都随图一起落盘，之后点地图标点能原样翻出来。
 */
@Serializable
data class PhotoRecord(
    val id: String = UUID.randomUUID().toString(),
    val ts: Long,
    /** PHOTO = 普通拍摄；SUSPICIOUS = 可疑设备；MARK = 仅标记 */
    val kind: String = "PHOTO",
    /** 可疑设备类型（仅 kind=SUSPICIOUS 时有值），如「隐蔽摄像头」 */
    val deviceType: String = "",
    val note: String = "",
    val lat: Double,
    val lon: Double,
    val accuracyM: Float = 0f,
    /** 相对照片目录的文件名 */
    val file: String,
    /** 相机界面当时看到的那些水印行（逐行文本），用于详情页原样回放 */
    val lines: List<String> = emptyList(),
    /** 关联的地图标点 id（WsMsg.Marker.photoId） */
    val markerId: String = "",
) {
    /** 地图上显示的标题：可疑设备优先显示类型（deviceType 是内部枚举码，必须翻成中文再展示） */
    val title: String
        get() = when {
            kind == "SUSPICIOUS" && deviceType.isNotBlank() -> "可疑设备 · ${deviceTypeLabel(deviceType)}"
            kind == "SUSPICIOUS" -> "可疑设备"
            else -> "取证照片"
        }

    private fun deviceTypeLabel(code: String): String = when (code) {
        "HIDDEN_CAMERA" -> "隐蔽摄像头"
        "SUSPICIOUS_CAMERA" -> "可疑摄像头"
        "SUSPICIOUS_DEVICE" -> "可疑设备"
        "OTHER" -> "其他"
        else -> if (code.all { it.code > 127 }) code else "其他可疑设备"
    }
}

/**
 * 轻量照片库：目录里放 jpg，索引是同目录的 index.jsonl（一行一条 [PhotoRecord]）。
 *
 * 刻意不用 Room / DataStore（项目规格约定）：追加写一行即可，读取时逐行解析、坏行跳过，
 * 这样即使写到一半被杀进程也不会把整库读崩。
 */
class PhotoStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val index: File get() = File(dir, "index.jsonl")

    fun ensure() {
        if (!dir.exists()) dir.mkdirs()
    }

    fun newFile(prefix: String = "IMG"): File {
        ensure()
        return File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    fun add(record: PhotoRecord) {
        ensure()
        runCatching { index.appendText(json.encodeToString(PhotoRecord.serializer(), record) + "\n") }
    }

    fun list(): List<PhotoRecord> = runCatching {
        if (!index.exists()) emptyList()
        else index.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString(PhotoRecord.serializer(), line) }.getOrNull() }
            .sortedByDescending { it.ts }
    }.getOrElse { emptyList() }

    fun byId(id: String): PhotoRecord? = list().firstOrNull { it.id == id }

    /** 记录对应的图片文件（可能不存在，调用方要自己兜底） */
    fun fileOf(record: PhotoRecord): File = File(dir, record.file)

    fun latest(): PhotoRecord? = list().firstOrNull()
}
