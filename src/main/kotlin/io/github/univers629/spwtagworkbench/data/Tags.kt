// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.read.CoverMime
import io.github.univers629.spwtagworkbench.backend.read.CoverStore
import io.github.univers629.spwtagworkbench.backend.read.TrackReader
import io.github.univers629.spwtagworkbench.backend.write.AudioTagWriter
import java.io.File

/**
 * UI 面向标签读写的门面。
 *
 * **真正的写盘全部走 [AudioTagWriter]**：
 * 释放播放 → 复制到临时文件 → 写 → 回读校验 → 先删后改名 → 还原属性。
 * 这里只负责「字段枚举映射」和「路径 → 统一模型」的转换。
 */
object Tags {
    /** UI 侧的可编辑字段，与后端 [TagField] 一一对应。 */
    enum class Key(
        val label: String,
        val field: TagField,
    ) {
        Title("标题", TagField.Title),
        Artist("艺术家", TagField.Artist),
        Album("专辑", TagField.Album),
        AlbumArtist("专辑艺术家", TagField.AlbumArtist),
        Genre("流派", TagField.Genre),
        Year("年份", TagField.Year),
    }

    data class Result(
        val ok: Boolean,
        val message: String,
    )

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 从文件读完整标签：这是权威来源，宿主数据库只是缓存。 */
    fun read(path: String): Track? = TrackReader.readFile(path)

    fun readLyrics(path: String): String? = TrackReader.readLyrics(path)

    fun readEmbeddedCover(path: String): ByteArray? {
        if (path.isBlank()) return null
        return CoverStore.loadFromFile(path, path)
    }

    /** 文件探针结果：只回答「有没有」，不搬字节。 */
    data class Probe(
        val hasLyrics: Boolean,
        val hasCover: Boolean,
    )

    /**
     * 轻量探测：文件里是否已有歌词与封面。
     *
     * 批量匹配的「跳过完整标签」判定只需要这两个布尔量，不该为此把
     * 封面字节和整段歌词读进内存——几百首下来就是几十 MB。
     */
    fun probe(path: String): Probe {
        if (path.isBlank()) return Probe(false, false)
        val lyrics = runCatching { TrackReader.readLyrics(path) }.getOrNull()
        val cover =
            runCatching {
                // 只查是否存在，不保留字节
                CoverStore.loadFromFile(path, path)
            }.getOrNull()
        return Probe(
            hasLyrics = !lyrics.isNullOrBlank(),
            hasCover = cover != null && cover.isNotEmpty(),
        )
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    fun writeField(
        path: String,
        key: Key,
        value: String,
        reloadLyricsForId: String? = null,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, "文件不存在或不可读")
        val outcome = AudioTagWriter.writeField(track, key.field, value, reloadLyricsForId)
        return Result(outcome.ok, decorate(outcome.message, outcome.hostRefreshed))
    }

    fun writeLyrics(
        path: String,
        lrcText: String,
        reloadLyricsForId: String? = null,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, "文件不存在或不可读")
        val outcome =
            AudioTagWriter.write(
                track = track,
                patch = TagPatch.single(TagField.Lyrics, lrcText),
                reloadLyricsForId = reloadLyricsForId,
            )
        return Result(outcome.ok, decorate(outcome.message, outcome.hostRefreshed))
    }

    fun writeCover(
        path: String,
        bytes: ByteArray,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, "文件不存在或不可读")
        val cover = CoverMime.asCover(bytes) ?: return Result(false, "封面数据为空")
        val outcome = AudioTagWriter.writeCover(track, cover)
        return Result(outcome.ok, decorate(outcome.message, outcome.hostRefreshed))
    }

    /**
     * 写入**艺术家图**：`type = 8`（Artist）。
     *
     * 与 [writeCover]（`type = 3`，Front Cover）是两张不同的图。
     * ID3v2 的 APIC / FLAC 的 PICTURE 允许多张不同类型共存，互不覆盖。
     */
    fun writeArtistCover(
        path: String,
        bytes: ByteArray,
    ): Result {
        val track = ensureTrack(path) ?: return Result(false, "文件不存在或不可读")
        val cover = CoverMime.asCover(bytes) ?: return Result(false, "艺术家图数据为空")
        val outcome = AudioTagWriter.writeCover(track, cover, pictureType = 8)
        return Result(outcome.ok, decorate(outcome.message, outcome.hostRefreshed))
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun ensureTrack(path: String): Track? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        return Track(id = "", path = path)
    }

    private fun decorate(
        message: String,
        refreshed: Boolean,
    ): String = if (refreshed) message else "$message（宿主刷新未响应）"
}
