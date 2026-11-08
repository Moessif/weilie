package com.gakj.weilie.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AI 客户端：deepseek 兼容 OpenAI chat API（v4.1 flash，/chat/completions）。
 * - key 自配；baseUrl 可切私有化端点（答辩合规话术）
 * - AiGuard：本地规则做提问边界（拒绝无关/敏感），不依赖云端
 */
class AiClient(
    private var apiKey: String,
    private var baseUrl: String = "https://api.deepseek.com",
    private var model: String = "deepseek-chat",
) {
    @Serializable
    data class AiMessage(val role: String, val content: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(CIO) { engine { requestTimeout = 60_000 } } }

    fun configure(key: String, url: String = baseUrl, m: String = model) {
        apiKey = key; baseUrl = url; model = m
    }

    suspend fun chat(messages: List<AiMessage>): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("未配置 AI key（设置页填入，或选择私有化端点）"))
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("messages", json.encodeToString(messages.map { buildJsonObject { put("role", it.role); put("content", it.content) } })
                .let { json.parseToJsonElement(it) })
        }
        return try {
            val resp = client.post("$baseUrl/chat/completions") {
                headers { append("Authorization", "Bearer $apiKey") }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val text = resp.bodyAsText()
            if (!resp.status.value.let { it in 200..299 }) {
                Result.failure(IllegalStateException("AI 服务返回 ${resp.status.value}: ${text.take(300)}"))
            } else {
                val content = json.parseToJsonElement(text).jsonObject["choices"]
                    ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                    ?.jsonObject?.get("content")?.jsonPrimitive?.content
                if (content != null) Result.success(content)
                else Result.failure(IllegalStateException("AI 响应格式异常"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- Prompt 构造 ----------

    fun systemPrompt(): String = """
        你是「围猎」App 内置的通信技术助手，服务对象是基层民警。
        职责：① 解答蜂窝网络/基站/信号/覆盖测绘相关技术问题（2G~5G、RSRP/RSRQ/SINR、
        ECI/NCI/PCI、频段频点、传播模型等）；② 结合用户提供的实测数据分析覆盖情况并给行动建议；
        ③ 对现场照片做场景分析（用户会附文字描述）。
        要求：回答用简体中文、口语化、给结论先行；涉及精确工程参数时说明置信度与限制；
        不提供任何规避执法、干扰通信、隐私侵犯类内容。遇到无关话题礼貌拒绝并拉回。
    """.trimIndent()

    fun sceneAnalysisPrompt(sceneHint: String, snapshotSummary: String): String =
        "现场情况：${sceneHint.ifBlank { "（未描述）" }}\n当前实测数据摘要：$snapshotSummary\n" +
            "请分析：1) 该场景下信号/覆盖特征说明了什么；2) 对搜捕行动的 2-3 条具体建议（走位、优先排查方向、需要补测的点）。"

    fun docAnswerPrompt(question: String, measuredContext: String): String =
        "问题：$question\n参考的实测上下文：${measuredContext.ifBlank { "无" }}\n请基于蜂窝网络原理回答，若实测上下文相关请引用。"

    companion object {
        /** 本地快照摘要（供 prompt，不含敏感位置精度） */
        fun summarize(servingText: String?, rsrp: Int?, onTarget: Boolean, sampleCount: Int, elapsedMin: Long): String =
            buildString {
                append("服务小区=${servingText ?: "未知"}; ")
                append("RSRP=${rsrp?.toString() ?: "无"} dBm; ")
                append("目标命中=${if (onTarget) "是" else "否"}; ")
                append("已采样本=$sampleCount 条 / 约 $elapsedMin 分钟")
            }
    }
}

/** 提问边界：本地白名单域 + 黑名单规则，先于云端过滤 */
object AiGuard {

    private val DOMAIN_HINTS = listOf(
        "基站", "信号", "覆盖", "小区", "频段", "频点", "rsrp", "rsrq", "sinr", "eci", "nci", "pci",
        "4g", "5g", "lte", "nr", "gsm", "cdma", "wcdma", "天线", "功率", "驻留", "邻区", "切换",
        "走位", "搜捕", "排查", "测向", "衰减", "传播", "模三", "干扰", "驻波", "屏蔽", "tap", "位置区",
        "lac", "tac", "earfcn", "nrarfcn", "dbm", "db", "卡", "sim", "运营商", "移动", "联通", "电信",
    )

    private val DENY_HINTS = listOf(
        "干扰器", "屏蔽器制作", "伪基站", "炸", "毒品", "攻击", "入侵", "破解", "枪",
        "翻墙", "赌博", "色情", "黑客",
    )

    /** 返回 null=允许；否则返回拒答文案 */
    fun check(question: String): String? {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return "请输入问题。"
        if (DENY_HINTS.any { q.contains(it) }) {
            return "该问题超出本助手的服务边界。本助手只解答蜂窝网络技术与覆盖实测相关问题。"
        }
        val inDomain = DOMAIN_HINTS.any { q.contains(it) }
        return if (!inDomain) {
            "这个问题似乎与基站覆盖实测无关。我可以解答：信号参数含义、小区识别（ECI/NCI/PCI）、" +
                "频段频点、覆盖走测方法、结合实测数据的行动建议等。换个问法试试？"
        } else null
    }
}
