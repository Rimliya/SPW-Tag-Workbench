// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.audio

import com.sun.jna.WString
import io.github.univers629.spwtagworkbench.core.Diag
import java.io.File

/**
 * 回放增益扫描：BASS 解码 → [Ebur128] 测量 → 换算成 ReplayGain 标签值。
 *
 * 分工：
 * - **BASS** 负责解码（宿主自带，支持 FLAC / MP3 / APE / WavPack / Opus / DSD 等）
 * - **[Ebur128]** 负责响度测量（随插件分发的原生库）
 * - 本对象只做管道与换算，不碰标签写入
 *
 * 每次解码都是**流式**的：边解边喂，内存占用与音频长度无关。一首 4 分钟
 * 立体声曲目约 2100 万个采样，攒齐再算就是 84 MB，批量处理时必然 OOM。
 */
object ReplayGainScanner {
    /**
     * ReplayGain 2.0 的参考响度。
     *
     * 规范原文：*"In order to maintain backwards compatibility with classic RG,
     * newer RG uses a -18 LUFS reference"*。可调参考值由播放器侧处理，
     * 标签里只存「相对于 −18 LUFS 的增益」，因此这里不做成配置项。
     */
    const val REFERENCE_LUFS = -18.0

    /** 单次喂给原生库的采样数上限（帧数 = 该值 / 声道数）。 */
    private const val CHUNK_SAMPLES = 1 shl 16

    /** 一首曲目的测量结果。 */
    data class TrackScan(
        val path: String,
        /** 集成响度（LUFS）；失败时为 [Double.NaN]。 */
        val integratedLufs: Double = Double.NaN,
        /** 到 [REFERENCE_LUFS] 的增益，可直接写 `REPLAYGAIN_TRACK_GAIN`。 */
        val gainDb: Double = Double.NaN,
        /** 峰值线性幅度（1.0 = 满刻度），可直接写 `REPLAYGAIN_TRACK_PEAK`。 */
        val peakLinear: Double = Double.NaN,
        val peakDbfs: Double = Double.NaN,
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val durationMs: Long = 0L,
        /** 失败原因；成功时为 null。 */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && !integratedLufs.isNaN()

        /** 按规范格式化增益：`[-]a.bb dB`，如 `-9.38 dB` / `5.00 dB`。 */
        fun gainText(): String = formatGain(gainDb)

        /** 按规范格式化峰值：`c.dddddd`。 */
        fun peakText(): String = formatPeak(peakLinear)
    }

    /**
     * 规范规定的增益文本格式：`[-]a.bb dB`。
     *
     * 负增益带 `-`，正增益**不带** `+`（规范的 value 格式是 `[-]a.bb dB`）。
     */
    fun formatGain(db: Double): String =
        if (db.isNaN()) {
            ""
        } else {
            String.format(java.util.Locale.US, "%.2f dB", db)
        }

    /** 规范规定的峰值文本格式：1.0 为满刻度，六位小数足够精确表示 16 位音频。 */
    fun formatPeak(linear: Double): String =
        if (linear.isNaN()) {
            ""
        } else {
            String.format(java.util.Locale.US, "%.6f", linear.coerceAtLeast(0.0))
        }

    /**
     * 测量一首曲目。
     *
     * @param truePeak true 用 ITU-R BS.1770-4 的真实峰值（需重采样，约慢 4 倍）；
     *   false 用采样峰值 —— ReplayGain 标签惯例用后者，够用且快得多
     * @param onProgress 进度回调，0..1；按已解码帧数估算
     */
    fun scan(
        path: String,
        truePeak: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): TrackScan {
        if (path.isBlank()) return TrackScan(path = path, error = "路径为空")
        val file = File(path)
        if (!file.isFile) return TrackScan(path = path, error = "文件不存在")

        val bass = Spectrogram.bassOrNull()
            ?: return TrackScan(path = path, error = "BASS 解码器不可用（宿主未提供或初始化失败）")

        var stream = 0
        try {
            // BASS_UNICODE 要求 UTF-16 指针：传 String 会按 ANSI 解，中文路径打不开
            stream =
                bass.BASS_StreamCreateFile(
                    false,
                    WString(file.absolutePath),
                    0L,
                    0L,
                    BASS_SAMPLE_FLOAT or BASS_STREAM_DECODE or BASS_UNICODE,
                )
            if (stream == 0) {
                val code = bass.BASS_ErrorGetCode()
                return TrackScan(path = path, error = "无法解码（BASS 错误码 $code）")
            }

            val info = Spectrogram.BassChannelInfo()
            if (!bass.BASS_ChannelGetInfo(stream, info)) {
                return TrackScan(path = path, error = "无法读取音频格式（错误码 ${bass.BASS_ErrorGetCode()}）")
            }
            val channels = info.chans.coerceAtLeast(1)
            val sampleRate = info.freq
            if (sampleRate <= 0) return TrackScan(path = path, error = "采样率非法：$sampleRate")

            val totalFrames = estimateFrames(bass, stream, sampleRate)

            return Ebur128.start(channels, sampleRate, truePeak).use { session ->
                val buffer = FloatArray(CHUNK_SAMPLES)
                var decoded = 0L
                while (true) {
                    val readBytes = bass.BASS_ChannelGetData(stream, buffer, buffer.size * 4)
                    if (readBytes <= 0) break
                    val samples = readBytes / 4
                    val frames = samples / channels
                    if (frames <= 0) break
                    session.feed(buffer, frames)
                    decoded += frames
                    if (totalFrames > 0) {
                        onProgress((decoded.toDouble() / totalFrames).toFloat().coerceIn(0f, 1f))
                    }
                }

                if (session.frames <= 0) {
                    return TrackScan(path = path, error = "解码结果为空（0 个采样）", channels = channels, sampleRate = sampleRate)
                }

                val result = session.result()
                TrackScan(
                    path = path,
                    integratedLufs = result.integratedLufs,
                    gainDb = result.gainDb(REFERENCE_LUFS),
                    peakLinear = result.peakLinear,
                    peakDbfs = result.peakDbfs,
                    channels = channels,
                    sampleRate = sampleRate,
                    durationMs = decoded * 1000L / sampleRate,
                )
            }
        } catch (t: Throwable) {
            Diag.log("replaygain: 扫描失败 ${file.name} ${t.javaClass.simpleName}: ${t.message}")
            return TrackScan(path = path, error = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}")
        } finally {
            if (stream != 0) runCatching { bass.BASS_StreamFree(stream) }
        }
    }

    /** 用字节长度估算总帧数，仅用于进度百分比；估不出来返回 0。 */
    private fun estimateFrames(
        bass: Spectrogram.BassNative,
        stream: Int,
        sampleRate: Int,
    ): Long =
        runCatching {
            val bytes = bass.BASS_ChannelGetLength(stream, BASS_POS_BYTE)
            if (bytes <= 0) return@runCatching 0L
            val seconds = bass.BASS_ChannelBytes2Seconds(stream, bytes)
            if (seconds <= 0) 0L else (seconds * sampleRate).toLong()
        }.getOrDefault(0L)

    // 与 Spectrogram 里保持一致；BASS 的 flags 是库级约定，不随模块变化
    private const val BASS_SAMPLE_FLOAT = 0x100
    private const val BASS_STREAM_DECODE = 0x200000
    private const val BASS_UNICODE = 0x80000000.toInt()
    private const val BASS_POS_BYTE = 0
}
