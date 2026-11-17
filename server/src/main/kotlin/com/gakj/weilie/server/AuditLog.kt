package com.gakj.weilie.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 审计日志（v1.3）：所有会改变房间状态的操作追加写 `data/audit.jsonl`。
 * 只追加、不修改，供事后追责与取证链核对；单文件超过 64MB 自动滚动为 `.1`。
 */
@Serializable
data class AuditEvent(
    val ts: Long,
    val action: String,
    val roomId: String = "",
    val userId: String = "",
    val detail: Map<String, String> = emptyMap(),
)

class AuditLog(private val dataDir: Path) {

    private val lock = Any()
    private val file: Path get() = dataDir.resolve("audit.jsonl")

    fun log(
        action: String,
        roomId: String = "",
        userId: String = "",
        detail: Map<String, String> = emptyMap(),
    ) {
        val event = AuditEvent(System.currentTimeMillis(), action, roomId, userId, detail)
        val line = wsJson.encodeToString(event) + "\n"
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dataDir)
                rotateIfNeeded()
                Files.writeString(
                    file,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    private fun rotateIfNeeded() {
        val f = file.toFile()
        if (f.exists() && f.length() > 64L * 1024 * 1024) {
            val rolled = dataDir.resolve("audit.jsonl.1")
            runCatching { Files.deleteIfExists(rolled) }
            runCatching { Files.move(f.toPath(), rolled) }
        }
    }
}
