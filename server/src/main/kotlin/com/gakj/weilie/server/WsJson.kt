package com.gakj.weilie.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全局共享 Json 配置（REST 与 WS 共用）：
 * - ignoreUnknownKeys：忽略未知字段，客户端先行加字段时不炸服务端；
 * - encodeDefaults：输出带默认值的字段，客户端解析更稳；
 * - classDiscriminator 固定 "type"，与协议 @JsonClassDiscriminator 一致。
 */
val wsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/**
 * WS 出站统一序列化入口：必须以多态基类型 [WsMsg] 序列化。
 * 若直接对具体子类（如 WsMsg.State）调用 encodeToString，会使用具体子类
 * 的序列化器，输出不带 "type" 判别字段，客户端 decodeFromString<WsMsg>
 * 将因缺少判别字段而丢弃消息。此函数保证所有服务端下发帧都带 "type"。
 */
fun encodeMsg(msg: WsMsg): String = wsJson.encodeToString<WsMsg>(msg)
