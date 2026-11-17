package com.gakj.weilie.server

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 房间采样落盘（v1.3）：`data/rooms/<roomId>/samples.jsonl`，追加写。
 *
 * 目的：
 * 1. 服务重启后能重算任务区覆盖率（此前采样只在内存，重启即归零）；
 * 2. 服务端导出（KML/GeoJSON/CSV）有轨迹可导；
 * 3. 审计时可回溯「某时刻谁在哪」。
 *
 * 容量控制：读取时只取最后 [MAX_LOAD] 行；超过 64MB 滚动为 `.1`。
 */
class RoomSampleStore(private val dataDir: Path) {

    private val lock = Any()

    companion object {
        const val MAX_LOAD = 20_000
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }

    private fun dirOf(roomId: String): Path = dataDir.resolve("rooms").resolve(sanitize(roomId))

    fun fileOf(roomId: String): Path = dirOf(roomId).resolve("samples.jsonl")

    fun append(roomId: String, ping: WsMsg.SamplePing) {
        if (roomId.isBlank()) return
        val line = wsJson.encodeToString(ping) + "\n"
        synchronized(lock) {
            runCatching {
                val dir = dirOf(roomId)
                Files.createDirectories(dir)
                val f = dir.resolve("samples.jsonl")
                rotateIfNeeded(f)
                Files.writeString(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    /** 读取最近 [limit] 条；文件缺失或损坏时返回空列表（服务照常启动） */
    fun load(roomId: String, limit: Int = MAX_LOAD): List<WsMsg.SamplePing> {
        val f = fileOf(roomId).toFile()
        if (!f.isFile) return emptyList()
        return runCatching {
            val lines = f.readLines()
            val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
            tail.mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { wsJson.decodeFromString(WsMsg.SamplePing.serializer(), line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun delete(roomId: String) {
        synchronized(lock) {
            runCatching { dirOf(roomId).toFile().deleteRecursively() }
        }
    }

    private fun rotateIfNeeded(f: Path) {
        val file = f.toFile()
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val rolled = f.resolveSibling("samples.jsonl.1")
            runCatching { Files.deleteIfExists(rolled) }
            runCatching { Files.move(f, rolled) }
        }
    }

    /** roomId 只允许字母数字与短横线，避免路径穿越 */
    private fun sanitize(roomId: String): String =
        roomId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "unknown" }
}
