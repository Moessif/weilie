package com.gakj.weilie.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 入房令牌（协议 v1.4）：用队伍码向服务端换取短时效令牌，
 * 连接 WebSocket 时带上 `&token=`，防止伪造 userId / 抢占队长。
 *
 * 兼容：服务端未启用令牌（404/501）时返回 null，调用方按旧方式连接。
 */
object TeamToken {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        httpBase: String,
        roomId: String,
        joinCode: String,
        userId: String,
        name: String,
        role: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val base = httpBase.trim().trimEnd('/')
            if (base.isBlank() || roomId.isBlank() || joinCode.isBlank()) return@runCatching null
            val url = URL("$base/api/teams/$roomId/token")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = buildString {
                append("{\"code\":\"").append(joinCode.replace("\"", "")).append("\",")
                append("\"userId\":\"").append(userId.replace("\"", "")).append("\",")
                append("\"name\":\"").append(name.replace("\"", "")).append("\",")
                append("\"role\":\"").append(role.replace("\"", "")).append("\"}")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}
