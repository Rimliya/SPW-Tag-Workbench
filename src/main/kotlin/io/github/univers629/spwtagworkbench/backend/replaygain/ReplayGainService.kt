// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.replaygain

import io.github.univers629.spwtagworkbench.backend.audio.ReplayGainScanner
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.model.WriteOutcome
import io.github.univers629.spwtagworkbench.backend.write.AudioTagWriter
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * 回放增益的完整流程：**测量 → 换算 → 写标签**。
 *
 * 三件事分工明确：
 * - [ReplayGainScanner] 解码 + 测响度（BASS → libebur128）
 * - 本对象把结果翻成 ReplayGain 2.0 的四个标签值
 * - [AudioTagWriter] 落盘（各容器的标签体系由它分派）
 *
 * ## 写哪几个键
 *
 * 规范固定四个：`REPLAYGAIN_TRACK_GAIN` / `_TRACK_PEAK` / `_ALBUM_GAIN` / `_ALBUM_PEAK`。
 * **不写 `REPLAYGAIN_REFERENCE_LOUDNESS`** —— 规范明确把它标记为「有害」：
 * 标准只有一个参考目标（−18 LUFS），改参考应当在播放器侧做，
 * 写进文件反而会让不同工具对同一份数据理解不一致。
 *
 * ## 单曲与专辑的关系
 *
 * - 单曲：只算音轨增益，不碰专辑键（专辑增益要整张一起测才有意义）
 * - 专辑：逐条测音轨 + 整张合并测专辑，一次把四个键都写下
 */
object ReplayGainService {
    /** 一次测量 + 写入的结果。 */
    data class Outcome(
        val path: String,
        val scan: ReplayGainScanner.TrackScan,
        /** 标签写入结果；测量失败时为 null。 */
        val write: WriteOutcome?,
        /** 整体是否成功（测量成功且写入成功）。 */
        val ok: Boolean,
        /** 失败原因；成功时为 null。 */
        val error: String?,
    )

    /**
     * 测量一首曲目并写入音轨增益。
     *
     * 只写 `REPLAYGAIN_TRACK_GAIN` 与 `REPLAYGAIN_TRACK_PEAK`。
     * 专辑键需要把整张专辑当作一段音频合并测量，单曲流程拿不到，
     * 因此这里不产生专辑值 —— 文件里已有的专辑值原样保留，由读取侧展示。
     *
     * @param truePeak true 用真实峰值（ITU-R BS.1770-4，慢约 4 倍）；
     *   ReplayGain 标签惯例用采样峰值
     * @param write 是否落盘；false 时只测不写（用于预览）
     */
    fun scanTrack(
        path: String,
        truePeak: Boolean = false,
        write: Boolean = true,
        refreshHost: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): Outcome {
        val scan = ReplayGainScanner.scan(path, truePeak, onProgress)
        if (!scan.ok) {
            return Outcome(path = path, scan = scan, write = null, ok = false, error = scan.error)
        }
        if (!write) {
            return Outcome(path = path, scan = scan, write = null, ok = true, error = null)
        }

        val patch =
            TagPatch()
                .apply {
                    set(TagField.ReplayGainTrackGain, scan.gainText())
                    set(TagField.ReplayGainTrackPeak, scan.peakText())
                }
        val outcome = AudioTagWriter.write(Track(id = "", path = path), patch, refreshHost = refreshHost)
        if (!outcome.ok) {
            Diag.log("replaygain: 写标签失败 ${outcome.message}")
        }
        return Outcome(
            path = path,
            scan = scan,
            write = outcome,
            ok = outcome.ok,
            error = if (outcome.ok) null else outcome.message,
        )
    }

    /**
     * 清除一首曲目的四个 ReplayGain 键。
     *
     * 传空值即删除（[AudioTagWriter] 对空值走删除分支）——
     * 这是「撤销响度数据」的唯一正确做法，写 0 会被播放器当作「增益为 0」而不是「没有数据」。
     */
    fun clear(
        path: String,
        refreshHost: Boolean = true,
    ): WriteOutcome {
        val patch = TagPatch()
        for (field in TagField.replayGainFields) patch.set(field, "")
        return AudioTagWriter.write(Track(id = "", path = path), patch, refreshHost = refreshHost)
    }
}
