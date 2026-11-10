package com.gakj.weilie.ui

import android.content.Context
import android.content.SharedPreferences
import com.gakj.weilie.WeilieApp
import com.gakj.weilie.core.ai.AiClient
import com.gakj.weilie.core.model.CoverageTask
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.parsing.TaskInputParser
import com.gakj.weilie.core.photo.PhotoStore
import com.gakj.weilie.core.sampling.SamplingController
import com.gakj.weilie.core.store.SessionStore
import com.gakj.weilie.core.sync.RoomClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * UI 层全局状态图：当前采样控制器、待建任务、AI 单例、偏好读写。
 * 刻意不用 ViewModel/Room/DataStore（规格约定）：轻量 object + SharedPreferences + StateFlow。
 */
object AppGraph {

    const val PREFS_NAME = "weilie_prefs"

    /** 默认协同服务器（阿里云 ECS us-east-1 已部署的后端），可在设置里改 */
    const val DEFAULT_SERVER = "http://47.89.181.85:8080"

    /** 全局协程域（采样控制器、RoomClient、AI 请求共用） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cachedCtx: Context? = null

    val ctx: Context
        get() = cachedCtx ?: WeilieApp.appContext.also { cachedCtx = it }

    val prefs: SharedPreferences
        get() = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 采集控制器 ----------

    var currentController: SamplingController? = null
        private set

    /** 新建任务页产出、地图页消费 */
    var pendingTask: CoverageTask? = null

    /** 新建任务页选择的拟合阈值（dBm），专家面板可改 */
    var pendingThresholdDbm: Int = -110

    /**
     * 取得 taskId 对应的控制器；不存在或任务不符时重建。
     * task 传 null（如进程被杀后从路由恢复）时用占位目标兜底。
     */
    fun ensure(context: Context, taskId: String, task: CoverageTask?): SamplingController {
        currentController?.let { cur ->
            if (cur.task.id == taskId) return cur
            cur.stop()
        }
        val real = task ?: fallbackTask(taskId)
        val c = SamplingController(context.applicationContext, appScope, real)
        applyGatePrefs(c)
        currentController = c
        return c
    }

    /** 需求 2：把设置页保存的 GPS 安检门槛应用到控制器（默认 50m / 3m） */
    private fun applyGatePrefs(c: SamplingController) {
        runCatching {
            c.maxAccuracyM = prefs.getFloat(Route.PREF_GATE_ACCURACY, 50f).toDouble()
            c.minDistanceM = prefs.getFloat(Route.PREF_GATE_MIN_DIST, 1f).toDouble()
        }
    }

    /**
     * A1 切换追踪基站：用新目标重建当前控制器。
     * 磁盘上的会话样本保留（taskId 不变）；新目标即刻生效。
     */
    fun retarget(context: Context, task: CoverageTask): SamplingController {
        currentController?.stop()
        val c = SamplingController(context.applicationContext, appScope, task)
        applyGatePrefs(c)
        currentController = c
        pendingTask = task
        return c
    }

    /**
     * 恢复历史会话时的兜底任务：必须从磁盘 manifest 还原原始标题/目标，
     * 否则会出现「恢复后任务被改名成『恢复的走测任务』、目标丢失、A1 显示 ECI --」的数据错。
     */
    private fun fallbackTask(taskId: String): CoverageTask {
        val m = runCatching { store.loadManifest(taskId) }.getOrNull()
        val fmt = runCatching { TargetFormat.valueOf(m?.targetFormat ?: "") }.getOrNull()
        // 关键：必须用 rawInput 重新解析出完整目标（ECI/NCI/经纬度…）。
        // 只塞 rawInput 会让 parsed 字段全为 null → 地图页显示「未设目标」、命中判定永远 false。
        val parsed = runCatching { TaskInputParser.parse(m?.targetRawInput ?: "").target }.getOrNull()
        return CoverageTask(
            id = taskId,
            title = m?.title?.ifBlank { null } ?: "恢复的走测任务",
            target = parsed ?: TargetCell(
                format = fmt ?: TargetFormat.ECI,
                rawInput = m?.targetRawInput ?: "",
            ),
            createdAtEpochMs = m?.startedAtEpochMs ?: System.currentTimeMillis(),
        )
    }

    // ---------- AI 单例 ----------

    val aiClient: AiClient by lazy {
        AiClient(
            prefs.getString("ai_key", "") ?: "",
            (prefs.getString("ai_base_url", "") ?: "").ifBlank { "https://api.deepseek.com" },
            (prefs.getString("ai_model", "") ?: "").ifBlank { "deepseek-chat" },
        )
    }

    /** 设置页改动后调用，热更新 AI 配置 */
    fun refreshAiConfig() {
        aiClient.configure(
            prefs.getString("ai_key", "") ?: "",
            (prefs.getString("ai_base_url", "") ?: "").ifBlank { "https://api.deepseek.com" },
            (prefs.getString("ai_model", "") ?: "").ifBlank { "deepseek-chat" },
        )
    }

    // ---------- 会话存储单例 ----------

    val store: SessionStore by lazy { SessionStore(ctx) }

    /** 取证照片库（相机界面拍完即落盘，地图标点按 photoId 回查） */
    val photoStore: PhotoStore by lazy { PhotoStore(java.io.File(ctx.filesDir, "photos")) }

    // ---------- 照片在线验真 ----------

    /**
     * 照片登记回执库（code/sig/sha256），与 PhotoRecord 分开存 verify.jsonl，
     * 避免给旧索引加字段造成反序列化污染。
     */
    val verifyStore: com.gakj.weilie.core.verify.VerifyStore by lazy {
        com.gakj.weilie.core.verify.VerifyStore(java.io.File(ctx.filesDir, "photos"))
    }

    /** 照片验真客户端：服务器地址跟随设置页，需要时把 ws:// 归一成 http:// */
    val photoVerifier: com.gakj.weilie.core.verify.PhotoVerifier by lazy {
        com.gakj.weilie.core.verify.PhotoVerifier(serverHttpUrl(), verifyStore)
    }

    /** 当前验证服务器 REST 根地址（设置页 PREF_SERVER 优先，默认 DEFAULT_SERVER） */
    fun serverHttpUrl(): String {
        val raw = prefs.getString(Route.PREF_SERVER, "")?.trim().orEmpty().trimEnd('/')
        val http = when {
            raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
            raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
            else -> raw
        }
        return http.ifBlank { DEFAULT_SERVER }
    }

    // ---------- 协同 ----------

    /** 当前房间客户端（同时只保留一个房间连接，进入新房间前断开旧的） */
    var currentRoom: RoomClient? = null

    /** 最近一次加入/创建的房间号（回填加入框；也用于判断队长身份） */
    var lastJoinCode: String? = null

    /** 扫码识别出的房号（ScanScreen 写入，TeamScreen 消费） */
    var lastScanCode: String? = null

    /** 最近一次进入房间时的角色：CAPTAIN / MEMBER / OBSERVER */
    var lastRole: String? = null

    /** 本机用户 ID：首次使用生成 UUID 并持久化 */
    fun userId(): String =
        prefs.getString("user_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("user_id", it).apply()
        }
}
