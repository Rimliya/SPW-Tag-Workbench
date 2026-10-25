// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.model

import io.github.univers629.spwtagworkbench.core.i18n.L10n

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
    /** 作词。ID3v2 是 `TEXT`，Vorbis / APEv2 是自由键。 */
    val lyricist: String = "",
    /** 作曲。ID3v2 是 `TCOM`。 */
    val composer: String = "",
    val durationMs: Long = 0L,
    val bitDepth: String = "",
    val bitrate: String = "",
    /**
     * ReplayGain 四个标签的原始文本（文件里怎么写就怎么存）。
     *
     * 保留原文而不解析成数值：一是格式容错由消费方决定（规范要求播放器容忍
     * 前导 `+`、多余位数、大小写变体），二是没写过这些标签的文件应当是空串，
     * 与「算出来是 0」区分得开。
     */
    val replayGainTrackGain: String = "",
    val replayGainTrackPeak: String = "",
    val replayGainAlbumGain: String = "",
    val replayGainAlbumPeak: String = "",
    /** 文件是否可读（宿主标记 + 实际探测）。 */
    val readable: Boolean = true,
    /** 文件里是否有内嵌封面（字节不进列表模型）。 */
    val hasCover: Boolean = false,
) {
    val key: String get() = id.ifEmpty { path }
    val hasFile: Boolean get() = path.isNotEmpty() && readable

    /** 是否已带 ReplayGain 数据（四个键里至少音轨增益有值）。 */
    val hasReplayGain: Boolean get() = replayGainTrackGain.isNotBlank()

    companion object {
        val Empty = Track(id = "", path = "")
    }
}

/**
 * 可写入的标签字段。顺序即 UI 展示顺序。
 *
 * [tagKey] 是该字段在**自由键体系**里的名字（Vorbis comment / APEv2）：
 * 这两套体系直接按字符串键名写，不需要帧映射表。
 * 普通文本字段填的是各自的惯用键名，ReplayGain 字段填的是规范规定的四个键。
 *
 * ID3v2 里的落法不同：普通字段用固定帧 ID，ReplayGain 用 `TXXX` + 描述符，
 * 描述符就是 [tagKey]。
 */
enum class TagField(
    val label: String,
    val tagKey: String,
    /** ReplayGain 字段：走 `TXXX` / 自由键，不参与普通文本帧映射。 */
    val isReplayGain: Boolean = false,
) {
    Title("标题", "TITLE"),
    Artist("艺术家", "ARTIST"),
    Album("专辑", "ALBUM"),
    AlbumArtist("专辑艺术家", "ALBUMARTIST"),
    Genre("风格", "GENRE"),
    Year("年份", "DATE"),
    Track("音轨号", "TRACKNUMBER"),
    Disc("碟号", "DISCNUMBER"),
    Lyricist("作词", "LYRICIST"),
    Composer("作曲家", "COMPOSER"),
    Comment("注释", "COMMENT"),
    Lyrics("歌词", "LYRICS"),

    // ReplayGain 2.0 的四个键，键名与取值格式由规范固定：
    // 增益形如 `-9.38 dB`，峰值形如 `0.999900`（1.0 = 满刻度）。
    // 规范另有 REPLAYGAIN_REFERENCE_LOUDNESS，但被明确标为「有害」
    // （标准只有一个参考目标，改参考应在播放器侧做），因此不写。
    ReplayGainTrackGain("音轨增益", "REPLAYGAIN_TRACK_GAIN", isReplayGain = true),
    ReplayGainTrackPeak("音轨峰值", "REPLAYGAIN_TRACK_PEAK", isReplayGain = true),
    ReplayGainAlbumGain("专辑增益", "REPLAYGAIN_ALBUM_GAIN", isReplayGain = true),
    ReplayGainAlbumPeak("专辑峰值", "REPLAYGAIN_ALBUM_PEAK", isReplayGain = true),
    ;

    companion object {
        /** 可自由编辑的文本字段（不含歌词与 ReplayGain）。 */
        val textFields: List<TagField> get() = entries.filter { it != Lyrics && !it.isReplayGain }

        /** ReplayGain 的四个字段，按展示顺序。 */
        val replayGainFields: List<TagField> get() = entries.filter { it.isReplayGain }

        /** 按自由键名找字段；找不到返回 null。键名忽略大小写。 */
        fun byTagKey(key: String): TagField? = entries.firstOrNull { it.tagKey.equals(key, ignoreCase = true) }
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
                        TagField.ReplayGainTrackGain -> track.replayGainTrackGain
                        TagField.ReplayGainTrackPeak -> track.replayGainTrackPeak
                        TagField.ReplayGainAlbumGain -> track.replayGainAlbumGain
                        TagField.ReplayGainAlbumPeak -> track.replayGainAlbumPeak
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
    /**
     * 图片描述（ID3v2 APIC 的 content descriptor / FLAC PICTURE 的 description）。
     *
     * ID3v2 规范要求同一标签里多张图必须有**不同的描述**，否则属于重复帧；
     * 两张艺术家图因此靠这里区分槽位。
     */
    val description: String = "",
) {
    override fun equals(other: Any?): Boolean = other is CoverArt && other.mime == mime && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
}

/**
 * 两张艺术家图的槽位标识。
 *
 * 规范没有「序号」字段，槽位身份写进图片描述里：
 * ID3v2 靠它满足「同类型多图描述必须不同」，读取时也优先按描述找回槽位。
 * 其它工具写的图没有这个前缀，读取端回退到出现顺序。
 */
object ArtistSlotMark {
    /** 形如 `SPW-ARTIST-1` / `SPW-ARTIST-2`。 */
    fun description(index: Int): String = "SPW-ARTIST-${index + 1}"

    /** 由描述反查槽位下标；不是本插件写的图返回 null。 */
    fun indexOf(description: String?): Int? {
        val text = description?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        val n = text.removePrefix(PREFIX).toIntOrNull() ?: return null
        return if (n in 1..SLOT_LIMIT) n - 1 else null
    }

    private const val PREFIX = "SPW-ARTIST-"
    private const val SLOT_LIMIT = 8
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
                append(L10n.t("status.batchOk", succeeded, total))
                if (failed.isNotEmpty()) append(L10n.t("status.batchFailedSuffix", failed.size))
                if (cancelled) append(L10n.t("status.batchCancelledSuffix"))
            }
}
