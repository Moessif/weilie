package com.gakj.weilie.core.cellmap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * 基站库客户端：只跟自家服务端说话（`/api/cells/near`、`/api/cells/cell`、`/api/cells/stats`），不直连 OpenCelliD。
 *
 * 这么做的原因：API Key 不能下发到 App，且服务端有瓦片缓存能把日限额 5000 次摊薄
 * （实测一次 900 m 查询首次吃 4 次上游，之后同区域 0 次）。
 *
 * 约定：**任何异常都不上抛**，统一转成 `ok=false` + 中文 message，UI 直接展示即可。
 */
class CellLibraryClient(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(CIO) { engine { requestTimeout = 20_000 } }
    }

    private val memo = Mutex()
    private var memoKey: String? = null
    private var memoAt: Long = 0L
    private var memoValue: CellNearResponse? = null

    /** 附近基站：服务端按瓦片补缓存后返回，`fetched` 是本次真正打上游的瓦片数 */
    suspend fun near(lat: Double, lon: Double, radiusM: Int = 900, limit: Int = 100): CellNearResponse = try {
        val text = client.get("${baseUrl.trimEnd('/')}/api/cells/near") {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("r", radiusM)
            parameter("limit", limit)
        }.bodyAsText()
        json.decodeFromString(CellNearResponse.serializer(), text)
    } catch (t: Throwable) {
        CellNearResponse(ok = false, message = "基站库请求失败：${t.message ?: t.javaClass.simpleName}")
    }

    /** 按编号查单个基站（MCC/MNC/LAC 或 TAC/CID） */
    suspend fun lookup(mcc: Int, mnc: Int, lac: Int, cellid: Long): CellQueryResponse = try {
        val text = client.get("${baseUrl.trimEnd('/')}/api/cells/cell") {
            parameter("mcc", mcc)
            parameter("mnc", mnc)
            parameter("lac", lac)
            parameter("cellid", cellid)
        }.bodyAsText()
        json.decodeFromString(CellQueryResponse.serializer(), text)
    } catch (t: Throwable) {
        CellQueryResponse(ok = false, message = "基站查询失败：${t.message ?: t.javaClass.simpleName}")
    }

    /**
     * 带 60 秒记忆化的附近查询：地图拖动/采样刷新会频繁触发，
     * 同一屏幕区域内没必要每次都打服务端。
     */
    suspend fun nearCached(lat: Double, lon: Double, radiusM: Int = 900, limit: Int = 100): CellNearResponse {
        val key = String.format(java.util.Locale.US, "%.3f|%.3f|%d|%d", lat, lon, radiusM, limit)
        val now = System.currentTimeMillis()
        memo.withLock {
            val cached = memoValue
            if (cached != null && memoKey == key && now - memoAt < 60_000L) return cached
        }
        val fresh = near(lat, lon, radiusM, limit)
        if (fresh.ok) {
            memo.withLock {
                memoKey = key
                memoAt = System.currentTimeMillis()
                memoValue = fresh
            }
        }
        return fresh
    }

    fun invalidate() {
        memoKey = null
        memoValue = null
    }

    fun close() {
        runCatching { client.close() }
    }

    companion object {
        /** 常用查询半径（米）：默认 900，步进给 UI 用 */
        val RADIUS_STEPS = listOf(300, 500, 900, 1500, 2500)
        val DEFAULT_RADIUS_M = 900

        /** 从列表里挑距离 (lat,lon) 最近的一条，用于「离我最近的理论站」提示 */
        fun nearest(sites: List<CellSite>, lat: Double, lon: Double): CellSite? =
            sites.filter { it.isValid() }
                .minByOrNull { it.distanceM(lat, lon) }

        /** 把距离格式化成「320 m」/「1.4 km」 */
        fun formatDistance(meters: Double): String = when {
            meters.isNaN() -> "—"
            meters < 1000.0 -> "${meters.roundToInt()} m"
            else -> String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        }
    }
}
