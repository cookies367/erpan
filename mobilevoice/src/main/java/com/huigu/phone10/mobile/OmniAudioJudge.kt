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
 * error：给界面看的短原因码，例如 http_400 / api_error / empty_result / timeout。
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
 *
 * 两个必须遵守的点（均为实测结论，改动前请先复测）：
 * 1. Qwen-Omni 的所有请求必须 stream=true，因此这里按 SSE 流式读取，
 *    拿到足够文字后立即掐断连接。
 * 2. 内联音频必须写成 data URL（data:audio/wav;base64,...）。只给裸 base64 时，
 *    短音频（1 秒左右）能侥幸通过，超过约 2 秒就会被服务端当成 URL 解析并报
 *    "<400> InvalidParameter: The provided URL does not appear to be valid"，
 *    这正是此前每次说话都分析失败的原因。
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
        // 只请求一次：不带 modalities 的写法已被实测证明必然失败（服务端会把音频当 URL），
        // 保留重试只会让用户说完话后多等几秒。
        return withTimeoutOrNull(8000) { call(base64Wav, withModalities = true) }
            ?: OmniOutcome(error = "timeout")
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
                            val snippet = try { response.body?.string()?.take(300) } catch (_: Exception) { null }
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
                                    // 流内错误事件。百炼的两种形态都要认：
                                    // {"error":{"code":"...","message":"..."}} 与 {"code":"...","message":"..."}
                                    val box = root.getAsJsonObject("error") ?: root
                                    val code = box.get("code")?.let { if (it.isJsonPrimitive) it.asString else null }
                                    val message = box.get("message")?.let { if (it.isJsonPrimitive) it.asString else null }
                                    if (!code.isNullOrBlank() || !message.isNullOrBlank()) {
                                        inband = listOfNotNull(code?.takeIf { it.isNotBlank() }, message)
                                            .joinToString(" ")
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
                            inband != null -> OmniOutcome(error = "api_error", detail = inband.take(200))
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
                            // 必须带 data URL 前缀，否则稍长的音频会被服务端当成网址解析而报参数错误。
                            addProperty("data", "data:audio/wav;base64,$base64Wav")
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
    }
}
