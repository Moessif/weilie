package com.gakj.weilie.ui.team

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** v0.19：团队房间管理（关闭房间）——服务端 v1.3+ 的 /api/teams/{id}/close?code= */
object TeamApi {

    suspend fun closeRoom(httpBase: String, roomId: String, joinCode: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = httpBase.trim().trimEnd('/')
                require(base.isNotBlank() && joinCode.isNotBlank()) { "服务器地址或队伍码为空" }
                val url = URL("$base/api/teams/$roomId/close?code=$joinCode")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                try {
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
        }
}