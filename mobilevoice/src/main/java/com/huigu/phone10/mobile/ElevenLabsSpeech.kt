package com.huigu.phone10.mobile

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ElevenLabs HTTP 按句流式合成实现。
 * 从文字流里逐句取文字，每句发一次 HTTP POST，
 * 拿回的裸 PCM 流按偶数对齐后丢给播放器。
 */
internal class ElevenLabsSpeech(
    private val config: SpeechConfig,
    client: OkHttpClient = OkHttpClient(),
    private val timeoutMillis: Long = 600_000,
) {
    // 复用 CloudSpeech 的 HTTP 构建模式：不重试、不跟随重定向
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun speakStream(texts: ReceiveChannel<String>, onPcm: (ByteArray) -> Unit) {
        // 逐句取文字，每句发一次 HTTP 请求。
        // 单句合成失败只跳过该句，不能让整轮回复作废（否则会把 Operit 的回复流也掐断）。
        for (text in texts) {
            currentCoroutineContext().ensureActive()
            if (text.isBlank()) continue
            try {
                synthesize(text, onPcm)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VoiceDiagnostics.record("eleven_sentence_skipped")
            }
        }
    }

    /**
     * 单次合成：POST {基址}/text-to-speech/{音色ID}?output_format=pcm_24000
     * 请求头 xi-api-key，body 是 {text, model_id}
     * 响应体是裸 PCM（24kHz 16bit 小端），流式读取并按偶数对齐
     */
    private suspend fun synthesize(text: String, onPcm: (ByteArray) -> Unit) {
        require(text.isNotBlank()) { "朗读内容为空。" }
        require(config.voice.isNotBlank()) { "ElevenLabs 需要填写音色 ID。" }

        val url = "${config.ttsBaseUrl.trimEnd('/')}/text-to-speech/${config.voice}?output_format=pcm_24000"
        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("model_id", config.ttsModel)
        }
        val request = Request.Builder().url(url)
            .header("xi-api-key", config.ttsKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        execute("合成", request) { response, active ->
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            // ElevenLabs 返回的 Content-Type 可能是 audio/mpeg 或 application/octet-stream
            // output_format=pcm_24000 时实际返回的是裸 PCM
            val input = response.body.byteStream()
            val buffer = ByteArray(8192)
            var carry: Byte? = null
            var total = 0L
            while (active()) {
                val offset = if (carry == null) 0 else 1
                carry?.let { buffer[0] = it }
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read == 0) continue
                val count = offset + read
                val even = count and -2
                carry = if (count != even) buffer[count - 1] else null
                if (even > 0 && active()) {
                    onPcm(buffer.copyOf(even))
                    total += even
                }
            }
            if (active() && (carry != null || total == 0L)) {
                throw SpeechApiException("ElevenLabs 合成音频为空或 PCM 数据不完整。")
            }
        }
    }

    // 复用 CloudSpeech.execute 的模式：suspendCancellableCoroutine + OkHttp enqueue
    private suspend fun <T> execute(stage: String, request: Request, consume: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            val responseRef = AtomicReference<Response?>()
            continuation.invokeOnCancellation { call.cancel(); responseRef.getAndSet(null)?.close() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive)
                        continuation.resumeWithException(SpeechApiException("ElevenLabs ${stage}请求失败或超时。"))
                }

                override fun onResponse(call: Call, response: Response) {
                    responseRef.set(response)
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful)
                                throw SpeechApiException("ElevenLabs ${stage}服务返回 HTTP ${response.code}。")
                            val value = consume(response) { continuation.isActive && !call.isCanceled() }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (error is SpeechApiException) error
                                else SpeechApiException("ElevenLabs ${stage}响应处理失败。"))
                        } finally { responseRef.compareAndSet(response, null) }
                    }
                }
            })
        }
}
