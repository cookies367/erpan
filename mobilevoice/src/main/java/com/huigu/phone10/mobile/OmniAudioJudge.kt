package com.huigu.phone10.mobile

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

/**
 * 听感分析结果。
 * hint：模型给出的语气/情绪/背景音结论（成功时非空）。
 * error：给界面看的短原因码，例如 http_400 / timeout / network。
 * detail：服务端返回的错误文本片段，只写入诊断日志，用于快速定位参数问题。
 */
data class OmniOutcome(
    val hint: String? = null,
    val error: String? = null,
    val detail: String? = null,
) {
    val ok: Boolean get() = hint != null
}

/**
 * 百炼 Omni 多模态音频分析器。
 * 将录音转为 WAV base64 提交给百炼兼容接口，提取说话人语气、情绪与背景音。
 * 百炼规定 Qwen-Omni 的所有请求必须 stream=true，因此这里按 SSE 流式读取，
 * 拿到足够文字后立即掐断连接，避免为剩余内容白等。
 */
class OmniAudioJudge(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    client: OkHttpClient = OkHttpClient()
) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(pcm: ByteArray): OmniOutcome =
        withTimeoutOrNull(8000) { analyzeStreaming(pcm) } ?: OmniOutcome(error = "timeout")

    private suspend fun analyzeStreaming(pcm: ByteArray): OmniOutcome {
        if (apiKey.isBlank()) return OmniOutcome(error = "no_key")
        if (pcm.isEmpty()) return OmniOutcome(error = "no_audio")
        val base64Wav = try {
            Base64.encodeToString(WavEncoder.pcmToWav(pcm), Base64.NO_WRAP)
        } catch (_: Exception) {
            return OmniOutcome(error = "encode")
        }

        val body = JsonObject().apply {
            addProperty("model", model)
            // 百炼要求 Omni 必须流式；这里只要文字结论，不额外合成语音。
            addProperty("stream", true)
            addProperty("max_tokens", 80)
            add("modalities", JsonArray().apply { add("text") })
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", base64Wav)
                                addProperty("format", "wav")
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "请用一行中文说明这段录音里说话人的语气情绪和环境背景音，30字以内。" +
                                "不要解释、不要标点以外的多余内容、不要换行。若没有特殊特征就只写：语气平静。")
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val call = http.newCall(request)
            // 结束（拿到结论 / 出错 / 超时取消）时立即掐断连接，不再等服务端后续输出。
            fun finish(outcome: OmniOutcome) {
                if (!finished.compareAndSet(false, true)) return
                call.cancel()
                if (continuation.isActive) continuation.resume(outcome)
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!finished.get()) finish(OmniOutcome(error = "network"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            val snippet = try { response.body?.string()?.take(120) } catch (_: Exception) { null }
                            finish(OmniOutcome(error = "http_${response.code}",
                                detail = snippet?.replace(Regex("\\s+"), " ")?.trim()))
                            return
                        }
                        val source = response.body?.source()
                        if (source == null) { finish(OmniOutcome(error = "empty_body")); return }
                        val text = StringBuilder()
                        try {
                            while (!finished.get()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isEmpty()) continue
                                if (payload == "[DONE]") break
                                val delta = try {
                                    val choices = JsonParser.parseString(payload).asJsonObject.getAsJsonArray("choices")
                                    if (choices == null || choices.size() == 0) null
                                    else choices.get(0).asJsonObject.getAsJsonObject("delta")?.get("content")?.asString
                                } catch (_: Exception) { null }
                                if (!delta.isNullOrEmpty()) {
                                    text.append(delta)
                                    // 结论只有一行，攒够字符就没必要再等剩余数据。
                                    if (text.length >= 48) break
                                }
                            }
                        } catch (_: Exception) {
                            // 自己主动掐断连接会走到这里，属于正常路径。
                        }
                        val hint = text.toString().trim().take(120)
                        finish(if (hint.isEmpty()) OmniOutcome(error = "empty_result") else OmniOutcome(hint = hint))
                    }
                }
            })
        }
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3.5-omni-flash"
        private const val ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    }
}
