// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.model

/**
 * 统一曲目模型。
 *
 * 宿主音乐库拿到的实体和从文件读出来的标签，都归一到这一个类型；
 * UI 层只碰它，不碰宿主实体，也不碰 jaudiotagger 对象。
 */
data class Track(
    /** 宿主歌曲 ID，可能为空（未入库的文件）。 */
    val id: String,
    val path: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
    val lyrics: String = "",
    val comment: String = "",
    val durationMs: Long = 0L,
    val bitDepth: String = "",
    val bitrate: String = "",
    /** 文件是否可读（宿主标记 + 实际探测）。 */
    val readable: Boolean = true,
    /** 文件里是否有内嵌封面（字节不进列表模型）。 */
    val hasCover: Boolean = false,
) {
    val key: String get() = id.ifEmpty { path }
    val hasFile: Boolean get() = path.isNotEmpty() && readable

    companion object {
        val Empty = Track(id = "", path = "")
    }
}

/** 可写入的标签字段。顺序即 UI 展示顺序。 */
enum class TagField(
    val label: String,
) {
    Title("标题"),
    Artist("艺术家"),
    Album("专辑"),
    AlbumArtist("专辑艺术家"),
    Genre("风格"),
    Year("年份"),
    Track("音轨号"),
    Disc("碟号"),
    Lyricist("作词"),
    Composer("作曲家"),
    Comment("注释"),
    Lyrics("歌词"),
    ;

    companion object {
        val textFields: List<TagField> get() = entries.filter { it != Lyrics }
    }
}

/**
 * 一次要写入的字段集合。
 *
 * 语义：**只有显式设置过的字段才会被写**；未设置保持文件原值。
 * 这样才能做到「改一个字段不擦掉其它字段」，也才能让多源合并按字段进行。
 */
class TagPatch {
    private val values = LinkedHashMap<TagField, String>()

    fun set(
        field: TagField,
        value: String?,
    ): TagPatch {
        values[field] = value?.trim().orEmpty()
        return this
    }

    fun remove(field: TagField): TagPatch {
        values.remove(field)
        return this
    }

    fun value(field: TagField): String? = values[field]

    fun has(field: TagField): Boolean = values.containsKey(field)

    val entries: List<Pair<TagField, String>> get() = values.toList()

    val isEmpty: Boolean get() = values.isEmpty()

    companion object {
        fun of(vararg pairs: Pair<TagField, String?>): TagPatch {
            val patch = TagPatch()
            for ((field, value) in pairs) patch.set(field, value)
            return patch
        }

        fun single(
            field: TagField,
            value: String?,
        ): TagPatch = TagPatch().set(field, value)

        /** 从一条曲目复制所有文本字段（批量套用模板用）。 */
        fun fromTrack(
            track: Track,
            fields: List<TagField> = TagField.entries,
        ): TagPatch {
            val patch = TagPatch()
            for (field in fields) {
                val value =
                    when (field) {
                        TagField.Title -> track.title
                        TagField.Artist -> track.artist
                        TagField.Album -> track.album
                        TagField.AlbumArtist -> track.albumArtist
                        TagField.Genre -> track.genre
                        TagField.Year -> track.year
                        TagField.Track -> track.track
                        TagField.Disc -> track.disc
                        TagField.Lyricist -> ""
                        TagField.Composer -> ""
                        TagField.Comment -> track.comment
                        TagField.Lyrics -> track.lyrics
                    }
                if (value.isNotEmpty()) patch.set(field, value)
            }
            return patch
        }
    }
}

/** 封面图片。mime 由文件头嗅探得到，不写死。 */
data class CoverArt(
    val bytes: ByteArray,
    val mime: String,
) {
    override fun equals(other: Any?): Boolean = other is CoverArt && other.mime == mime && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
}

/** 单文件写入结果。status 决定 UI 上给什么反馈。 */
data class WriteOutcome(
    val path: String,
    val ok: Boolean,
    val message: String,
    val writtenFields: List<TagField> = emptyList(),
    val hostRefreshed: Boolean = false,
    val lyricsReloaded: Boolean = false,
) {
    companion object {
        fun failure(
            path: String,
            message: String,
        ) = WriteOutcome(path, false, message)
    }
}

/** 批量写入进度，供 UI 直接渲染。 */
data class BatchProgress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val currentPath: String = "",
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/** 批量写入的汇总。 */
data class BatchReport(
    val total: Int,
    val succeeded: Int,
    val failed: List<WriteOutcome>,
    val cancelled: Boolean,
) {
    val ok: Boolean get() = failed.isEmpty() && !cancelled
    val summary: String
        get() =
            buildString {
                append("成功 $succeeded / $total")
                if (failed.isNotEmpty()) append("，失败 ${failed.size}")
                if (cancelled) append("，已取消")
            }
}
