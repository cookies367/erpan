package com.huigu.phone10.mobile

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** MiniMax 音色混合权重项：一个音色 ID + 一个 1-100 的权重值 */
data class TimbreWeight(val voiceId: String, val weight: Int = 50)

data class SpeechConfig(val sttBaseUrl: String, val sttKey: String, val sttModel: String,
    val ttsBaseUrl: String, val ttsKey: String, val ttsModel: String, val voice: String,
    val provider: String? = OPENAI, val ttsProvider: String? = null,
    // MiniMax 音色混合权重列表，最多 4 项；为 null 或空时走单音色模式
    val timbreWeights: List<TimbreWeight>? = null,
    // MiniMax 音频参数：音速 0.5-2.0（默认1.0），音调 -12~12（默认0），音量 0.5-2.0（默认1.0）
    val ttsSpeed: Float = 1.0f, val ttsPitch: Float = 0.0f, val ttsVol: Float = 1.0f) {
    // Gson leaves fields absent from older encrypted settings null.
    val isBailian: Boolean get() = provider == BAILIAN
    val effectiveTtsProvider: String get() = ttsProvider ?: provider ?: OPENAI
    val streamingTts: Boolean get() = effectiveTtsProvider in setOf(BAILIAN, MINIMAX, ELEVENLABS)
    val hasTimbreWeights: Boolean get() = !timbreWeights.isNullOrEmpty()

    fun withTtsProvider(next: String): SpeechConfig {
        if (next == effectiveTtsProvider) return this
        val defaults = when (next) {
            BAILIAN -> bailianDefaults()
            OPENAI -> openAiDefaults()
            MINIMAX -> copy(ttsBaseUrl = "wss://api.minimax.cn/ws/v1/t2a_v2_bidi",
                ttsKey = "", ttsModel = "speech-2.8-turbo", voice = "male-qn-qingse")
            ELEVENLABS -> elevenLabsDefaults()
            else -> throw IllegalArgumentException("请选择支持的合成服务。")
        }
        return copy(ttsProvider = next, ttsBaseUrl = defaults.ttsBaseUrl, ttsKey = defaults.ttsKey,
            ttsModel = defaults.ttsModel, voice = defaults.voice, timbreWeights = if (next == MINIMAX) timbreWeights else null)
    }

    fun validate() {
        require(provider == null || provider == OPENAI || provider == BAILIAN) { "请选择支持的语音服务。" }
        if (isBailian) bailianEndpoint(sttBaseUrl) else base(sttBaseUrl)
        when (effectiveTtsProvider) {
            BAILIAN -> bailianEndpoint(ttsBaseUrl)
            MINIMAX -> minimaxEndpoint(ttsBaseUrl)
            OPENAI -> base(ttsBaseUrl)
            ELEVENLABS -> base(ttsBaseUrl)
            else -> throw IllegalArgumentException("请选择支持的合成服务。")
        }
        // 密钥和模型始终必填
        require(listOf(sttKey, sttModel, ttsKey, ttsModel).all {
            it.isNotBlank() && it.length <= 4096 && '\r' !in it && '\n' !in it
        }) { "请填写有效的识别和合成密钥及模型。" }
        // 音色 ID：使用混合权重时可不填（权重列表里有 voice_id）；否则必填
        if (!hasTimbreWeights) {
            require(voice.isNotBlank() && voice.length <= 4096 && '\r' !in voice && '\n' !in voice) { "请填写有效的音色 ID。" }
        }
        require(listOf(sttKey, ttsKey).all { key -> key.all { it.code in 33..126 } }) {
            "语音密钥包含无效字符。"
        }
        // 混合权重的合法性检查
        if (hasTimbreWeights) {
            require(timbreWeights!!.size in 1..4) { "音色混合最多 4 项。" }
            timbreWeights.forEach { tw ->
                require(tw.voiceId.isNotBlank()) { "混合音色 ID 不能为空。" }
                require(tw.weight in 1..100) { "音色权重须在 1-100 之间。" }
            }
        }
    }

    override fun toString(): String = "SpeechConfig(credentials=redacted)"

    internal fun sttEndpoint() = endpoint(sttBaseUrl, "transcriptions")
    internal fun ttsEndpoint() = endpoint(ttsBaseUrl, "speech")

    // 把 WebSocket 双向地址转成 HTTP 同步合成地址，用于试听
    internal fun minimaxHttpSyncEndpoint(): String {
        val uri = try { URI(ttsBaseUrl.trim()) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("MiniMax 地址无效。")
        return "https://${uri.host}/v1/t2a_v2"
    }

    internal fun minimaxEndpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        require(uri != null && uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/ws/v1/t2a_v2_bidi") { "MiniMax 须填写完整 WSS 双向流式地址，且不含账号或查询参数。" }
        return uri.toString()
    }

    internal fun bailianEndpoint(value: String): String {
        val message = "百炼地址须为 WSS 推理地址，或 HTTPS 的根地址、/api/v1 地址，且不含账号、查询参数或片段。"
        val text = value.trim()
        require(text.length <= 4096 && '\r' !in text && '\n' !in text) { message }
        val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException(message) }
        val scheme = uri.scheme.orEmpty().lowercase()
        require(scheme == "wss" || scheme == "https") { message }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { message }
        require(if (scheme == "wss") uri.rawPath == "/api-ws/v1/inference"
            else uri.rawPath in listOf("", "/", "/api/v1", "/api/v1/")) { message }
        val httpText = "https" + text.substring(text.indexOf(':'))
        val url = httpText.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { message }
        return url.newBuilder().encodedPath("/api-ws/v1/inference").build().toString().replaceFirst("https://", "wss://")
    }

    private fun endpoint(value: String, action: String): HttpUrl = base(value).newBuilder()
        .encodedPath(base(value).encodedPath.trimEnd('/') + "/audio/" + action).build()

    private fun base(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "语音地址须为 HTTPS 基址，且不含账号、查询参数或片段。" }
        return url
    }

    companion object {
        const val OPENAI = "openai"
        const val BAILIAN = "bailian"
        const val MINIMAX = "minimax"
        const val ELEVENLABS = "elevenlabs"

        fun bailianDefaults() = SpeechConfig(
            sttBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference", sttKey = "", sttModel = "paraformer-realtime-v2",
            ttsBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference", ttsKey = "", ttsModel = "cosyvoice-v3.5-plus",
            voice = "", provider = BAILIAN)

        fun openAiDefaults() = SpeechConfig(
            sttBaseUrl = "https://api.openai.com/v1", sttKey = "", sttModel = "gpt-4o-mini-transcribe",
            ttsBaseUrl = "https://api.openai.com/v1", ttsKey = "", ttsModel = "gpt-4o-mini-tts", voice = "coral",
            provider = OPENAI)

        fun elevenLabsDefaults() = SpeechConfig(
            sttBaseUrl = "https://api.openai.com/v1", sttKey = "", sttModel = "gpt-4o-mini-transcribe",
            ttsBaseUrl = "https://api.elevenlabs.io/v1", ttsKey = "", ttsModel = "eleven_multilingual_v2", voice = "",
            provider = OPENAI, ttsProvider = ELEVENLABS)
    }
}
