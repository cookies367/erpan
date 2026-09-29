package com.huigu.phone10.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MiniMax 中文系统音色预设列表（voice_id → 中文名）
private val MINIMAX_CN_VOICES = listOf(
    "male-qn-qingse" to "青年男声·清澈",
    "male-qn-jingying" to "青年精英男声",
    "male-qn-badao" to "青年霸道男声",
    "male-qn-daxuesheng" to "青年大学生男声",
    "female-shaonv" to "少女音",
    "female-yujie" to "御姐女声",
    "female-chengshu" to "成熟女声",
    "female-tianmei" to "甜美女声",
    "male-qn-qingse-jingpin" to "清澈男声·精品",
    "male-qn-jingying-jingpin" to "精英男声·精品",
    "male-qn-badao-jingpin" to "霸道男声·精品",
    "male-qn-daxuesheng-jingpin" to "大学生男声·精品",
    "female-shaonv-jingpin" to "少女音·精品",
    "female-yujie-jingpin" to "御姐女声·精品",
    "female-chengshu-jingpin" to "成熟女声·精品",
    "female-tianmei-jingpin" to "甜美女声·精品",
    "clever" to "机灵",
    "cute" to "可爱",
    "lovely" to "甜美",
    "cartoon" to "卡通",
    "bingjiao" to "病娇",
    "junlang" to "俊朗",
    "chunzhen" to "纯真",
    "lengdan" to "冷淡",
    "badao" to "霸道",
    "tianxin" to "甜心",
    "qiaopi" to "俏皮",
    "wumei" to "妩媚",
    "diadia" to "嗲嗲",
    "danya" to "淡雅",
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ErpanConfig(settings: MobileSettings, enabled: Boolean, listing: Boolean, notice: String,
    target: String, onChange: (MobileSettings) -> Unit, onStt: (String) -> Unit, onTts: (String) -> Unit,
    onChats: () -> Unit, onSave: () -> Unit, onBack: () -> Unit, onAbout: () -> Unit,
    onSaveSttPreset: (String) -> Unit, onApplySttPreset: (Int) -> Unit, onDeleteSttPreset: (Int) -> Unit,
    onSaveTtsPreset: (String) -> Unit, onApplyTtsPreset: (Int) -> Unit, onDeleteTtsPreset: (Int) -> Unit,
    // MiniMax 音色混合试听回调：传入试听文本，返回 PCM 给调用方播放
    onPreviewTimbre: (suspend (String) -> Unit)? = null) {
    val voiceTarget = remember { BringIntoViewRequester() }
    val judgeTarget = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        delay(120)
        if (target == "voice") voiceTarget.bringIntoView()
        if (target == "judge" && settings.smartEndpoint) judgeTarget.bringIntoView()
    }
    val speech = settings.speech
    val ttsProvider = speech.effectiveTtsProvider

    // 折叠状态记忆
    var chatExpanded by remember { mutableStateOf(true) }
    var sttExpanded by remember { mutableStateOf(true) }
    var ttsExpanded by remember { mutableStateOf(true) }
    var judgeExpanded by remember { mutableStateOf(settings.smartEndpoint) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageHeading("连接配置", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("按需展开分组配置，点击标题即可折叠", color = ErpanColors.Muted, fontSize = 13.sp)
            if (!enabled) Text("通话中可查看配置；结束语音后再修改。", color = ErpanColors.Rose, fontSize = 13.sp)
            if (notice.isNotBlank()) Text(notice, color = ErpanColors.Rose, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))

            // ① 目标聊天折叠卡片
            CollapsibleCard(title = "目标聊天与连接", expanded = chatExpanded, onToggle = { chatExpanded = !chatExpanded }) {
                ErpanNavigationCard("选中聊天窗口", subtitle = if (listing) "正在读取…" else settings.displayChat(),
                    enabled = enabled && !listing, onClick = onChats)
                Hint("聊天模型、角色和历史在 Operit 中设置")
            }

            // ② 语音识别折叠卡片
            CollapsibleCard(title = "语音识别 (STT) 与听感", expanded = sttExpanded, onToggle = { sttExpanded = !sttExpanded }) {
                ProviderField("识别服务商", speech.provider ?: SpeechConfig.OPENAI, enabled,
                    listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.OPENAI to "Audio API 兼容"), onStt)
                FormField("识别接口地址", speech.sttBaseUrl, enabled, placeholder = "填写识别服务的地址") {
                    onChange(settings.copy(speech = speech.copy(sttBaseUrl = it.trim())))
                }
                FormField("识别 API Key", speech.sttKey, enabled, secret = true,
                    placeholder = if (speech.isBailian) "填写百炼 API Key" else "填写识别服务的 API Key") {
                    onChange(settings.copy(speech = speech.copy(sttKey = it.trim())))
                }
                FormField("识别模型", speech.sttModel, enabled) {
                    onChange(settings.copy(speech = speech.copy(sttModel = it.trim())))
                }
                Hint(if (speech.isBailian) "地址、密钥和模型需属于同一地域。百炼识别使用 Paraformer 协议。" else "服务需支持 Audio API 语音识别接口。")

                // Omni 深度听感开关
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("百炼 Omni 深度听感分析", fontSize = 14.sp)
                        Hint("录音结束后调用百炼 Omni 分析说话人语气、情绪与背景音，并拼入声音线索附件。")
                    }
                    Switch(checked = settings.enableOmniHints, onCheckedChange = { onChange(settings.copy(enableOmniHints = it)) }, enabled = enabled)
                }
                if (settings.enableOmniHints) {
                    FormField("Omni 模型名", settings.omniModel.orEmpty(), enabled, placeholder = OmniAudioJudge.DEFAULT_MODEL) {
                        onChange(settings.copy(omniModel = it.trim()))
                    }
                    Hint("留空使用默认 ${OmniAudioJudge.DEFAULT_MODEL}；也可改 qwen3-omni-flash 等。与识别共用同一个百炼 API Key。")
                }

                ConfigDivider()
                PresetSection("识别预设", settings.sttPresets, enabled, onSaveSttPreset, onApplySttPreset, onDeleteSttPreset)
            }

            // ③ 语音合成折叠卡片
            CollapsibleCard(title = "语音合成 (TTS) 与音色", expanded = ttsExpanded, onToggle = { ttsExpanded = !ttsExpanded }) {
                ProviderField("合成服务商", ttsProvider, enabled,
                    listOf(SpeechConfig.BAILIAN to "阿里云百炼", SpeechConfig.MINIMAX to "MiniMax 官方", SpeechConfig.ELEVENLABS to "ElevenLabs", SpeechConfig.OPENAI to "Audio API 兼容"), onTts)
                if (ttsProvider == SpeechConfig.MINIMAX) Hint("填写 MiniMax 官方 Key 和音色 ID；百炼 Key 不适用。")
                if (ttsProvider == SpeechConfig.ELEVENLABS) Hint("填写 ElevenLabs API Key 和音色 ID；地址可用官方或中转域名。")
                FormField("合成接口地址", speech.ttsBaseUrl, enabled, placeholder = "填写合成服务的地址") {
                    onChange(settings.copy(speech = speech.copy(ttsBaseUrl = it.trim())))
                }
                FormField("合成 API Key", speech.ttsKey, enabled, secret = true,
                    placeholder = when (ttsProvider) { SpeechConfig.BAILIAN -> "填写百炼 API Key"; SpeechConfig.MINIMAX -> "填写 MiniMax 官方 Key"; SpeechConfig.ELEVENLABS -> "填写 ElevenLabs API Key"; else -> "填写合成服务的 API Key" }) {
                    onChange(settings.copy(speech = speech.copy(ttsKey = it.trim())))
                }
                if (speech.isBailian && ttsProvider == SpeechConfig.BAILIAN) TextButton(enabled = enabled, onClick = {
                    onChange(settings.copy(speech = speech.copy(ttsBaseUrl = speech.sttBaseUrl, ttsKey = speech.sttKey)))
                }, contentPadding = PaddingValues(0.dp)) { Text("使用上面的百炼识别地址和密钥", fontSize = 12.sp) }
                FormField("合成模型", speech.ttsModel, enabled) { onChange(settings.copy(speech = speech.copy(ttsModel = it.trim()))) }
                Column(Modifier.bringIntoViewRequester(voiceTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    FormField("音色 ID", speech.voice, enabled, placeholder = "粘贴服务商提供的音色 ID") {
                        onChange(settings.copy(speech = speech.copy(voice = it.trim()), voiceName = null))
                    }
                    FormField("音色名称（选填）", settings.voiceName.orEmpty(), enabled, placeholder = "给这个声音起个名字") {
                        onChange(settings.copy(voiceName = it.take(80)))
                    }
                    Hint("名称仅用于首页显示，不改变音色。音色 ID 须匹配所选服务和模型。")
                }

                // MiniMax 音色混合面板
                if (ttsProvider == SpeechConfig.MINIMAX) {
                    ConfigDivider()
                    TimbreMixPanel(
                        settings = settings,
                        speech = speech,
                        enabled = enabled,
                        onChange = onChange,
                        onPreviewTimbre = onPreviewTimbre,
                    )
                }

                ConfigDivider()
                PresetSection("合成预设", settings.ttsPresets, enabled, onSaveTtsPreset, onApplyTtsPreset, onDeleteTtsPreset)
            }

            // ④ 智能判断折叠卡片（仅当开启或有配置时）
            if (settings.smartEndpoint) {
                CollapsibleCard(title = "智能结束判断", expanded = judgeExpanded, onToggle = { judgeExpanded = !judgeExpanded }) {
                    Column(Modifier.bringIntoViewRequester(judgeTarget), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Hint("此项额外调用文本模型判断是否说完，按该服务计费。")
                        val judge = settings.endJudge ?: EndJudgeConfig()
                        FormField("判断接口地址", judge.baseUrl, enabled) { onChange(settings.copy(endJudge = judge.copy(baseUrl = it.trim()))) }
                        FormField("判断 API Key", judge.key, enabled, secret = true) { onChange(settings.copy(endJudge = judge.copy(key = it.trim()))) }
                        FormField("判断模型", judge.model, enabled) { onChange(settings.copy(endJudge = judge.copy(model = it.trim()))) }
                    }
                }
            }

            Button(onClick = onSave, enabled = enabled && !listing, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp).heightIn(min = 50.dp)) { Text("保存配置", fontSize = 17.sp) }
            Hint("密钥加密保存在本机。识别、合成及可选判断的费用由相应服务商结算。")
            TextButton(onClick = onAbout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("关于耳畔") }
        }
    }
}

// ===== MiniMax 音色混合面板 =====
@Composable private fun TimbreMixPanel(
    settings: MobileSettings,
    speech: SpeechConfig,
    enabled: Boolean,
    onChange: (MobileSettings) -> Unit,
    onPreviewTimbre: (suspend (String) -> Unit)?,
) {
    var mixEnabled by remember { mutableStateOf(speech.hasTimbreWeights) }
    // 从已有配置恢复权重列表，或初始化为空列表
    var weights by remember(speech.timbreWeights) {
        mutableStateOf(speech.timbreWeights ?: emptyList())
    }
    var previewText by remember { mutableStateOf("你好，这是音色混合的试听效果。") }
    var previewStatus by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("系统音色混合", fontSize = 14.sp)
            Hint("开启后可混合最多 4 个 MiniMax 中文系统音色，按权重融合成新声音。")
        }
        Switch(
            checked = mixEnabled,
            enabled = enabled,
            onCheckedChange = { on ->
                mixEnabled = on
                if (on && weights.isEmpty()) {
                    // 默认加一行
                    weights = listOf(TimbreWeight(MINIMAX_CN_VOICES[0].first, 50))
                }
                val newSpeech = if (on) speech.copy(timbreWeights = weights) else speech.copy(timbreWeights = null)
                onChange(settings.copy(speech = newSpeech))
            },
        )
    }

    if (mixEnabled) {
        Spacer(Modifier.height(8.dp))
        weights.forEachIndexed { index, tw ->
            TimbreWeightRow(
                index = index,
                selected = tw.voiceId,
                weight = tw.weight,
                enabled = enabled,
                voices = MINIMAX_CN_VOICES,
                canRemove = weights.size > 1,
                onVoiceChange = { newVoiceId ->
                    weights = weights.toMutableList().also { it[index] = TimbreWeight(newVoiceId, tw.weight) }
                    onChange(settings.copy(speech = speech.copy(timbreWeights = weights)))
                },
                onWeightChange = { newWeight ->
                    weights = weights.toMutableList().also { it[index] = TimbreWeight(tw.voiceId, newWeight) }
                    onChange(settings.copy(speech = speech.copy(timbreWeights = weights)))
                },
                onRemove = {
                    weights = weights.toMutableList().also { it.removeAt(index) }
                    onChange(settings.copy(speech = speech.copy(timbreWeights = weights)))
                },
            )
            if (index < weights.lastIndex) Spacer(Modifier.height(10.dp))
        }

        // 添加按钮（最多 4 个）
        if (weights.size < 4) {
            TextButton(enabled = enabled, onClick = {
                val nextVoice = MINIMAX_CN_VOICES.firstOrNull { it.first !in weights.map { w -> w.voiceId } }?.first
                    ?: MINIMAX_CN_VOICES[0].first
                weights = weights + TimbreWeight(nextVoice, 50)
                onChange(settings.copy(speech = speech.copy(timbreWeights = weights)))
            }) { Text("+ 添加音色（${weights.size}/4）", fontSize = 14.sp) }
        }

        Spacer(Modifier.height(10.dp))
        // 试听区域
        OutlinedTextField(
            value = previewText,
            onValueChange = { previewText = it },
            enabled = enabled && !previewing,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "试听文本" },
            label = { Text("试听文本", fontSize = 13.sp) },
            shape = RoundedCornerShape(9.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = ErpanColors.Line,
                focusedBorderColor = ErpanColors.Rose,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (onPreviewTimbre != null && !previewing) {
                    previewing = true
                    previewStatus = "正在合成试听…"
                    scope.launch {
                        try {
                            onPreviewTimbre?.invoke(previewText)
                            previewStatus = "试听播放完毕"
                        } catch (e: Exception) {
                            previewStatus = "试听失败：${e.message ?: "未知错误"}"
                        }
                        previewing = false
                    }
                }
            },
            enabled = enabled && !previewing && weights.isNotEmpty(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) { Text(if (previewing) "合成中…" else "▶ 试听混合效果", fontSize = 15.sp) }
        if (previewStatus.isNotBlank()) {
            Text(previewStatus, fontSize = 12.sp, color = if (previewStatus.contains("失败")) ErpanColors.Rose else ErpanColors.Muted,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// 单行音色权重控件：下拉选音色 + 滑杆调权重 + 删除按钮
@Composable private fun TimbreWeightRow(
    index: Int,
    selected: String,
    weight: Int,
    enabled: Boolean,
    voices: List<Pair<String, String>>,
    canRemove: Boolean,
    onVoiceChange: (String) -> Unit,
    onWeightChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var dropdownOpen by remember { mutableStateOf(false) }
    val voiceName = voices.firstOrNull { it.first == selected }?.second ?: selected
    Surface(color = ErpanColors.Paper, shape = RoundedCornerShape(9.dp),
        border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音色 ${index + 1}", fontSize = 13.sp, color = ErpanColors.Muted, modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f)) {
                    Surface(onClick = { dropdownOpen = true }, enabled = enabled, color = ErpanColors.Paper,
                        shape = RoundedCornerShape(7.dp), border = BorderStroke(0.6.dp, ErpanColors.Line)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(voiceName, Modifier.weight(1f), fontSize = 14.sp)
                            LineIcon(ErpanIcon.DOWN, ErpanColors.Muted, Modifier.size(15.dp))
                        }
                    }
                    DropdownMenu(dropdownOpen, onDismissRequest = { dropdownOpen = false }) {
                        voices.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name, fontSize = 14.sp) }, onClick = {
                                dropdownOpen = false; onVoiceChange(id)
                            })
                        }
                    }
                }
                if (canRemove) {
                    IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
                        Text("✕", fontSize = 16.sp, color = ErpanColors.Rose)
                    }
                }
            }
            // 权重滑杆
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权重", fontSize = 12.sp, color = ErpanColors.Muted, modifier = Modifier.width(52.dp))
                Slider(
                    value = weight.toFloat(),
                    onValueChange = { onWeightChange(it.toInt().coerceIn(1, 100)) },
                    enabled = enabled,
                    valueRange = 1f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("$weight", fontSize = 13.sp, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = ErpanColors.Paper,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.8.dp, ErpanColors.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, Modifier.weight(1f), fontSize = 17.sp, fontFamily = FontFamily.Serif)
                Text(if (expanded) "收起 ▲" else "展开 ▼", fontSize = 13.sp, color = ErpanColors.Rose)
            }
            if (expanded) {
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable internal fun PageHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { LineIcon(ErpanIcon.BACK) }
        Text(title, fontSize = 26.sp, fontFamily = FontFamily.Serif)
    }
}
@Composable private fun Hint(text: String) { Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = ErpanColors.Muted) }
@Composable private fun ConfigDivider() { HorizontalDivider(Modifier.padding(vertical = 9.dp), thickness = 0.6.dp, color = ErpanColors.Line) }

@Composable private fun ProviderField(label: String, selected: String, enabled: Boolean,
    choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 14.sp)
        Box {
            Surface(onClick = { open = true }, enabled = enabled, color = ErpanColors.Paper,
                shape = RoundedCornerShape(9.dp), border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(choices.firstOrNull { it.first == selected }?.second ?: "请选择", Modifier.weight(1f), fontSize = 15.sp)
                    LineIcon(ErpanIcon.DOWN, ErpanColors.Muted, Modifier.size(17.dp))
                }
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onChange(id) }) }
            }
        }
    }
}
@Composable private fun FormField(label: String, value: String, enabled: Boolean, secret: Boolean = false,
    placeholder: String = "", onChange: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 14.sp)
        OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = true, shape = RoundedCornerShape(9.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = ErpanColors.Muted) },
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                else KeyboardOptions.Default,
            trailingIcon = if (secret) { { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示", fontSize = 12.sp) } } } else null,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = ErpanColors.Line, focusedBorderColor = ErpanColors.Rose,
                disabledBorderColor = ErpanColors.Line.copy(alpha = 0.65f)))
    }
}

/** 预设管理区块：保存当前配置为预设、点一下应用、旁边有删除 */
@Composable private fun ColumnScope.PresetSection(
    title: String,
    presets: List<SpeechPreset>,
    enabled: Boolean,
    onSave: (String) -> Unit,
    onApply: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    SectionTitle(title)
    if (presets.isEmpty()) {
        Hint("尚无预设。填好上面的配置后点「存为预设」即可保存。")
    } else {
        presets.forEachIndexed { index, preset ->
            Surface(color = ErpanColors.Paper, shape = RoundedCornerShape(9.dp),
                border = BorderStroke(0.8.dp, ErpanColors.Line), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, Modifier.weight(1f), fontSize = 15.sp)
                    TextButton(enabled = enabled, onClick = { onApply(index) }) { Text("应用", fontSize = 13.sp) }
                    TextButton(enabled = enabled, onClick = { onDelete(index) }) { Text("删除", fontSize = 13.sp) }
                }
            }
        }
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    TextButton(enabled = enabled, onClick = { showSaveDialog = true; presetName = "" },
        modifier = Modifier.align(Alignment.Start)) { Text("存为预设", fontSize = 14.sp) }
    if (showSaveDialog) AlertDialog(onDismissRequest = { showSaveDialog = false },
        title = { Text("保存预设") },
        text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it },
            label = { Text("预设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = presetName.isNotBlank(), onClick = {
            onSave(presetName.trim()); showSaveDialog = false
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } })
}
