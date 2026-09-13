package com.huigu.phone10.mobile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将 16kHz 16bit 单声道 PCM 转为带 RIFF 头的 WAV 字节流。
 */
object WavEncoder {
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Short = 1, bitDepth: Short = 16): ByteArray {
        val totalAudioLen = pcm.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitDepth / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size (16 for PCM)
        header.putShort(1.toShort()) // AudioFormat (1 for PCM)
        header.putShort(channels)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitDepth / 8).toShort()) // BlockAlign
        header.putShort(bitDepth)
        header.put("data".toByteArray())
        header.putInt(totalAudioLen)

        val out = ByteArrayOutputStream(44 + pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
