// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.write

import io.github.univers629.spwtagworkbench.backend.host.HostBridge
import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.model.WriteOutcome
import io.github.univers629.spwtagworkbench.core.Diag
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileTime

/**
 * 单文件标签写入。
 *
 * 流程（顺序不可改，来自原项目实测）：
 * 1. 释放宿主播放占用 —— 正在播的这首要先暂停
 * 2. 复制到同目录临时文件，**保留音频扩展名**（jaudiotagger 靠扩展名选解析器）
 * 3. 在临时文件上写标签
 * 4. 回读校验：字段真的写进去了才算成功
 * 5. **先删原文件，再改名临时文件** —— 不能 move(REPLACE_EXISTING)
 * 6. 还原原文件的 DOS 属性与修改时间
 * 7. 任一步失败：清理临时文件，原文件不动
 *
 * 刻意不用 `runCatching`：Kotlin 2.4.20 在「含 try/catch 的内联调用」上有后端崩溃
 * （`try block should start with NOP`），本项目实测遇到过。
 */
object AudioTagWriter {
    /** ID3v2 APIC / FLAC PICTURE 的 Front Cover（专辑封面）。 */
    const val PICTURE_FRONT_COVER = 3

    /** ID3v2 APIC / FLAC PICTURE 的 Artist（艺术家图）。MP4 容器没有这种类型。 */
    const val PICTURE_ARTIST = 8

    /** 临时文件标记。扫描器据此排除，避免把写了一半的副本当歌曲导入。 */
    const val TEMP_INFIX = ".spw-write-"

    fun isTempFile(name: String?): Boolean = name != null && name.contains(TEMP_INFIX)

    /**
     * 写入一条曲目。
     *
     * @param patch 只包含要改的字段，未出现的字段保持文件原值
     * @param cover 非 null 时替换内嵌封面
     * @param refreshHost 成功后是否刷新宿主音乐库
     * @param reloadLyricsForId 非空时，成功后重载该歌曲的宿主歌词
     */
    fun write(
        track: Track,
        patch: TagPatch,
        cover: CoverArt? = null,
        /** 写图用的 ID3v2 / FLAC 类型：3 = Front Cover，8 = Artist。 */
        coverType: Int = PICTURE_FRONT_COVER,
        refreshHost: Boolean = true,
        reloadLyricsForId: String? = null,
    ): WriteOutcome {
        val path = track.path
        if (path.isBlank()) return WriteOutcome.failure(path, "曲目没有文件路径")
        val target = File(path)
        if (!target.isFile) return WriteOutcome.failure(path, "文件不存在")
        if (!target.canWrite()) return WriteOutcome.failure(path, "文件不可写（只读或权限不足）")
        if (patch.isEmpty && cover == null) return WriteOutcome.failure(path, "没有要写入的字段")

        // MP4/M4A 的写前检查：加密文件、容器索引与真实数据对不上的文件都写不进去，
        // 而且后者的典型表现是底层库抛出 `newPosition > limit`。提前拦下比写坏文件好。
        val mp4 = Mp4Probe.inspect(target)
        if (mp4 != null) return WriteOutcome.failure(path, mp4.message)
        if (coverType == PICTURE_ARTIST && Mp4Probe.isMp4(target)) {
            return WriteOutcome.failure(path, Mp4Probe.SINGLE_ARTWORK_MESSAGE)
        }

        // 1. 释放播放占用
        HostBridge.releasePlaybackFor(path)

        val targetPath = target.toPath()
        val parent =
            targetPath.toAbsolutePath().parent
                ?: return WriteOutcome.failure(path, "文件没有父目录")

        val attributes = snapshotAttributes(targetPath)
        val extension = target.name.substringAfterLast('.', missingDelimiterValue = "")
        val suffix = if (extension.isEmpty()) ".tmp" else ".$extension"

        val temp: Path =
            try {
                Files.createTempFile(parent, ".${target.name}$TEMP_INFIX", suffix)
            } catch (t: Throwable) {
                return WriteOutcome.failure(path, "无法创建临时文件：${describe(t)}")
            }

        var replaced = false
        try {
            Files.copy(targetPath, temp, StandardCopyOption.REPLACE_EXISTING)

            applyTo(temp.toFile(), patch, cover, coverType)
            verify(temp.toFile(), patch, cover, coverType)

            // 5. 先删后改名
            replaceByDeleteThenRename(targetPath, temp)
            replaced = true

            // 6. 还原属性
            restoreAttributes(targetPath, attributes)

            var refreshed = false
            var reloaded = false
            if (refreshHost) {
                refreshed = HostBridge.refreshLibrary("写入 ${target.name}")
                if (!reloadLyricsForId.isNullOrEmpty()) {
                    reloaded = HostBridge.reloadLyrics(reloadLyricsForId)
                }
            }

            val fields = patch.entries.map { it.first }
            Diag.log(
                "write ok: ${target.name} fields=${fields.joinToString { it.label }}" +
                    " cover=${cover != null} refresh=$refreshed lyricsReload=$reloaded",
            )
            return WriteOutcome(
                path = path,
                ok = true,
                message =
                    buildString {
                        append("已写入 ${fields.size} 个字段")
                        if (cover != null) append(" + 封面")
                        if (!refreshed) append("（宿主刷新未响应）")
                    },
                writtenFields = fields,
                hostRefreshed = refreshed,
                lyricsReloaded = reloaded,
            )
        } catch (t: Throwable) {
            val message = describe(t)
            Diag.log("write fail: ${target.name} -> $message")
            return WriteOutcome.failure(path, message)
        } finally {
            // 7. 清理临时文件；改名成功后这里已是 no-op
            if (!replaced) {
                try {
                    Files.deleteIfExists(temp)
                } catch (ignored: Throwable) {
                    Diag.log("write: 临时文件清理失败 ${temp.fileName}")
                }
            }
        }
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    private fun applyTo(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int = PICTURE_FRONT_COVER,
    ) {
        val audio = AudioFileIO.read(file)
        val tag = tagOf(audio)

        for ((field, value) in patch.entries) {
            val key = fieldKey(field)
            if (value.isBlank()) {
                deleteField(tag, key)
            } else {
                tag.setField(key, value)
            }
        }

        if (cover != null) {
            // 只替换**同类型**的图，别的类型原样保留。
            // 这样写艺术家图（type=8）不会把专辑封面（type=3）抹掉。
            deleteArtworkOfType(tag, coverType)
            val artwork = ArtworkFactory.getNew()
            artwork.binaryData = cover.bytes
            artwork.mimeType = cover.mime
            artwork.pictureType = coverType
            // 用 addField 追加；setField 在多图标签上可能覆盖掉已有的图
            addArtwork(tag, artwork)
        }

        AudioFileIO.write(audio)
    }

    // ── 回读校验 ────────────────────────────────────────────────────────────

    private fun verify(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int = PICTURE_FRONT_COVER,
    ) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag

        for ((field, expectedRaw) in patch.entries) {
            val key = fieldKey(field)
            val actual = readFirst(tag, key)
            val expected = expectedRaw.trim()
            if (!sameValue(field, actual, expected)) {
                throw IOException(
                    "回读校验失败：${field.label} 实际为「${actual.take(24)}」，期望「${expected.take(24)}」，原文件未替换",
                )
            }
        }

        if (cover != null) {
            // 必须按类型取图：写艺术家图（type=8）时标签里通常还留着专辑封面（type=3），
            // 取「第一张」会拿到专辑封面，大小校验必然失败。
            val artwork = artworkOfType(tag, coverType) ?: firstArtwork(tag)
            val bytes = artworkBytes(artwork)
            if (bytes == null || bytes.isEmpty()) {
                throw IOException("回读校验失败：图片（type=$coverType）未写入，原文件未替换")
            }
            if (bytes.size != cover.bytes.size) {
                throw IOException(
                    "回读校验失败：图片（type=$coverType）大小 ${bytes.size} ≠ ${cover.bytes.size}，原文件未替换",
                )
            }
        }
    }

    private fun readFirst(
        tag: Tag?,
        key: FieldKey,
    ): String {
        if (tag == null) return ""
        return try {
            tag.getFirst(key)?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 字段值是否一致。
     *
     * 音轨号 / 碟号 / 年份会被 jaudiotagger 归一化（"03" → "3"、"2020-01" → "2020"），
     * 这几类按前导数字比较；其余字段严格比较。
     */
    private fun sameValue(
        field: TagField,
        actual: String,
        expected: String,
    ): Boolean {
        if (actual == expected) return true
        return when (field) {
            TagField.Track, TagField.Disc, TagField.Year -> {
                if (expected.isBlank()) {
                    actual.isBlank()
                } else {
                    leadingNumber(actual) == leadingNumber(expected) && leadingNumber(expected) >= 0
                }
            }

            else -> false
        }
    }

    private fun leadingNumber(value: String): Int {
        val digits = value.trim().takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: -1
    }

    private fun firstArtwork(tag: Tag?): Artwork? {
        if (tag == null) return null
        return try {
            tag.firstArtwork
        } catch (_: Throwable) {
            null
        }
    }

    /** 取标签里指定类型的图片；没有该类型时返回 null。 */
    private fun artworkOfType(
        tag: Tag?,
        pictureType: Int,
    ): Artwork? {
        if (tag == null) return null
        val artworks =
            try {
                tag.artworkList.orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        return pickArtworkOfType(artworks, pictureType)
    }

    /**
     * 从图片列表里挑选指定类型的一张。
     *
     * 类型读不出来（抛异常）时按 Front Cover（type=3）处理，与 [safePictureType] 一致；
     * type=0 是 ID3v2 的 Other，不等于 Front Cover，按自己的类型参与匹配。
     */
    internal fun pickArtworkOfType(
        artworks: List<Artwork?>,
        pictureType: Int,
    ): Artwork? = artworks.firstOrNull { it != null && safePictureType(it) == pictureType }

    private fun artworkBytes(artwork: Artwork?): ByteArray? {
        if (artwork == null) return null
        return try {
            artwork.binaryData
        } catch (_: Throwable) {
            null
        }
    }

    // ── 标签对象辅助 ────────────────────────────────────────────────────────

    private fun tagOf(audio: AudioFile): Tag {
        val existing = audio.tag
        if (existing != null) return existing
        return audio.getTagOrCreateAndSetDefault()
    }

    private fun deleteField(
        tag: Tag,
        key: FieldKey,
    ) {
        try {
            tag.deleteField(key)
        } catch (_: Throwable) {
            // 个别格式不支持删除某字段，忽略（本来也就没有值）
        }
    }

    private fun deleteArtwork(tag: Tag) {
        try {
            tag.deleteArtworkField()
        } catch (_: Throwable) {
            // 无封面字段时删除会抛，忽略
        }
    }

    /** 只删指定类型的图，其余类型保留。 */
    private fun deleteArtworkOfType(
        tag: Tag,
        pictureType: Int,
    ) {
        val existing =
            try {
                tag.artworkList.orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        if (existing.isEmpty()) return
        val keep = existing.filter { safePictureType(it) != pictureType }
        deleteArtwork(tag)
        keep.forEach { kept ->
            try {
                tag.addField(kept)
            } catch (_: Throwable) {
                // 单张失败不影响其它
            }
        }
    }

    /** 读图片类型，取不到时按 Front Cover 处理（老文件常不写类型）。 */
    private fun safePictureType(artwork: Artwork?): Int =
        try {
            artwork?.pictureType ?: PICTURE_FRONT_COVER
        } catch (_: Throwable) {
            PICTURE_FRONT_COVER
        }

    private fun addArtwork(
        tag: Tag,
        artwork: Artwork,
    ) {
        try {
            tag.addField(artwork)
        } catch (t: Throwable) {
            // 有些格式不支持 addField 追加，退回 setField
            try {
                tag.setField(artwork)
            } catch (t2: Throwable) {
                throw IOException("该格式不支持写入封面：${t2.javaClass.simpleName}")
            }
        }
    }

    private fun setArtwork(
        tag: Tag,
        artwork: Artwork,
    ) {
        try {
            tag.setField(artwork)
        } catch (t: Throwable) {
            throw IOException("该格式不支持写入封面：${t.javaClass.simpleName}")
        }
    }

    fun fieldKey(field: TagField): FieldKey =
        when (field) {
            TagField.Title -> FieldKey.TITLE
            TagField.Artist -> FieldKey.ARTIST
            TagField.Album -> FieldKey.ALBUM
            TagField.AlbumArtist -> FieldKey.ALBUM_ARTIST
            TagField.Genre -> FieldKey.GENRE
            TagField.Year -> FieldKey.YEAR
            TagField.Track -> FieldKey.TRACK
            TagField.Disc -> FieldKey.DISC_NO
            TagField.Lyricist -> FieldKey.LYRICIST
            TagField.Composer -> FieldKey.COMPOSER
            TagField.Comment -> FieldKey.COMMENT
            TagField.Lyrics -> FieldKey.LYRICS
        }

    // ── 文件替换与属性 ──────────────────────────────────────────────────────

    /**
     * 先删原文件，再改名临时文件。
     *
     * 不止一次实测：`Files.move(REPLACE_EXISTING)` 覆盖正在播放 / 被宿主打开的文件不可靠。
     * 删除后若改名失败，尽力把临时文件内容写回原路径；**回填也失败时把临时文件
     * 转移成 `<原名>.recovery` 留在原目录**——绝不删掉最后的恢复源，
     * 否则「删原文件 + 删恢复源」双杀会造成用户音频文件永久丢失。
     */
    private fun replaceByDeleteThenRename(
        target: Path,
        temp: Path,
    ) {
        var deleted = false
        try {
            Files.delete(target)
            deleted = true
            Files.move(temp, target)
        } catch (t: Throwable) {
            if (deleted) {
                try {
                    Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING)
                    Diag.log("replace: 改名失败，已用临时文件重建原文件")
                } catch (restore: Throwable) {
                    // 三连失败：原文件已删、重建失败。temp 是用户数据仅存副本，
                    // 改名成 .recovery 保留在原目录，供用户手动恢复。
                    val recovery =
                        target.resolveSibling(target.fileName.toString() + ".recovery")
                    try {
                        Files.move(temp, recovery)
                        Diag.log(
                            "replace: 重建原文件失败（${restore.javaClass.simpleName}），" +
                                "已保留恢复文件 ${recovery.fileName}",
                        )
                    } catch (moveRecovery: Throwable) {
                        // 连 recovery 改名都失败（极端 IO 故障）：什么都不删，
                        // temp 留给外层 finally 的正常清理逻辑处理并如实记录
                        Diag.log(
                            "replace: 保留恢复文件也失败（${moveRecovery.javaClass.simpleName}），" +
                                "临时文件留在 ${temp.fileName}",
                        )
                    }
                }
            }
            throw IOException("替换原文件失败：${describe(t)}")
        }
    }

    private data class Attributes(
        val lastModified: FileTime,
        val readOnly: Boolean,
        val hidden: Boolean,
        val system: Boolean,
        val archive: Boolean,
    )

    private fun snapshotAttributes(path: Path): Attributes? =
        try {
            val lastModified = Files.getLastModifiedTime(path)
            val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java)
            if (view == null) {
                Attributes(lastModified, false, false, false, false)
            } else {
                val attrs: DosFileAttributes = view.readAttributes()
                Attributes(lastModified, attrs.isReadOnly, attrs.isHidden, attrs.isSystem, attrs.isArchive)
            }
        } catch (_: Throwable) {
            null
        }

    private fun restoreAttributes(
        path: Path,
        attributes: Attributes?,
    ) {
        if (attributes == null) return
        try {
            val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java)
            if (view != null) {
                // 只读最后设，否则会挡住后面的还原
                view.setHidden(attributes.hidden)
                view.setSystem(attributes.system)
                view.setArchive(attributes.archive)
            }
            Files.setLastModifiedTime(path, attributes.lastModified)
            if (view != null) view.setReadOnly(attributes.readOnly)
        } catch (t: Throwable) {
            Diag.log("restoreAttributes: 还原属性失败 ${t.javaClass.simpleName}")
        }
    }

    private fun describe(t: Throwable): String {
        val cause = t.cause ?: t
        return when {
            cause is java.nio.file.AccessDeniedException -> "文件被占用或无权限（宿主可能仍在播放）"
            else -> friendlyError(cause.message.orEmpty(), cause.javaClass.simpleName)
        }
    }

    /**
     * 把底层异常翻译成可读提示。
     *
     * 其中 `newPosition > limit: (… > …)` / `newLimit > capacity` 来自 JDK 的
     * `java.nio.Buffer`：标签库在计算文件内部偏移时越过了缓冲区边界，多见于
     * 容器结构异常或截断过的 M4A/AAC 文件（这类文件本来也写不进去）。
     */
    internal fun friendlyError(
        raw: String,
        typeName: String,
    ): String =
        when {
            raw.contains("newPosition > limit") || raw.contains("newLimit > capacity") ->
                "文件容器结构异常（内部偏移超出文件大小），未写入；原文件保持不变。" +
                    "该文件需先用其他工具重新保存，或转成 FLAC/MP3 后再写入"
            raw.isEmpty() -> typeName
            else -> "$typeName: ${raw.take(90)}"
        }

    /** 便捷入口：只改一个字段。 */
    fun writeField(
        track: Track,
        field: TagField,
        value: String,
        reloadLyricsForId: String? = null,
    ): WriteOutcome = write(track, TagPatch.single(field, value), reloadLyricsForId = reloadLyricsForId)

    /** 便捷入口：只写封面。 */
    fun writeCover(
        track: Track,
        cover: CoverArt,
        pictureType: Int = PICTURE_FRONT_COVER,
    ): WriteOutcome = write(track, TagPatch(), cover = cover, coverType = pictureType)
}
