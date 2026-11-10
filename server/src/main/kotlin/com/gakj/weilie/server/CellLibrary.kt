package com.gakj.weilie.server

import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

private val cellLog = LoggerFactory.getLogger("weilie.cells")

/**
 * 基站库图层的数据代理与缓存。
 *
 * 数据来源：OpenCelliD（https://opencellid.org，CC BY-SA 4.0，须署名）。
 * 该服务对移动端并不友好：单次区域查询最多 4 km²、单次最多返回 1000 条、
 * 免费额度 5000 次/天，因此 App 不直连，全部走本服务端的「瓦片缓存 + 单小区缓存」：
 *  - 同一块 0.018°×0.018° 瓦片（约 2.0 km × 2.0 km×cos(lat)，恒 < 4 km²）30 天内只上游拉一次；
 *  - 单小区查询（MCC/MNC/LAC-CI）命中缓存 30 天，未命中缓存 7 天（避免反复打空）。
 *
 * API Key 只从环境变量 `OPENCELLID_KEY` / 系统属性 `opencellid.key` /
 * `dataDir/opencellid.key` 读取，绝不写进代码仓库，也不下发到 App。
 */
class CellLibrary(private val dataDir: Path) {

    /** 区域查询结果（与 App 端 CellSite 同构） */
    @Serializable
    data class CellSiteDto(
        val mcc: Int = 0,
        val mnc: Int = 0,
        val lac: Int = 0,
        val cellid: Long = 0,
        val rat: String = "",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val rangeM: Int = 0,
        val samples: Int = 0,
        val updatedSec: Long = 0,
    )

    @Serializable
    data class NearResponse(
        val ok: Boolean = true,
        val count: Int = 0,
        val sites: List<CellSiteDto> = emptyList(),
        val fetched: Int = 0,
        val message: String? = null,
    )

    @Serializable
    data class QueryResponse(
        val ok: Boolean = false,
        val site: CellSiteDto? = null,
        val message: String? = null,
    )

    @Serializable
    data class StatsResponse(
        val ok: Boolean = true,
        val keyConfigured: Boolean,
        val tiles: Int,
        val cachedCells: Int,
        val upstreamToday: Int,
        val upstreamLimit: Int,
        val source: String = SOURCE_ATTRIBUTION,
    )

    @Serializable
    private data class TileFile(val ts: Long, val sites: List<CellSiteDto>)

    @Serializable
    private data class SingleFile(val ts: Long, val ok: Boolean, val site: CellSiteDto? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val cellDir: Path = dataDir.resolve("cells")
    private val tileDir: Path = cellDir.resolve("tile")
    private val singleDir: Path = cellDir.resolve("single")

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** 内存里的瓦片缓存：key = "$latIdx_$lonIdx" */
    private val tileMemo = ConcurrentHashMap<String, TileFile>()
    /** 同一瓦片并发请求去重（避免一次平移把同一块瓦片打十几次） */
    private val inflight = ConcurrentHashMap<String, Boolean>()

    private var budgetDay: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private var budgetUsed: Int = 0

    private val apiKey: String? = run {
        val fromEnv = System.getenv("OPENCELLID_KEY")?.trim()
        val fromProp = System.getProperty("opencellid.key")?.trim()
        val fromFile = runCatching {
            val f = cellDir.resolve("opencellid.key")
            if (Files.exists(f)) Files.readString(f).trim() else null
        }.getOrNull()
        listOf(fromEnv, fromProp, fromFile).firstOrNull { !it.isNullOrBlank() }
    }

    init {
        if (apiKey == null) {
            cellLog.warn("未配置 OpenCelliD API Key（环境变量 OPENCELLID_KEY），基站库接口将只返回缓存内容")
        }
        runCatching {
            Files.createDirectories(tileDir)
            Files.createDirectories(singleDir)
        }
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 查询（lat, lon）半径 [radiusM] 米内的已知小区，按距离升序返回前 [limit] 条。
     * 只读缓存 + 按需补齐缺失瓦片；超出每日上游额度的部分直接跳过（不报错）。
     */
    fun near(lat: Double, lon: Double, radiusM: Int, limit: Int): NearResponse {
        if (!lat.isFinite() || !lon.isFinite() || abs(lat) > 85.0 || abs(lon) > 180.0) {
            return NearResponse(ok = false, message = "坐标非法：lat=$lat lon=$lon")
        }
        if (lat == 0.0 && lon == 0.0) {
            return NearResponse(ok = false, message = "坐标为空（0,0），拒绝查询")
        }
        val r = radiusM.coerceIn(100, 5000).toDouble()
        val maxItems = limit.coerceIn(1, 1000)
        val dLat = r / METERS_PER_DEG_LAT
        val dLon = r / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.1))

        val tiles = tilesFor(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        var fetched = 0
        val merged = ArrayList<CellSiteDto>(256)
        for (t in tiles) {
            val cached = readTile(t) ?: continue
            if (cached.first) fetched++ // first=true 表示这次真的打了上游
            merged += cached.second.sites
        }
        if (apiKey == null) {
            return NearResponse(
                ok = false,
                sites = merged.take(maxItems),
                count = merged.size,
                fetched = 0,
                message = "服务端未配置 OpenCelliD API Key，仅返回已有缓存",
            )
        }
        val filtered = merged
            .map { it to distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= r }
            .sortedBy { it.second }
            .map { it.first }
        return NearResponse(
            ok = true,
            count = filtered.size.coerceAtMost(maxItems),
            sites = filtered.take(maxItems),
            fetched = fetched,
            message = null,
        )
    }

    /** 按 MCC/MNC/LAC(或 TAC)/CI 查单个小区位置 */
    fun lookup(mcc: Int, mnc: Int, lac: Int, cellid: Long): QueryResponse {
        if (mcc <= 0 || cellid <= 0) {
            return QueryResponse(ok = false, message = "参数不完整：需要 MCC、MNC、TAC/LAC、CI")
        }
        val file = singleDir.resolve("$mcc-$mnc-$lac-$cellid.json")
        val cached = runCatching {
            if (Files.exists(file)) {
                val f = json.decodeFromString(SingleFile.serializer(), Files.readString(file))
                val ttl = if (f.ok) SINGLE_TTL_MS else NEGATIVE_TTL_MS
                if (System.currentTimeMillis() - f.ts <= ttl) f else null
            } else null
        }.getOrNull()
        if (cached != null) {
            return if (cached.ok && cached.site != null) {
                QueryResponse(ok = true, site = cached.site)
            } else {
                QueryResponse(ok = false, message = "库中无该小区记录")
            }
        }
        if (apiKey == null) {
            return QueryResponse(ok = false, message = "服务端未配置 OpenCelliD API Key，无法查询")
        }
        if (!takeBudget()) {
            return QueryResponse(ok = false, message = "今日上游查询额度已用完，请明天再试")
        }
        val site = runCatching { fetchSingle(mcc, mnc, lac, cellid) }.getOrNull()
        runCatching {
            Files.writeString(file, json.encodeToString(SingleFile.serializer(), SingleFile(System.currentTimeMillis(), site != null, site)))
        }
        return if (site != null) {
            QueryResponse(ok = true, site = site)
        } else {
            QueryResponse(ok = false, message = "库中无该小区记录（该小区可能较新或未被众包采集）")
        }
    }

    fun stats(): StatsResponse {
        val tiles = runCatching { Files.list(tileDir).use { s -> s.count().toInt() } }.getOrDefault(0)
        val singles = runCatching { Files.list(singleDir).use { s -> s.count().toInt() } }.getOrDefault(0)
        return StatsResponse(
            keyConfigured = apiKey != null,
            tiles = tiles,
            cachedCells = tiles + singles,
            upstreamToday = budgetUsed,
            upstreamLimit = DAILY_UPSTREAM_LIMIT,
        )
    }

    /** 供测试与运维：预热一片区域（同步） */
    fun warm(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Int {
        var n = 0
        for (t in tilesFor(minLat, minLon, maxLat, maxLon)) {
            val r = readTile(t) ?: continue
            if (r.first) n++
        }
        return n
    }

    // ---------------------------------------------------------------- 瓦片缓存

    private data class TileKey(val latIdx: Int, val lonIdx: Int) {
        val id: String get() = "${latIdx}_$lonIdx"
        fun bbox(): DoubleArray {
            val lat0 = latIdx * TILE_DEG
            val lon0 = lonIdx * TILE_DEG
            return doubleArrayOf(lat0, lon0, lat0 + TILE_DEG, lon0 + TILE_DEG)
        }
    }

    private fun tilesFor(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<TileKey> {
        val i0 = floor(maxOf(minLat, -85.0) / TILE_DEG).toInt()
        val i1 = floor(minOf(maxLat, 85.0) / TILE_DEG).toInt()
        val j0 = floor(maxOf(minLon, -180.0) / TILE_DEG).toInt()
        val j1 = floor(minOf(maxLon, 180.0) / TILE_DEG).toInt()
        val out = ArrayList<TileKey>(16)
        for (i in i0..i1) for (j in j0..j1) out += TileKey(i, j)
        return out
    }

    /**
     * 读取一块瓦片。返回 Pair(是否本次真的请求了上游, 瓦片内容)；
     * 缓存不存在且额度/权限不足时返回 null。
     */
    private fun readTile(t: TileKey): Pair<Boolean, TileFile>? {
        val memo = tileMemo[t.id]
        if (memo != null && System.currentTimeMillis() - memo.ts <= TILE_TTL_MS) {
            return false to memo
        }
        val file = tileDir.resolve("${t.id}.json")
        val fromDisk = runCatching {
            if (Files.exists(file)) json.decodeFromString(TileFile.serializer(), Files.readString(file)) else null
        }.getOrNull()
        if (fromDisk != null && System.currentTimeMillis() - fromDisk.ts <= TILE_TTL_MS) {
            tileMemo[t.id] = fromDisk
            return false to fromDisk
        }
        // 需要上游补齐：去重 + 额度检查
        if (apiKey == null) return fromDisk?.let { false to it }
        if (inflight.putIfAbsent(t.id, true) != null) {
            // 别的请求正在拉这块：先用过期数据顶一下，没有就跳过
            return fromDisk?.let { false to it }
        }
        try {
            if (!takeBudget()) return fromDisk?.let { false to it }
            return try {
                val bb = t.bbox()
                val sites = fetchArea(bb[0], bb[1], bb[2], bb[3])
                val tf = TileFile(System.currentTimeMillis(), sites)
                tileMemo[t.id] = tf
                runCatching { Files.writeString(file, json.encodeToString(TileFile.serializer(), tf)) }
                true to tf
            } catch (e: Exception) {
                cellLog.warn("瓦片 {} 拉取失败：{}", t.id, e.message)
                fromDisk?.let { false to it }
            }
        } finally {
            inflight.remove(t.id)
        }
    }

    // ---------------------------------------------------------------- 上游 HTTP

    private fun fetchArea(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<CellSiteDto> {
        val key = apiKey ?: return emptyList()
        val bbox = String.format(
            Locale.US, "%.6f,%.6f,%.6f,%.6f", minLat, minLon, maxLat, maxLon,
        )
        val url = "$UPSTREAM/cell/getInArea?key=$key&BBOX=$bbox&format=json&limit=1000"
        val body = getJson(url) ?: return emptyList()
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        obj["error"]?.let {
            cellLog.warn("上游返回错误：{}", it.jsonPrimitive.content)
            return emptyList()
        }
        val arr = obj["cells"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { parseCell(it.jsonObject) }.getOrNull() }
    }

    private fun fetchSingle(mcc: Int, mnc: Int, lac: Int, cellid: Long): CellSiteDto? {
        val key = apiKey ?: return null
        val url = "$UPSTREAM/cell/get?key=$key&mcc=$mcc&mnc=$mnc&lac=$lac&cellid=$cellid&format=json"
        val body = getJson(url) ?: return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (obj.containsKey("error")) return null
        return runCatching { parseCell(obj) }.getOrNull()
    }

    private fun getJson(url: String): String? {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "weilie-server/0.6 (+https://github.com/Moessif/weilie)")
            .header("Accept", "application/json")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            cellLog.warn("上游 HTTP {}：{}", resp.statusCode(), resp.body().take(200))
            return null
        }
        return resp.body()
    }

    private fun parseCell(o: JsonObject): CellSiteDto? {
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lac = o["tac"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            ?: o["lac"]?.jsonPrimitive?.intOrNull
            ?: 0
        return CellSiteDto(
            mcc = o["mcc"]?.jsonPrimitive?.intOrNull ?: 0,
            mnc = o["mnc"]?.jsonPrimitive?.intOrNull ?: 0,
            lac = lac,
            cellid = o["cellid"]?.jsonPrimitive?.longOrNull ?: 0L,
            rat = o["radio"]?.jsonPrimitive?.content ?: "",
            lat = lat,
            lon = lon,
            rangeM = o["range"]?.jsonPrimitive?.intOrNull ?: 0,
            samples = o["samples"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedSec = o["updated"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    // ---------------------------------------------------------------- 杂项

    /** 每日上游额度（留 500 次余量给手工排查） */
    private fun takeBudget(): Boolean {
        synchronized(this) {
            val today = LocalDate.now(ZoneOffset.UTC)
            if (today != budgetDay) {
                budgetDay = today
                budgetUsed = 0
            }
            if (budgetUsed >= DAILY_UPSTREAM_LIMIT) return false
            budgetUsed++
            return true
        }
    }

    companion object {
        /** OpenCelliD 接口根 */
        const val UPSTREAM = "https://opencellid.org"
        /** 数据许可与署名（必须在界面上展示） */
        const val SOURCE_ATTRIBUTION = "OpenCelliD · CC BY-SA 4.0 · https://opencellid.org"
        /** 瓦片边长（度）。0.018° 纬度 ≈ 2.0 km，恒满足上游 4 km² 的单次上限 */
        const val TILE_DEG = 0.018
        const val METERS_PER_DEG_LAT = 111_320.0
        /** 瓦片缓存有效期：30 天（基站位置变化很慢） */
        const val TILE_TTL_MS = 30L * 24 * 3600 * 1000
        /** 单小区命中缓存 30 天、未命中 7 天 */
        const val SINGLE_TTL_MS = 30L * 24 * 3600 * 1000
        const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000
        /** 每日上游请求上限（免费额度 5000/天） */
        const val DAILY_UPSTREAM_LIMIT = 4500

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * METERS_PER_DEG_LAT
            val dLon = (lon2 - lon1) * METERS_PER_DEG_LAT * cos(Math.toRadians((lat1 + lat2) / 2.0))
            return hypot(dLat, dLon)
        }
    }
}

/** 基站库 REST 路由：/api/cells/near、/api/cells/cell、/api/cells/stats */
fun Route.cellLibraryRoutes(lib: CellLibrary) {
    get("/api/cells/near") {
        val p = call.request.queryParameters
        val lat = p["lat"]?.toDoubleOrNull()
        val lon = p["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) {
            call.respond(CellLibrary.NearResponse(ok = false, message = "缺少参数 lat / lon"))
            return@get
        }
        val r = p["r"]?.toIntOrNull() ?: 900
        val limit = p["limit"]?.toIntOrNull() ?: 300
        call.respond(lib.near(lat, lon, r, limit))
    }
    get("/api/cells/cell") {
        val p = call.request.queryParameters
        val mcc = p["mcc"]?.toIntOrNull()
        val mnc = p["mnc"]?.toIntOrNull() ?: 0
        val lac = p["lac"]?.toIntOrNull() ?: p["tac"]?.toIntOrNull()
        val cellid = p["cellid"]?.toLongOrNull() ?: p["ci"]?.toLongOrNull()
        if (mcc == null || lac == null || cellid == null) {
            call.respond(CellLibrary.QueryResponse(ok = false, message = "缺少参数 mcc / lac(或 tac) / cellid"))
            return@get
        }
        call.respond(lib.lookup(mcc, mnc, lac, cellid))
    }
    get("/api/cells/stats") {
        call.respond(lib.stats())
    }
}

/** 供日志使用：把毫秒时间戳格式化成 UTC 日期，便于排障 */
internal fun isoDayOf(epochMs: Long): String =
    LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC).toString()
