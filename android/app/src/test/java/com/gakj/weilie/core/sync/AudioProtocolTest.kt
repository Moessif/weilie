package com.gakj.weilie.core.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音对讲消息的编解码单测。
 *
 * 关注三件事：
 * 1) 三帧消息都能带 "type" 判别字段往返；
 * 2) 服务端/旧客户端带来的未知字段被忽略（ignoreUnknownKeys）；
 * 3) 旧消息（Pong）仍然能解码，说明新增音频类型没有破坏协议。
 */
class AudioProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun audioStartRoundTrip() {
        val msg = WsMsg.AudioStart(userId = "u1", byName = "Tester", ts = 1000L)
        val text = json.encodeToString<WsMsg>(msg)
        assertTrue(text.contains("\"type\":\"audioStart\""))

        val back = json.decodeFromString<WsMsg>(text)
        assertTrue(back is WsMsg.AudioStart)
        back as WsMsg.AudioStart
        assertEquals("u1", back.userId)
        assertEquals("Tester", back.byName)
        assertEquals(1000L, back.ts)
    }

    @Test
    fun audioChunkRoundTrip() {
        val msg = WsMsg.AudioChunk(userId = "u2", seq = 7, data = "AAECAwQ=", ts = 2000L)
        val text = json.encodeToString<WsMsg>(msg)
        assertTrue(text.contains("\"type\":\"audioChunk\""))

        val back = json.decodeFromString<WsMsg>(text)
        assertTrue(back is WsMsg.AudioChunk)
        back as WsMsg.AudioChunk
        assertEquals("u2", back.userId)
        assertEquals(7, back.seq)
        assertEquals("AAECAwQ=", back.data)
        assertEquals(2000L, back.ts)
    }

    @Test
    fun audioStopRoundTrip() {
        val msg = WsMsg.AudioStop(userId = "u3", ts = 3000L)
        val text = json.encodeToString<WsMsg>(msg)
        assertTrue(text.contains("\"type\":\"audioStop\""))

        val back = json.decodeFromString<WsMsg>(text)
        assertTrue(back is WsMsg.AudioStop)
        back as WsMsg.AudioStop
        assertEquals("u3", back.userId)
        assertEquals(3000L, back.ts)
    }

    @Test
    fun unknownFieldOnAudioChunkIsIgnored() {
        // 服务端后续可能给音频帧补充字段，老客户端需要能继续解码。
        val text = """{"type":"audioChunk","userId":"u4","seq":1,"data":"AAAA","ts":4,"codec":"pcm16"}"""
        val back = json.decodeFromString<WsMsg>(text)
        assertTrue(back is WsMsg.AudioChunk)
        assertEquals("u4", (back as WsMsg.AudioChunk).userId)
    }

    @Test
    fun legacyPongStillDecodes() {
        val text = """{"type":"pong","ts":5,"userId":"u5"}"""
        val back = json.decodeFromString<WsMsg>(text)
        assertTrue(back is WsMsg.Pong)
    }
}
