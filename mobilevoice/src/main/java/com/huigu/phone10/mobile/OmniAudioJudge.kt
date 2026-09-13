package com.huigu.phone10.mobile

import android.util.Base64
import android.util.Log
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
 * error：给界面看的短原因码，例如 http_400 / empty_result / timeout。
 * detail：服务端响应片段，只写入诊断日志，用于定位参数或格式差异。
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
 * 百炼规定 Qwen-Omni 的所有请求必须 stream=true，因此这里按 SSE 流式读取，
 * 拿到足够文字后立即掐断连接。
 * 响应格式在不同模型版本间有差异，所以同时兼容 delta.content、
 * delta.audio.transcript 与一次性 JSON 两种形态；空结果时会换一组参数重试一次。
 */
class OmniAudioJudge(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    client: OkHttpClient = OkHttpClient()
) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(pcm: ByteArray): OmniOutcome =
        withTimeoutOrNull(9000) { analyzeInner(pcm) } ?: OmniOutcome(error = "timeout")

    private suspend fun analyzeInner(pcm: ByteArray): OmniOutcome {
        if (apiKey.isBlank()) return OmniOutcome(error = "no_key")
        if (pcm.isEmpty()) return OmniOutcome(error = "no_audio")
        val base64Wav = try {
            Base64.encodeToString(WavEncoder.pcmToWav(pcm), Base64.NO_WRAP)
        } catch (_: Exception) {
            return OmniOutcome(error = "encode")
        }
        val first = withTimeoutOrNull(6000) { call(base64Wav, withModalities = true) }
            ?: return OmniOutcome(error = "timeout")
        if (first.ok) return first
        if (first.error !in RETRYABLE) return first
        // 空结果：去掉 modalities 参数再试一次，覆盖不同模型版本对输出模态的处理差异。
        val second = withTimeoutOrNull(6000) { call(base64Wav, withModalities = false) }
            ?: return first
        if (second.ok) {
            Log.d(TAG, "retry_without_modalities=ok")
            return second
        }
        return first.copy(detail = listOfNotNull(first.detail, "retry:${second.error}").joinToString(" | "))
    }

    private suspend fun call(base64Wav: String, withModalities: Boolean): OmniOutcome =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyOf(base64Wav, withModalities).toString().toRequestBody("application/json".toMediaType()))
                .build()
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
                    if (!finished.get()) {
                        Log.d(TAG, "onFailure ${e.javaClass.simpleName}")
                        finish(OmniOutcome(error = "network"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            val snippet = try { response.body?.string()?.take(160) } catch (_: Exception) { null }
                            Log.d(TAG, "http=${response.code} body=${snippet?.replace(Regex("\\s+"), " ")}")
                            finish(OmniOutcome(error = "http_${response.code}",
                                detail = snippet?.replace(Regex("\\s+"), " ")?.trim()))
                            return
                        }
                        val source = response.body?.source()
                        if (source == null) { finish(OmniOutcome(error = "empty_body")); return }
                        val text = StringBuilder()
                        val raw = StringBuilder()
                        var sawSse = false
                        var inband: String? = null
                        try {
                            while (!finished.get()) {
                                val line = source.readUtf8Line() ?: break
                                if (raw.length < 400) raw.append(line).append('\n')
                                if (!line.startsWith("data:")) continue
                                sawSse = true
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isEmpty()) continue
                                if (payload == "[DONE]") break
                                val root = try { JsonParser.parseString(payload).asJsonObject } catch (_: Exception) { null }
                                if (root == null) continue
                                if (root.get("choices") == null) {
                                    // 流内错误事件：{"code":"...","message":"..."}
                                    val code = root.get("code")?.let { if (it.isJsonPrimitive) it.asString else null }
                                    if (!code.isNullOrBlank()) {
                                        inband = code + " " + (root.get("message")?.let { if (it.isJsonPrimitive) it.asString else null } ?: "")
                                        break
                                    }
                                    continue
                                }
                                val delta = textOf(root)
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
                        val head = raw.toString().replace("\n", " ").trim().take(200)
                        Log.d(TAG, "model=$model sse=$sawSse inband=$inband len=${hint.length} head=$head")
                        finish(when {
                            hint.isNotEmpty() -> OmniOutcome(hint = hint)
                            inband != null -> OmniOutcome(error = "api_error", detail = inband.take(160))
                            !sawSse -> OmniOutcome(error = "no_sse", detail = head)
                            else -> OmniOutcome(error = "empty_result", detail = head)
                        })
                    }
                }
            })
        }

    private fun bodyOf(base64Wav: String, withModalities: Boolean) = JsonObject().apply {
        addProperty("model", model)
        // 百炼要求 Omni 必须流式。
        addProperty("stream", true)
        addProperty("max_tokens", 96)
        if (withModalities) add("modalities", JsonArray().apply { add("text") })
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
                            "不要解释、不要换行。若没有特殊特征就只写：语气平静。")
                    })
                })
            })
        })
    }

    /** 兼容 delta.content、delta.audio.transcript 与一次性 JSON 的 message.content。 */
    private fun textOf(root: JsonObject): String? {
        val choices = root.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null
        val choice = choices.get(0).asJsonObject
        val delta = choice.getAsJsonObject("delta")
        if (delta != null) {
            val content = delta.get("content")
            if (content != null && content.isJsonPrimitive) {
                val value = content.asString
                if (value.isNotEmpty()) return value
            }
            val audio = delta.getAsJsonObject("audio")
            if (audio != null) {
                val transcript = audio.get("transcript")
                if (transcript != null && transcript.isJsonPrimitive) {
                    val value = transcript.asString
                    if (value.isNotEmpty()) return value
                }
            }
        }
        val message = choice.getAsJsonObject("message")
        if (message != null) {
            val content = message.get("content")
            if (content != null && content.isJsonPrimitive && content.asString.isNotEmpty()) return content.asString
        }
        return null
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3.5-omni-flash"
        private const val TAG = "Phone10Omni"
        private const val ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private val RETRYABLE = setOf("empty_result", "no_sse", "empty_body")
    }
}
