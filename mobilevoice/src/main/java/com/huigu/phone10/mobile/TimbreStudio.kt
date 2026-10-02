package com.huigu.phone10.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 音色调试室：全屏独立界面。
 * 顶部分类标签选语言 → 搜索框筛选 → 网格卡片选音色（最多4个）→ 底部调音台（权重/音速/音调/音量/试听）
 */
@OptIn(androidx.compose.foundation.lazy.grid.LazyGridScope::class)
@Composable
internal fun TimbreStudio(
    speech: SpeechConfig,
    onBack: () -> Unit,
    onChange: (SpeechConfig) -> Unit,
    onPreviewTimbre: suspend (String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    // 已选音色列表，从已有配置恢复
    var selectedWeights by remember(speech.timbreWeights) {
        mutableStateOf(speech.timbreWeights ?: emptyList())
    }
    var previewText by remember { mutableStateOf("你好，这是音色混合的试听效果。") }
    var previewStatus by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 音速/音调/音量，从已有配置恢复
    var speed by remember { mutableStateOf(speech.ttsSpeed) }
    var pitch by remember { mutableStateOf(speech.ttsPitch) }
    var vol by remember { mutableStateOf(speech.ttsVol) }

    fun updateSpeech(weights: List<TimbreWeight>) {
        selectedWeights = weights
        onChange(speech.copy(timbreWeights = weights.ifEmpty { null }, ttsSpeed = speed, ttsPitch = pitch, ttsVol = vol))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { LineIcon(ErpanIcon.BACK) }
            Text("音色调试室", fontSize = 24.sp, fontFamily = FontFamily.Serif)
        }

        // 语言分类标签（横滑）
        val tabRowScroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalScroll(tabRowScroll).padding(horizontal = 12.dp)) {
            MINIMAX_VOICE_GROUPS.forEachIndexed { index, group ->
                val isSelected = index == selectedTab
                Surface(
                    onClick = { selectedTab = index; searchQuery = "" },
                    color = if (isSelected) ErpanColors.Rose else ErpanColors.Paper,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(0.8.dp, if (isSelected) ErpanColors.Rose else ErpanColors.Line),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        "${group.language} (${group.voices.size})",
                        fontSize = 13.sp,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else ErpanColors.Muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("在 ${MINIMAX_VOICE_GROUPS[selectedTab].language} 中搜索音色…", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = ErpanColors.Line,
                focusedBorderColor = ErpanColors.Rose,
            ),
        )

        // 音色网格区域（占剩余空间，可滚动）
        val currentGroup = MINIMAX_VOICE_GROUPS[selectedTab]
        val filtered = if (searchQuery.isBlank()) currentGroup.voices
            else currentGroup.voices.filter {
                it.voiceId.contains(searchQuery, true) || it.displayName.contains(searchQuery, true)
            }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 使用普通 Column + forEach 模拟网格（LazyVerticalGrid 在嵌套 weight 下不稳定）
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                // 两列布局：手动拆分
                val pairs = filtered.chunked(2)
                pairs.forEach { rowVoices ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowVoices.forEach { voice ->
                            val isSelected = selectedWeights.any { it.voiceId == voice.voiceId }
                            val isFull = selectedWeights.size >= 4 && !isSelected
                            Surface(
                                onClick = {
                                    if (isSelected) {
                                        // 取消选中
                                        updateSpeech(selectedWeights.filterNot { it.voiceId == voice.voiceId })
                                    } else if (!isFull) {
                                        // 选中，默认权重 50
                                        updateSpeech(selectedWeights + TimbreWeight(voice.voiceId, 50))
                                    }
                                },
                                enabled = !isFull,
                                color = if (isSelected) ErpanColors.Rose.copy(alpha = 0.08f) else ErpanColors.Paper,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 0.8.dp,
                                    if (isSelected) ErpanColors.Rose else ErpanColors.Line
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(voice.displayName, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) ErpanColors.Rose else androidx.compose.ui.graphics.Color.Unspecified)
                                    Text(voice.voiceId, fontSize = 10.sp, color = ErpanColors.Muted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                        // 如果行只有1个，补一个占位
                        if (rowVoices.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // 底部固定区域：已选音色 + 调音台 + 试听
        Surface(
            color = ErpanColors.Paper,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已选音色 (${selectedWeights.size}/4)", fontSize = 15.sp, fontFamily = FontFamily.Serif)

                selectedWeights.forEachIndexed { index, tw ->
                    val voiceInfo = findVoice(tw.voiceId)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}", fontSize = 12.sp, color = ErpanColors.Muted, modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(voiceInfo?.displayName ?: tw.voiceId, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tw.voiceId, fontSize = 10.sp, color = ErpanColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${tw.weight}", fontSize = 13.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                        Slider(
                            value = tw.weight.toFloat(),
                            onValueChange = { newW ->
                                val newList = selectedWeights.toMutableList()
                                newList[index] = TimbreWeight(tw.voiceId, newW.toInt().coerceIn(1, 100))
                                updateSpeech(newList)
                            },
                            valueRange = 1f..100f,
                            modifier = Modifier.width(120.dp),
                        )
                        IconButton(onClick = { updateSpeech(selectedWeights.filterIndexed { i, _ -> i != index }) },
                            modifier = Modifier.size(28.dp)) {
                            Text("✕", fontSize = 14.sp, color = ErpanColors.Rose)
                        }
                    }
                }

                if (selectedWeights.isEmpty()) {
                    Text("从上方网格中点选音色加入混音（最多 4 个）", fontSize = 12.sp, color = ErpanColors.Muted)
                }

                HorizontalDivider(thickness = 0.6.dp, color = ErpanColors.Line)

                // 全局音速/音调/音量
                Text("音频参数", fontSize = 14.sp, fontFamily = FontFamily.Serif)
                SliderRow("音速", speed, 0.5f..2.0f, "×") { speed = it; onChange(speech.copy(timbreWeights = selectedWeights.ifEmpty { null }, ttsSpeed = it, ttsPitch = pitch, ttsVol = vol)) }
                SliderRow("音调", pitch, -12f..12f, "") { pitch = it; onChange(speech.copy(timbreWeights = selectedWeights.ifEmpty { null }, ttsSpeed = speed, ttsPitch = it, ttsVol = vol)) }
                SliderRow("音量", vol, 0.5f..2.0f, "×") { vol = it; onChange(speech.copy(timbreWeights = selectedWeights.ifEmpty { null }, ttsSpeed = speed, ttsPitch = pitch, ttsVol = it)) }

                HorizontalDivider(thickness = 0.6.dp, color = ErpanColors.Line)

                // 试听区
                OutlinedTextField(
                    value = previewText,
                    onValueChange = { previewText = it },
                    enabled = !previewing,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("试听文本", fontSize = 13.sp) },
                    shape = RoundedCornerShape(9.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ErpanColors.Line,
                        focusedBorderColor = ErpanColors.Rose,
                    ),
                )
                Button(
                    onClick = {
                        if (!previewing && selectedWeights.isNotEmpty()) {
                            previewing = true
                            previewStatus = "正在合成试听…"
                            scope.launch {
                                try {
                                    onPreviewTimbre(previewText)
                                    previewStatus = "试听播放完毕"
                                } catch (e: Exception) {
                                    previewStatus = "试听失败：${e.message ?: "未知错误"}"
                                }
                                previewing = false
                            }
                        }
                    },
                    enabled = !previewing && selectedWeights.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text(if (previewing) "合成中…" else "▶ 试听混合效果", fontSize = 15.sp) }
                if (previewStatus.isNotBlank()) {
                    Text(previewStatus, fontSize = 12.sp,
                        color = if (previewStatus.contains("失败")) ErpanColors.Rose else ErpanColors.Muted,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, color = ErpanColors.Muted, modifier = Modifier.width(40.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.1f$suffix", value), fontSize = 12.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}