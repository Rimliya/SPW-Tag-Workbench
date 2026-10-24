// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.core.Diag
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * ID3v2 `APIC` / FLAC `PICTURE` 的标准图片类型。
 *
 * 注意标准里**没有**「歌曲封面」和「专辑封面」之分：两者都是 [FRONT_COVER]。
 * 艺术家类图片用 [ARTIST] / [LEAD_ARTIST] / [BAND] 区分用途，
 * 归属哪个艺术家记在 `description` 里。
 */
object PictureTypeStandard {
    const val OTHER = 0
    const val FILE_ICON = 1
    const val FRONT_COVER = 3
    const val BACK_COVER = 4
    const val LEAD_ARTIST = 7
    const val ARTIST = 8
    const val BAND = 10
    const val COMPOSER = 11
    const val LYRICIST = 12

    /**
     * 艺术家类图片的替代顺序。
     *
     * 与 Lyrico 的 `artistPictureTypes` 一致：Artist → LeadArtist → Band。
     * 下方两个槽位按这个顺序取前两张可用的图。
     */
    val ARTIST_CHAIN = listOf(ARTIST, LEAD_ARTIST, BAND)

    fun label(type: Int): String =
        when (type) {
            FRONT_COVER -> "专辑封面"
            BACK_COVER -> "封底"
            ARTIST -> "艺术家"
            LEAD_ARTIST -> "主唱"
            BAND -> "乐队"
            COMPOSER -> "作曲"
            LYRICIST -> "作词"
            OTHER -> "其他"
            FILE_ICON -> "图标"
            else -> "类型 $type"
        }

    /** 是否为艺术家类图片。 */
    fun isArtistType(type: Int): Boolean = type in ARTIST_CHAIN
}

/**
 * 一张内嵌图片。
 *
 * `type` 见 [PictureTypeStandard]；`description` 是标签里的描述文本
 * （ID3v2 用它记艺术家名，见 Lyrico 的归属规则）。
 */
class EmbeddedPicture(
    val type: Int,
    val description: String,
    val bytes: ByteArray,
    val mime: String,
) {
    val sizeBytes: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedPicture) return false
        return type == other.type &&
            description == other.description &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = type
        r = 31 * r + description.hashCode()
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

/**
 * 封面读取与 MIME 嗅探。
 */
object CoverMime {
    /**
     * 按文件头判断图片类型。
     *
     * 不要写死 `image/jpeg`：在线来源并不保证返回 JPEG，
     * PNG / WebP 封面用错 MIME 会在播放器里显示异常。
     */
    fun detect(bytes: ByteArray?): String {
        if (bytes == null || bytes.size < 12) return "image/jpeg"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return "image/gif"
        }
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return "image/bmp"
        if (bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return "image/jpeg"
    }

    fun asCover(bytes: ByteArray?): CoverArt? {
        if (bytes == null || bytes.isEmpty()) return null
        return CoverArt(bytes = bytes, mime = detect(bytes))
    }
}

/**
 * 封面缓存。
 *
 * 同一首歌不要反复解码：字节按曲目 key 缓存，超过容量按最近最少使用淘汰。
 * 只缓存原始字节（UI 层自己解码成位图），保证后端不依赖任何图形类型。
 */
object CoverStore {
    private const val MAX_ENTRIES = 24
    private const val MAX_BYTES = 64L * 1024L * 1024L

    private val order = ArrayDeque<String>()
    private val cache = HashMap<String, ByteArray>()
    private var cachedBytes = 0L

    @Synchronized
    fun cached(key: String): ByteArray? {
        val hit = cache[key] ?: return null
        order.remove(key)
        order.addLast(key)
        return hit
    }

    @Synchronized
    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) return
        cache.put(key, bytes)?.let { cachedBytes -= it.size }
        order.remove(key)
        order.addLast(key)
        cachedBytes += bytes.size
        while (order.size > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
            val evicted = order.removeFirstOrNull() ?: break
            cache.remove(evicted)?.let { cachedBytes -= it.size }
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        order.clear()
        cachedBytes = 0L
    }

    /** 读内嵌封面；带缓存。 */
    fun loadFromFile(
        key: String,
        path: String,
    ): ByteArray? {
        cached(key)?.let { return it }
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        val bytes =
            try {
                val audio = AudioFileIO.read(file)
                audio.tag?.firstArtwork?.binaryData
            } catch (t: Throwable) {
                Diag.log("cover: 读取失败 ${file.name} ${t.javaClass.simpleName}")
                null
            }
        if (bytes != null && bytes.isNotEmpty()) put(key, bytes)
        return bytes
    }

    // ── 多图读取 ─────────────────────────────────────────────────────────────

    private const val MAX_PIC_ENTRIES = 12
    private val picOrder = ArrayDeque<String>()
    private val picCache = HashMap<String, List<EmbeddedPicture>>()

    /**
     * 读全部内嵌图片（带类型与描述）。
     *
     * 与 [loadFromFile] 分开缓存：单图那条路径只关心第一张，这里要全部。
     */
    @Synchronized
    fun readPictures(path: String): List<EmbeddedPicture> {
        if (path.isBlank()) return emptyList()
        picCache[path]?.let { hit ->
            picOrder.remove(path)
            picOrder.addLast(path)
            return hit
        }
        val file = File(path)
        if (!file.isFile) return emptyList()

        val list =
            try {
                val audio = AudioFileIO.read(file)
                val tag = audio.tag ?: return emptyList()
                tag.artworkList.orEmpty().mapNotNull { art ->
                    val data = art.binaryData
                    if (data == null || data.isEmpty()) {
                        null
                    } else {
                        EmbeddedPicture(
                            type = runCatching { art.pictureType }.getOrDefault(0),
                            description = art.description.orEmpty(),
                            bytes = data,
                            mime = art.mimeType ?: CoverMime.detect(data),
                        )
                    }
                }
            } catch (t: Throwable) {
                Diag.log("pictures: 读取失败 ${file.name} ${t.javaClass.simpleName}")
                emptyList()
            }

        picCache[path] = list
        picOrder.remove(path)
        picOrder.addLast(path)
        while (picOrder.size > MAX_PIC_ENTRIES) {
            picOrder.removeFirstOrNull()?.let { picCache.remove(it) }
        }
        return list
    }

    /** 写入封面后清掉该曲目的图片缓存，否则界面还是旧图。 */
    @Synchronized
    fun invalidate(path: String) {
        picCache.remove(path)
        cache.remove(path)
        order.remove(path)
        picOrder.remove(path)
    }

    /** 全部清空（插件停止时调用）。 */
    @Synchronized
    fun clearPictures() {
        picCache.clear()
        picOrder.clear()
    }
}

/**
 * 读取层：把「宿主实体」和「文件」都收成统一模型。
 */
object TrackReader {
    /** 从文件读一份完整标签（封面字节可选）。 */
    fun readFile(
        path: String,
        withCover: Boolean = false,
    ): io.github.univers629.spwtagworkbench.backend.model.Track? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        return try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            val header = audio.audioHeader
            val artwork = if (withCover) tag?.firstArtwork?.binaryData else null
            if (artwork != null && artwork.isNotEmpty()) {
                CoverStore.put(path, artwork)
            }
            io.github.univers629.spwtagworkbench.backend.model.Track(
                id = "",
                path = path,
                title = read(tag, FieldKey.TITLE),
                artist = read(tag, FieldKey.ARTIST),
                album = read(tag, FieldKey.ALBUM),
                albumArtist = read(tag, FieldKey.ALBUM_ARTIST),
                genre = read(tag, FieldKey.GENRE),
                year = read(tag, FieldKey.YEAR),
                track = read(tag, FieldKey.TRACK),
                disc = read(tag, FieldKey.DISC_NO),
                lyrics = read(tag, FieldKey.LYRICS),
                comment = read(tag, FieldKey.COMMENT),
                durationMs =
                    run {
                        val seconds = header?.trackLength?.toLong() ?: 0L
                        seconds * 1000L
                    },
                bitDepth = if ((header?.bitsPerSample ?: 0) > 0) "${header?.bitsPerSample} bit" else "",
                bitrate = header?.bitRate?.let { "$it kbps" } ?: "",
                readable = true,
                hasCover =
                    artwork != null ||
                        run {
                            val any =
                                try {
                                    tag?.firstArtwork?.binaryData
                                } catch (_: Throwable) {
                                    null
                                }
                            any != null
                        },
            )
        } catch (t: Throwable) {
            Diag.log("read: 读文件失败 ${file.name} ${t.javaClass.simpleName}")
            null
        }
    }

    /** 只读歌词文本。 */
    fun readLyrics(path: String): String? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val text = AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)
            text?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Diag.log("read: 读歌词失败 ${file.name} ${t.javaClass.simpleName}")
            null
        }
    }

    private fun read(
        tag: org.jaudiotagger.tag.Tag?,
        key: FieldKey,
    ): String {
        if (tag == null) return ""
        return try {
            tag.getFirst(key)?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
