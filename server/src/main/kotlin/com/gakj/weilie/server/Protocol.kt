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
        /** 在线验真码：队友没有本机照片原件，靠它向服务器查登记信息 */
        val code: String = "",
    ) : WsMsg()

    /** 队长下发/重划任务范围（圆=以合适半径扫清；多边形=分块）。
     *  多块任务区：每块一条 Zone 消息，第一块 replace=true 表示清空旧任务区重新划分。 */
    @Serializable
    @SerialName("zone")
    data class Zone(
        /** 服务端分配的区域 id（v1.2）：删除/统计以它为准；老客户端为空 */
        val id: String = "",
        val by: String,
        val kind: String, // CIRCLE / POLYGON
        val centerLat: Double? = null,
        val centerLon: Double? = null,
        val radiusM: Double? = null,
        val polygon: List<List<Double>> = emptyList(), // [ [lat,lon], ... ]
        val assignees: List<String> = emptyList(),
        val note: String = "",
        val replace: Boolean = false,
        /** v1.3：软删除标记；数据保留用于审计/历史，客户端不再显示 */
        val deleted: Boolean = false,
        /** v1.10：待确认的转移目标 userId；为空表示无待处理转移 */
        val pendingTransferTo: String = "",
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

    /** 队员申请接任队长（v1.2）：服务端只转发给现任队长；队长同意后走 promote */
    @Serializable
    @SerialName("promoteRequest")
    data class PromoteRequest(
        val from: String,
        val to: String,
        val ts: Long,
    ) : WsMsg()

    /** 队长拒绝接任申请（v1.2）：服务端转发给申请人 */
    @Serializable
    @SerialName("promoteDecline")
    data class PromoteDecline(
        val from: String,
        val to: String,
        val ts: Long,
    ) : WsMsg()

    /** v1.10：发起任务区转移（创建者或队长），目标需确认 */
    @Serializable
    @SerialName("zoneTransferRequest")
    data class ZoneTransferRequest(
        val id: String,
        val by: String,
        val to: String,
        val ts: Long,
    ) : WsMsg()

    /** v1.10：接受任务区转移（仅目标本人） */
    @Serializable
    @SerialName("zoneTransferAccept")
    data class ZoneTransferAccept(
        val id: String,
        val by: String,
        val ts: Long,
    ) : WsMsg()

    /** v1.10：拒绝任务区转移（仅目标本人） */
    @Serializable
    @SerialName("zoneTransferDecline")
    data class ZoneTransferDecline(
        val id: String,
        val by: String,
        val ts: Long,
    ) : WsMsg()

    /** v1.10：清空全部任务区（仅队长，软删除，用于「重划」） */
    @Serializable
    @SerialName("zoneClear")
    data class ZoneClear(
        val by: String,
        val ts: Long,
    ) : WsMsg()

    /** 删除任务区（v1.2）：创建者本人或现任队长可删 */
    @Serializable
    @SerialName("zoneDelete")
    data class ZoneDelete(
        val by: String,
        val id: String,
        val ts: Long,
    ) : WsMsg()

    /** 单个任务区的完成度（v1.2，随 state 下发）：按 20m 网格统计区内被采样覆盖的比例 */
    @Serializable
    data class ZoneStat(
        val id: String,
        val covered: Int = 0,
        val total: Int = 0,
        val ratio: Double = 0.0,
        val samples: Int = 0,
    )

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
        /** 标点 id：删除时定位用；旧版本可能为空 */
        val id: String = "",
        /** 标点名称（民警自己起名，如「3 号楼配电房」）；空则按类型显示 */
        val name: String = "",
        /** 可见范围：空 = 全队可见；否则只有列表里的 userId（加上创建者自己）能看到 */
        val visibleTo: List<String> = emptyList(),
        /** 照片在线验真码：跨设备查看时用它向服务器查登记信息 */
        val photoCode: String = "",
    ) : WsMsg()

    /** 删除标点：只有创建者本人或队长有权删除 */
    @Serializable
    @SerialName("markerDelete")
    data class MarkerDelete(val id: String, val by: String) : WsMsg()

    @Serializable
    @SerialName("ping")
    data class Ping(val ts: Long = System.currentTimeMillis()) : WsMsg()

    // ---------- 服务端 -> 客户端 ----------

    // ---------- 语音对讲（客户端上行，服务端转发给同一房间的其他成员）----------

    @Serializable
    @SerialName("audioStart")
    data class AudioStart(
        val userId: String,
        val byName: String = "",
        val ts: Long = 0L,
    ) : WsMsg()

    @Serializable
    @SerialName("audioChunk")
    data class AudioChunk(
        val userId: String,
        val seq: Int = 0,
        val data: String = "",
        val ts: Long = 0L,
    ) : WsMsg()

    @Serializable
    @SerialName("audioStop")
    data class AudioStop(
        val userId: String,
        val ts: Long = 0L,
    ) : WsMsg()

    // ---------- 服务端 -> 客户端（state / chatEcho / photoAck / bye / pong）----------
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
        /** 任务区完成度（v1.2）；老服务端不下发时为空，客户端按无数据处理 */
        val zoneStats: List<ZoneStat> = emptyList(),
        /** v1.8：全队合并采样（降采样 ≤1500），供客户端拟合全队覆盖 */
        val teamSamples: List<SamplePoint> = emptyList(),
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

/** v1.8：团队合并采样的精简点（state.teamSamples 用，降采样下发） */
@Serializable
data class SamplePoint(
    val userId: String,
    val lat: Double,
    val lon: Double,
    val rsrp: Int? = null,
    val onTarget: Boolean = false,
    val ts: Long,
)

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
