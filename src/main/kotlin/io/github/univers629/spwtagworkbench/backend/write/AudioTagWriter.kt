// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.write

import io.github.univers629.spwtagworkbench.backend.host.HostBridge
import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.model.WriteOutcome
import io.github.univers629.spwtagworkbench.backend.read.ApeV2
import io.github.univers629.spwtagworkbench.backend.read.ContainerKind
import io.github.univers629.spwtagworkbench.backend.read.DffTags
import io.github.univers629.spwtagworkbench.backend.read.Id3v2
import io.github.univers629.spwtagworkbench.backend.read.OggTags
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.Settings
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
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

object AudioTagWriter {
    /** ID3v2 APIC / FLAC PICTURE 的 Front Cover（专辑封面）。 */
    const val PICTURE_FRONT_COVER = 3

    /** ID3v2 APIC / FLAC PICTURE 的 Artist（艺术家图）。MP4 与 APEv2 容器没有这种类型。 */
    const val PICTURE_ARTIST = 8

    /** APEv2 容器只有一套封面（`Cover Art (Front)`）。 */
    const val APE_SINGLE_ARTWORK_MESSAGE = "APEv2 容器（APE/WV/TAK/TTA/MPC）只有一套封面，不支持单独写入艺术家图"

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

            val container = ContainerKind.detect(temp.toFile())
            if (container == ContainerKind.AAC) {
                // AAC 裸流没有内建标签区：标签就是文件开头的一段 ID3v2，
                // 只重写这段前缀，音频帧一个字节不动。
                writeId3Prefix(temp.toFile(), patch, cover, coverType)
                verifyId3Tag(Id3v2.read(temp.toFile()), patch, cover, coverType)
            } else if (container == ContainerKind.DFF) {
                // DFF 把 ID3v2 放在 `ID3 ` 块里：重建块链，音频块原样保留
                writeDff(temp.toFile(), patch, cover, coverType)
                verifyId3Tag(DffTags.read(temp.toFile()), patch, cover, coverType)
            } else if (container == ContainerKind.OPUS) {
                // Opus：注释在 Ogg 第二个逻辑包里；封面存 METADATA_BLOCK_PICTURE
                writeOggTags(temp.toFile(), patch, cover, coverType)
                verifyOggTags(temp.toFile(), patch, cover, coverType)
            } else if (container == ContainerKind.TTA && Id3v2.read(temp.toFile()) != null) {
                // TTA 的主标签习惯是文件开头的 ID3v2：沿用前缀写法，并同步尾部 ID3v1
                writeId3Prefix(temp.toFile(), patch, cover, coverType)
                verifyId3Tag(Id3v2.read(temp.toFile()), patch, cover, coverType)
                Id3v1.sync(temp.toFile(), patch)
            } else if (container.isApeV2) {
                // APEv2（APE / WV / TAK / TTA / MPC）：标签在文件尾部，只重写尾部即可。
                // 这类容器只有一套封面，写艺术家图会顶掉专辑封面，直接拒绝。
                if (coverType == PICTURE_ARTIST) {
                    return WriteOutcome.failure(path, APE_SINGLE_ARTWORK_MESSAGE)
                }
                writeApeV2(temp.toFile(), patch, cover)
                verifyApeV2(temp.toFile(), patch, cover)
            } else {
                applyTo(temp.toFile(), patch, cover, coverType)
                verify(temp.toFile(), patch, cover, coverType)
                // MP3 等容器常同时带 ID3v1：同步它，避免播放器读到旧值
                Id3v1.sync(temp.toFile(), patch)
            }

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
        val tag = alignId3Tag(audio)

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

        applyId3Encoding(tag)
        AudioFileIO.write(audio)
    }

    /**
     * 按设置把 ID3v2 文本帧重编码：2.3 用 UTF-16，2.4 用 UTF-8。
     *
     * 逐帧设置而不是只调标签级 `setEncoding`：转换版本时帧是从旧标签复制过来的，
     * 仍带着旧编码（实测 2.3→2.4 后仍然是 UTF-16）。
     */
    private fun applyId3Encoding(tag: Tag) {
        val id3 = tag as? AbstractID3v2Tag ?: return
        val utf8 = (id3.majorVersion.toInt() and 0xFF) == 4
        val encodingId =
            runCatching {
                TextEncoding.getInstanceOf().getIdForCharset(if (utf8) Charsets.UTF_8 else Charsets.UTF_16)
            }.getOrDefault(-1)
        if (encodingId < 0) return
        val fields = runCatching { id3.fields }.getOrNull() ?: return
        while (fields.hasNext()) {
            val frame = fields.next() as? AbstractID3v2Frame ?: continue
            runCatching {
                val body = frame.body
                if (body is AbstractFrameBodyTextInfo) body.setTextEncoding(encodingId.toByte())
            }
        }
    }

    /**
     * 按设置把 ID3v2 标签对齐到指定版本。
     *
     * 只对「本来就是 ID3v2」或「扩展名属于 ID3 体系」的文件动手；
     * 其它容器（FLAC/Vorbis/APEv2/MP4）返回原标签，不在这里改。
     */
    private fun alignId3Tag(audio: AudioFile): Tag {
        val desired = Settings.id3v2Version
        val targetMajor = if (desired == 4) 4 else 3
        val existing = audio.tag
        if (existing is AbstractID3v2Tag && (existing.majorVersion.toInt() and 0xFF) == targetMajor) {
            return existing
        }
        val id3Container = existing is AbstractID3v2Tag
        if (!id3Container && existing != null) return existing
        val converted: Tag =
            when {
                existing is AbstractID3v2Tag && targetMajor == 3 -> ID3v23Tag(existing)
                existing is AbstractID3v2Tag && targetMajor == 4 -> ID3v24Tag(existing)
                targetMajor == 3 -> ID3v23Tag()
                else -> ID3v24Tag()
            }
        runCatching { audio.setTag(converted) }
        return converted
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

    // ── ID3v2 系：AAC 前置标签 / DFF 的 ID3 块 ───────────────────────────────

    /** 字段与 ID3v2 帧 ID 的对应关系（年份字段随版本变化）。 */
    internal fun id3FrameId(
        field: TagField,
        major: Int,
    ): String =
        when (field) {
            TagField.Title -> "TIT2"
            TagField.Artist -> "TPE1"
            TagField.Album -> "TALB"
            TagField.AlbumArtist -> "TPE2"
            TagField.Genre -> "TCON"
            TagField.Year -> Id3v2.yearFrameId(major)
            TagField.Track -> "TRCK"
            TagField.Disc -> "TPOS"
            TagField.Lyricist -> "TEXT"
            TagField.Composer -> "TCOM"
            TagField.Comment -> "COMM"
            TagField.Lyrics -> "USLT"
        }

    /** 在原有标签基础上合并补丁与封面，按设置构造 v2.3/v2.4 字节。 */
    private fun buildId3TagBytes(
        existing: Id3v2.Tag?,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ): ByteArray {
        val major = if (Settings.id3v2Version == 4) 4 else 3
        val fields = LinkedHashMap<String, String>(existing?.fields.orEmpty())
        // 年份帧在不同版本里名字不同，切换时先清掉旧的
        fields.remove("TYER")
        fields.remove("TDRC")
        val lyrics: String? =
            patch.value(TagField.Lyrics)?.let { if (it.isBlank()) null else it } ?: existing?.lyrics
        for ((field, value) in patch.entries) {
            if (field == TagField.Lyrics) continue
            val id = id3FrameId(field, major)
            if (value.isBlank()) fields.remove(id) else fields[id] = value
        }
        val pictures = existing?.pictures.orEmpty().toMutableList()
        if (cover != null) {
            val type = if (coverType == PICTURE_ARTIST) PICTURE_ARTIST else PICTURE_FRONT_COVER
            pictures.removeAll { it.type == type }
            pictures += Id3v2.Picture(type = type, mime = cover.mime, description = "", bytes = cover.bytes)
        }
        return Id3v2.build(version = major, values = fields, lyrics = lyrics, pictures = pictures)
    }

    /** 前置 ID3v2（AAC 裸流、带 ID3v2 的 TTA）：只重写前缀，音频不动。 */
    private fun writeId3Prefix(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ) {
        val bytes = file.readBytes()
        val existing = Id3v2.readAt(bytes, 0)
        val oldSize = existing?.totalSize ?: 0
        if (oldSize > bytes.size) throw IOException("ID3v2 标签长度超出文件大小，未写入")
        val tagBytes = buildId3TagBytes(existing, patch, cover, coverType)
        val audio = bytes.copyOfRange(oldSize, bytes.size)
        file.writeBytes(tagBytes + audio)
    }

    /** DFF：标签是文件里的 `ID3 ` 块，重建块链后追加在末尾（音频块原样保留）。 */
    private fun writeDff(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ) {
        val existing = DffTags.read(file)
        val tagBytes = buildId3TagBytes(existing, patch, cover, coverType)
        DffTags.write(file, tagBytes)
    }

    /** 回读校验（AAC / DFF 共用）。 */
    private fun verifyId3Tag(
        tag: Id3v2.Tag?,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ) {
        if (tag == null) throw IOException("回读校验失败：ID3v2 标签未写入，原文件未替换")
        val major = tag.major
        for ((field, expectedRaw) in patch.entries) {
            val expected = expectedRaw.trim()
            val actual =
                if (field == TagField.Lyrics) {
                    tag.lyrics.orEmpty()
                } else {
                    tag.text(id3FrameId(field, major)).orEmpty()
                }
            if (!sameValue(field, actual, expected)) {
                throw IOException(
                    "回读校验失败：${field.label} 实际为「${actual.take(24)}」，期望「${expected.take(24)}」，原文件未替换",
                )
            }
        }
        if (cover != null) {
            val type = if (coverType == PICTURE_ARTIST) PICTURE_ARTIST else PICTURE_FRONT_COVER
            val hit = tag.pictures.firstOrNull { it.type == type }
            if (hit == null || hit.bytes.size != cover.bytes.size) {
                throw IOException("回读校验失败：图片（ID3v2）大小不符，原文件未替换")
            }
        }
    }
    // ── Opus（OggTags）──────────────────────────────────────────────────────

    /** 字段与 Vorbis/Opus 注释键的对应关系。 */
    internal fun oggKeyOf(field: TagField): String =
        when (field) {
            TagField.Title -> "TITLE"
            TagField.Artist -> "ARTIST"
            TagField.Album -> "ALBUM"
            TagField.AlbumArtist -> "ALBUMARTIST"
            TagField.Genre -> "GENRE"
            TagField.Year -> "DATE"
            TagField.Track -> "TRACKNUMBER"
            TagField.Disc -> "DISCNUMBER"
            TagField.Lyricist -> "LYRICIST"
            TagField.Composer -> "COMPOSER"
            TagField.Comment -> "COMMENT"
            TagField.Lyrics -> "LYRICS"
        }

    private fun writeOggTags(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ) {
        val values = LinkedHashMap<String, String?>()
        for ((field, value) in patch.entries) {
            values[oggKeyOf(field)] = if (value.isBlank()) null else value
        }
        val pictures =
            if (cover == null) {
                null
            } else {
                val type = if (coverType == PICTURE_ARTIST) PICTURE_ARTIST else PICTURE_FRONT_COVER
                OggTags.pictures(file).filter { it.type != type } +
                    OggTags.Picture(type = type, mime = cover.mime, bytes = cover.bytes)
            }
        OggTags.write(file, values, pictures)
    }

    private fun verifyOggTags(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
        coverType: Int,
    ) {
        val comment = OggTags.read(file) ?: throw IOException("回读校验失败：OpusTags 未写入，原文件未替换")
        if (cover != null) {
            val type = if (coverType == PICTURE_ARTIST) PICTURE_ARTIST else PICTURE_FRONT_COVER
            val hit = OggTags.pictures(file).firstOrNull { it.type == type }
            if (hit == null || hit.bytes.size != cover.bytes.size) {
                throw IOException("回读校验失败：图片（Opus）大小不符，原文件未替换")
            }
        }
        for ((field, expectedRaw) in patch.entries) {
            val expected = expectedRaw.trim()
            val actual = comment.text(oggKeyOf(field)).orEmpty()
            if (!sameValue(field, actual, expected)) {
                throw IOException(
                    "回读校验失败：${field.label} 实际为「${actual.take(24)}」，期望「${expected.take(24)}」，原文件未替换",
                )
            }
        }
    }

    // ── APEv2 ────────────────────────────────────────────────────────────────

    /** 字段与 APEv2 键的对应关系（键名按 APEv2 惯例）。 */
    internal fun apeKeyOf(field: TagField): String =
        when (field) {
            TagField.Title -> ApeV2.Key.TITLE
            TagField.Artist -> ApeV2.Key.ARTIST
            TagField.Album -> ApeV2.Key.ALBUM
            TagField.AlbumArtist -> ApeV2.Key.ALBUM_ARTIST
            TagField.Genre -> ApeV2.Key.GENRE
            TagField.Year -> ApeV2.Key.YEAR
            TagField.Track -> ApeV2.Key.TRACK
            TagField.Disc -> ApeV2.Key.DISC
            TagField.Lyricist -> "Lyricist"
            TagField.Composer -> "Composer"
            TagField.Comment -> ApeV2.Key.COMMENT
            TagField.Lyrics -> ApeV2.Key.LYRICS
        }

    /** 封面在 APEv2 里是「文件名 + 0 + 图片字节」。 */
    internal fun apeCoverValue(cover: CoverArt): ByteArray {
        val ext =
            when (cover.mime.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
        return "cover.$ext".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + cover.bytes
    }

    private fun writeApeV2(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
    ) {
        val values = LinkedHashMap<String, ByteArray?>()
        for ((field, value) in patch.entries) {
            values[apeKeyOf(field)] = if (value.isBlank()) null else value.toByteArray(Charsets.UTF_8)
        }
        cover?.let { values[ApeV2.Key.COVER_FRONT] = apeCoverValue(it) }
        ApeV2.write(file, values)
    }

    private fun verifyApeV2(
        file: File,
        patch: TagPatch,
        cover: CoverArt?,
    ) {
        val tag = ApeV2.read(file) ?: throw IOException("回读校验失败：APEv2 标签未写入，原文件未替换")
        for ((field, expectedRaw) in patch.entries) {
            val expected = expectedRaw.trim()
            val actual = tag.text(apeKeyOf(field)).orEmpty()
            if (!sameValue(field, actual, expected)) {
                throw IOException(
                    "回读校验失败：${field.label} 实际为「${actual.take(24)}」，期望「${expected.take(24)}」，原文件未替换",
                )
            }
        }
        if (cover != null) {
            val item =
                tag.item(ApeV2.Key.COVER_FRONT)
                    ?: throw IOException("回读校验失败：图片（APEv2）未写入，原文件未替换")
            val bytes = ApeV2.coverBytes(item.value)
            if (bytes.size != cover.bytes.size) {
                throw IOException(
                    "回读校验失败：图片（APEv2）大小 ${bytes.size} ≠ ${cover.bytes.size}，原文件未替换",
                )
            }
        }
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
