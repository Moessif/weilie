package com.gakj.weilie.core.store

import android.content.Context
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.SampleRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 会话清单：一次走测任务的元数据 */
@Serializable
data class SessionManifest(
    val taskId: String,
    val title: String,
    val targetRawInput: String,
    val targetFormat: String,
    val startedAtEpochMs: Long,
    var endedAtEpochMs: Long? = null,
    var sampleCount: Int = 0,
    val deviceModel: String = android.os.Build.MODEL ?: "",
    val appVersion: String = "0.1.0",
)

/**
 * 会话存储：append-only JSONL（每行一个 SampleRecord）+ manifest.json。
 * 目录：getExternalFilesDir/files 下的 sessions/{taskId}/
 * 设计取舍：暂用 JSONL 而非 Room（与 append-only 语义一致、免 KSP 构建链风险），后续可无缝迁移。
 */
class SessionStore(context: Context) {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val root: File = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("sessions")

    fun newSessionDir(taskId: String): File = root.resolve(taskId).apply { mkdirs() }

    fun samplesFile(taskId: String): File = root.resolve(taskId).resolve("samples.jsonl")

    /** 追加一条采样（调用方保证在采样线程串行调用） */
    fun appendSample(taskId: String, record: SampleRecord) {
        val f = samplesFile(taskId)
        if (!f.exists()) f.parentFile?.mkdirs()
        f.appendText(json.encodeToString(record) + "\n")
    }

    fun loadSamples(taskId: String): List<SampleRecord> {
        val f = samplesFile(taskId)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString<SampleRecord>(line) }.getOrNull()
        }
    }

    fun writeManifest(manifest: SessionManifest) {
        val dir = root.resolve(manifest.taskId).apply { mkdirs() }
        dir.resolve("manifest.json").writeText(json.encodeToString(manifest))
    }

    fun loadManifest(taskId: String): SessionManifest? {
        val f = root.resolve(taskId).resolve("manifest.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<SessionManifest>(f.readText()) }.getOrNull()
    }

    fun listSessions(): List<SessionManifest> =
        root.listFiles { d -> d.isDirectory }
            ?.mapNotNull { loadManifest(it.name) }
            ?.sortedByDescending { it.startedAtEpochMs }
            ?: emptyList()

    /**
     * 删除一个会话：整目录递归删除（manifest.json + samples.jsonl + 该会话的导出文件）。
     * 只允许删除 root 的直接子目录，避免 taskId 里带 ../ 时误删别处。
     */
    fun deleteSession(taskId: String): Boolean {
        if (taskId.isBlank()) return false
        val dir = root.resolve(taskId)
        if (!dir.exists()) return false
        val parent = runCatching { dir.canonicalFile.parentFile }.getOrNull() ?: return false
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return false
        if (parent != rootCanonical) return false
        return dir.deleteRecursively()
    }

    /** 删除全部会话，返回成功删除的会话数 */
    fun deleteAllSessions(): Int {
        val dirs = root.listFiles { d -> d.isDirectory } ?: return 0
        var n = 0
        dirs.forEach { if (runCatching { it.deleteRecursively() }.getOrDefault(false)) n++ }
        return n
    }

    fun exportCsvFile(taskId: String): File? {
        val samples = loadSamples(taskId)
        if (samples.isEmpty()) return null
        val out = root.resolve(taskId).resolve("export_${System.currentTimeMillis()}.csv")
        out.writeText(com.gakj.weilie.core.export.CsvExporter.export(samples))
        return out
    }
}
