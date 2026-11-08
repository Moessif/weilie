@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.gakj.weilie.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * 多人协同 WebSocket 消息协议 v1（服务端副本）。
 * 必须与 Android 客户端 core/sync/Protocol.kt 保持同一套字段名与 type 判别值，
 * 任何修改必须两侧同步。传输层：JSON 文本帧，判别字段 "type"。
 */
@Serializable
@JsonClassDiscriminator("type")
sealed class WsMsg {

    // ---------- 客户端 -> 服务端 ----------

    @Serializable
    @SerialName("hello")
    data class Hello(
        val roomId: String,
        val userId: String,
        val name: String,
        val role: String, // CAPTAIN / MEMBER / OBSERVER
    ) : WsMsg()

    @Serializable
    @SerialName("loc")
    data class Loc(
        val userId: String,
        val lat: Double,
        val lon: Double,
        val acc: Double? = null,
        val ts: Long,
        val onTarget: Boolean = false,
    ) : WsMsg()

    /** 采样节流上报：不打全量，只报关键项（全量数据走导出/云上传） */
    @Serializable
    @SerialName("sample")
    data class SamplePing(
        val userId: String,
        val lat: Double,
        val lon: Double,
        val rsrp: Int? = null,
        val onTarget: Boolean = false,
        val ts: Long,
    ) : WsMsg()

    @Serializable
    @SerialName("chat")
    data class Chat(
        val from: String,
        val fromName: String,
        val text: String,
        val ts: Long,
    ) : WsMsg()

    @Serializable
    @SerialName("photo")
    data class PhotoRef(
        val userId: String,
        val photoId: String,
        val lat: Double,
        val lon: Double,
        val note: String = "",
        val kind: String = "NORMAL", // NORMAL / SUSPICIOUS
        val ts: Long,
    ) : WsMsg()

    /** 队长下发/重划任务范围（圆=以合适半径扫清；多边形=分块）。
     *  多块任务区：每块一条 Zone 消息，第一块 replace=true 表示清空旧任务区重新划分。 */
    @Serializable
    @SerialName("zone")
    data class Zone(
        val by: String,
        val kind: String, // CIRCLE / POLYGON
        val centerLat: Double? = null,
        val centerLon: Double? = null,
        val radiusM: Double? = null,
        val polygon: List<List<Double>> = emptyList(), // [ [lat,lon], ... ]
        val assignees: List<String> = emptyList(),
        val note: String = "",
        val replace: Boolean = false,
        val ts: Long,
    ) : WsMsg()

    /** 队长转让（仅现队长可发）：服务端校验后改角色并广播 state + 系统聊天 */
    @Serializable
    @SerialName("promote")
    data class Promote(
        val from: String,
        val to: String,
        val ts: Long,
    ) : WsMsg()

    /** 地图标记（可疑设备特殊标记等），队长可见全部队员标记 */
    @Serializable
    @SerialName("marker")
    data class Marker(
        val by: String,
        val byName: String,
        val lat: Double,
        val lon: Double,
        val kind: String = "FLAG", // FLAG / SUSPICIOUS / ARRIVED
        val note: String = "",
        val ts: Long,
        /** 关联的取证照片记录 id，点击标点可回看照片与验真码 */
        val photoId: String = "",
        /** 可疑设备类型（kind=SUSPICIOUS 时） */
        val deviceType: String = "",
    ) : WsMsg()

    @Serializable
    @SerialName("ping")
    data class Ping(val ts: Long = System.currentTimeMillis()) : WsMsg()

    // ---------- 服务端 -> 客户端 ----------

    @Serializable
    @SerialName("state")
    data class State(
        val roomId: String,
        val members: List<MemberView> = emptyList(),
        /** 任务区全部分块（多块任务划分）。v1.1 起以此为准 */
        val zones: List<Zone> = emptyList(),
        /** @deprecated v1.0 单块字段，仅为过渡兼容保留；新代码一律读 zones */
        @Deprecated("用 zones") val zone: Zone? = null,
        val markers: List<Marker> = emptyList(),
        /** 聊天历史（服务端保留最近 200 条，重连后随 state 下发补齐） */
        val chats: List<Chat> = emptyList(),
    ) : WsMsg()

    @Serializable
    @SerialName("chatEcho")
    data class ChatEcho(val chat: Chat) : WsMsg()

    @Serializable
    @SerialName("photoAck")
    data class PhotoAck(val photoId: String, val ok: Boolean, val url: String? = null) : WsMsg()

    @Serializable
    @SerialName("bye")
    data class Bye(val userId: String, val reason: String = "") : WsMsg()

    @Serializable
    @SerialName("pong")
    data class Pong(val ts: Long) : WsMsg()
}

/** 房间内单个成员的对外视图（state.members 列表项） */
@Serializable
data class MemberView(
    val userId: String,
    val name: String,
    val role: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val acc: Double? = null,
    val onTarget: Boolean = false,
    val lastTs: Long = 0,
    val sampleCount: Int = 0,
)
