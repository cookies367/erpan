package com.huigu.phone10.mobile

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 百炼 Omni 多模态音频分析器。
 * 将录音转为 WAV base64 提交给百炼兼容接口，提取说话人语气、情绪与背景音。
 */
class OmniAudioJudge(
    private val apiKey: String,
    private val model: String = "qwen-omni-turbo",
    client: OkHttpClient = OkHttpClient()
) {
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(pcm: ByteArray): String? = withTimeoutOrNull(5000) {
        if (apiKey.isBlank() || pcm.isEmpty()) return@withTimeoutOrNull null
        try {
            val wavBytes = WavEncoder.pcmToWav(pcm)
            val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("stream", false)
                addProperty("max_tokens", 60)
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
                                addProperty("text", "简要分析说话人的【语气情绪】与环境【背景音】，限制25字以内。例如：语气轻快带笑，背景有微弱键盘声。若无特殊特征输出：语气平静。")
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            suspendCancellableCoroutine { continuation ->
                val call = http.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                if (!continuation.isActive) return
                                if (!response.isSuccessful) {
                                    continuation.resume(null)
                                    return
                                }
                                val json = JsonParser.parseString(response.body.string()).asJsonObject
                                val content = json.getAsJsonArray("choices")
                                    ?.get(0)?.asJsonObject
                                    ?.getAsJsonObject("message")
                                    ?.get("content")?.asString?.trim()
                                continuation.resume(content?.take(80))
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    }
                })
            }
        } catch (_: Exception) {
            null
        }
    }
}
