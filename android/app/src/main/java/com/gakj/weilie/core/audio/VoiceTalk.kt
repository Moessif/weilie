package com.gakj.weilie.core.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音对讲引擎：16kHz / 单声道 / 16bit PCM，100ms 一帧。
 *
 * 为什么用裸 PCM 而不是 AAC：
 * - 编解码器（MediaCodec）在低端机上启动要 100~300ms，且要考虑编码器异步回调；
 * - 对讲数据量小（32KB/s），base64 后 43KB/s，房间通常 2~6 人，服务端原样转发足够；
 * - 裸 PCM 没有解码延迟，接收端写入 AudioTrack 即可出声，实测端到端约 200~400ms。
 *
 * 用法（半双工按住说话）：
 * 1. [startRecording] 传入一个挂起回调，每帧调用一次（在 IO 线程）；
 * 2. 松开时 [stopRecording]；
 * 3. 收到队友音频：先 [startPlayback]（每个说话人一次），再逐帧 [play]，最后 [stopPlayback]。
 */
class VoiceTalk {

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 100ms 的 16bit 单声道数据 = 1600 采样 × 2 字节 */
        private const val CHUNK_BYTES = 3_200
    }

    private var record: AudioRecord? = null
    private var recordJob: Job? = null

    private var track: AudioTrack? = null
    private var playerThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val playing = AtomicBoolean(false)

    /** 是否正在录音（界面用来切按钮状态） */
    val isRecording: Boolean get() = record != null

    /**
     * 开始录音；返回 false 表示设备不支持或没有麦克风权限。
     * [onChunk] 参数依次是帧序号与 base64 PCM。
     */
    fun startRecording(scope: CoroutineScope, onChunk: suspend (Int, String) -> Unit): Boolean {
        if (record != null) return true
        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }
        record = rec
        val started = runCatching { rec.startRecording() }.isSuccess
        if (!started) {
            stopRecording()
            return false
        }
        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(CHUNK_BYTES)
            var seq = 0
            while (isActive) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) break
                val data = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
                runCatching { onChunk(seq, data) }
                seq++
            }
        }
        return true
    }

    fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
    }

    /** 队友开始说话：准备播放（清空上一段残留） */
    fun startPlayback() {
        if (playing.get()) return
        playQueue.clear()
        playing.set(true)
        val t = ensureTrack() ?: run {
            playing.set(false)
            return
        }
        runCatching { t.play() }
        playerThread = Thread {
            while (playing.get()) {
                val chunk = playQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (chunk.isEmpty()) continue
                runCatching { t.write(chunk, 0, chunk.size) }
            }
        }.apply {
            name = "weilie-audio-play"
            isDaemon = true
            start()
        }
    }

    /** 收到一帧音频（base64），立即入队播放 */
    fun play(data: String) {
        if (!playing.get()) startPlayback()
        val bytes = runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull() ?: return
        if (bytes.isEmpty()) return
        playQueue.offer(bytes)
    }

    fun stopPlayback() {
        playing.set(false)
        playerThread?.interrupt()
        playerThread = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    /** 页面退出时调用：停录音、停播放、释放底层资源 */
    fun release() {
        stopRecording()
        stopPlayback()
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
        playQueue.clear()
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return if (it.state == AudioTrack.STATE_INITIALIZED) it else null }
        val minBuf = runCatching {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 6)
        val t = runCatching {
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STREAM,
            )
        }.getOrNull() ?: return null
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { t.release() }
            return null
        }
        track = t
        return t
    }
}
