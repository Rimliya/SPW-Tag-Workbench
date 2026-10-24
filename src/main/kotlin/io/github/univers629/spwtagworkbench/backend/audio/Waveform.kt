// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.audio

import com.sun.jna.WString
import io.github.univers629.spwtagworkbench.core.Diag
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max

/**
 * 波形峰值读取：**直接读 PCM**，与频谱图完全无关。
 *
 * 为什么不能复用频谱数据：频谱是一次性把整首 FFT 成 760px 宽的低分辨率图像，
 * 而打轴要的是「当前播放位置附近那一小段」的高分辨率波形，必须按时间区间重新读 PCM。
 * 参考项目 `SPW-Tag-Workbench` 的 `WaveformCache` + `SyncAudioEngine.peaks` 就是这个思路。
 *
 * 每次调用新建解码流、定位到区间起点、读完立即释放——
 * 打轴页只显示一个随播放滚动的窗口，请求频率低、区间小，不值得维护常驻流。
 */
object Waveform {
    private const val BASS_SAMPLE_FLOAT = 0x100
    private const val BASS_STREAM_DECODE = 0x200000
    private const val BASS_UNICODE = 0x80000000.toInt()
    private const val BASS_POS_BYTE = 0
    private const val MAX_READ_SAMPLES = 8192

    /**
     * 读取 [fromMs]–[toMs] 区间的峰值包络。
     *
     * @return 长度为 [buckets] 的数组，每项 0..1；失败返回空数组
     */
    fun peaks(
        path: String,
        fromMs: Long,
        toMs: Long,
        buckets: Int,
    ): FloatArray {
        if (buckets <= 0 || path.isBlank()) return FloatArray(0)
        val api = Spectrogram.bassOrNull() ?: return FloatArray(0)
        val start = fromMs.coerceAtLeast(0L)
        val end = if (toMs > start) toMs else start + 1_000L
        if (end <= start) return FloatArray(0)

        var handle = 0
        return try {
            handle =
                api.BASS_StreamCreateFile(
                    false,
                    // BASS_UNICODE 要求 UTF-16 指针：传 String 会按 ANSI 解，中文路径打不开
                    WString(Path.of(path).toAbsolutePath().toString()),
                    0L,
                    0L,
                    BASS_SAMPLE_FLOAT or BASS_STREAM_DECODE or BASS_UNICODE,
                )
            if (handle == 0) {
                Diag.log("waveform: 打开失败 code=${api.BASS_ErrorGetCode()}")
                return FloatArray(0)
            }
            val info = Spectrogram.BassChannelInfo()
            if (!api.BASS_ChannelGetInfo(handle, info)) return FloatArray(0)
            val channels = max(1, info.chans)

            val fromBytes = api.BASS_ChannelSeconds2Bytes(handle, start / 1000.0)
            val toBytes = api.BASS_ChannelSeconds2Bytes(handle, end / 1000.0)
            if (!api.BASS_ChannelSetPosition(handle, fromBytes, BASS_POS_BYTE)) return FloatArray(0)

            val totalFrames = max(1L, (toBytes - fromBytes) / (4L * channels))
            val framesPerBucket = max(1L, totalFrames / buckets)
            val out = FloatArray(buckets)

            val bufferSize = minOf(MAX_READ_SAMPLES.toLong(), (framesPerBucket * channels).coerceAtLeast(1L)).toInt()
            val buffer = FloatArray(bufferSize)

            var bucket = 0
            var framesInBucket = 0L
            // 波形要的是**轮廓**，所以取桶内绝对幅度峰值，不是平均值
            var peak = 0f

            while (bucket < buckets) {
                val got = api.BASS_ChannelGetData(handle, buffer, bufferSize)
                if (got <= 0) break
                val samples = got / 4
                var i = 0
                while (i < samples && bucket < buckets) {
                    // **多声道合并后直接用单帧循环**：每帧只取一次最大值，
                    // 避免内层 channels 循环把开销翻倍（整首歌有上千万帧）。
                    var framePeak = 0f
                    for (c in 0 until channels) {
                        if (i + c < samples) {
                            val v = abs(buffer[i + c])
                            if (v > framePeak) framePeak = v
                        }
                    }
                    if (framePeak > peak) peak = framePeak
                    i += channels
                    framesInBucket++
                    if (framesInBucket >= framesPerBucket) {
                        out[bucket] = peak.coerceIn(0f, 1f)
                        bucket++
                        framesInBucket = 0L
                        peak = 0f
                    }
                }
            }
            if (bucket < buckets) out[bucket] = peak.coerceIn(0f, 1f)
            out
        } catch (t: Throwable) {
            Diag.log("waveform: 读取异常 ${t.javaClass.simpleName} ${t.message.orEmpty().take(60)}")
            FloatArray(0)
        } finally {
            if (handle != 0) runCatching { api.BASS_StreamFree(handle) }
        }
    }
}
