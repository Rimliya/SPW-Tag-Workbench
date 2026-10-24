// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File
import java.io.RandomAccessFile

/**
 * APEv2 标签读写。
 *
 * 标签位于文件**末尾**：`[音频数据][标签头?][条目…][标签尾]`，每段都是 32 字节的
 * `APETAGEX` 结构。真实文件里两种布局都存在：只有尾（`luckynight.ape`）与
 * 头 + 尾（`silence-44-s.wv`），读取必须都支持。
 *
 * 条目结构：`valueLength(4) + flags(4) + key(以 0 结尾) + value(valueLength 字节)`，
 * 键与值都是 UTF-8。封面存在 `Cover Art (Front)` 键里，值是「文件名 0 图片字节」。
 */
object ApeV2 {
    const val FOOTER = "APETAGEX"
    private const val FOOTER_SIZE = 32

    /** 标签尾部标志位。 */
    private const val FLAG_HAS_HEADER = 0x80000000L
    private const val FLAG_NO_FOOTER = 0x40000000L
    private const val FLAG_IS_HEADER = 0x20000000L

    /** 条目标志。 */
    private const val ITEM_READ_ONLY = 0x00000001L
    private const val ITEM_BINARY = 0x00000002L

    /** 一个标签条目。 */
    class Item(
        val key: String,
        val value: ByteArray,
        val flags: Int = 0,
    ) {
        val isBinary: Boolean get() = (flags.toLong() and ITEM_BINARY) != 0L

        fun text(): String = String(value, Charsets.UTF_8)

        override fun toString(): String = "$key=${if (isBinary) "<binary ${value.size}B>" else text()}"
    }

    /** 读完的标签。 */
    class Tag(
        val items: List<Item>,
        /** 标签起始偏移（若有头部，指向头部而不是条目）。 */
        val tagStart: Long,
        val tagEnd: Long,
        val hasHeader: Boolean,
    ) {
        fun item(key: String): Item? = items.firstOrNull { it.key.equals(key, ignoreCase = true) }

        fun text(key: String): String? = item(key)?.takeIf { !it.isBinary }?.text()?.takeIf { it.isNotBlank() }

        /** 音频数据的结束位置（标签之前）。 */
        val audioEnd: Long get() = tagStart
    }

    /** 定位并解析标签；没有标签返回 null。 */
    fun read(file: File): Tag? =
        try {
            RandomAccessFile(file, "r").use { raf -> read(raf) }
        } catch (_: Throwable) {
            null
        }

    private fun read(raf: RandomAccessFile): Tag? {
        val fileLength = raf.length()
        if (fileLength < FOOTER_SIZE) return null
        // ID3v1 可能排在 APEv2 之后（最后 128 字节）
        var id3v1 = 0L
        if (fileLength >= 128 + FOOTER_SIZE) {
            raf.seek(fileLength - 128)
            val tag = ByteArray(3)
            raf.read(tag, 0, 3)
            if (String(tag, Charsets.ISO_8859_1) == "TAG") id3v1 = 128
        }
        val searchEnd = fileLength - id3v1
        val window = minOf(searchEnd, 256L * 1024)
        if (window < FOOTER_SIZE) return null
        val start = searchEnd - window
        raf.seek(start)
        val buf = ByteArray(window.toInt())
        val read = raf.read(buf, 0, buf.size)
        if (read < FOOTER_SIZE) return null
        val text = String(buf, 0, read, Charsets.ISO_8859_1)
        val footerAt = text.lastIndexOf(FOOTER)
        if (footerAt < 0) return null
        val size = u32(buf, footerAt + 12)
        val count = u32(buf, footerAt + 16)
        val flags = u32(buf, footerAt + 20)
        if (size < FOOTER_SIZE || size > window) return null
        val tagEnd = start + footerAt + FOOTER_SIZE
        val tagStart = start + footerAt + FOOTER_SIZE - size
        val hasHeader = (flags and FLAG_IS_HEADER) == 0L && (flags and FLAG_HAS_HEADER) != 0L
        val itemsStart = tagStart
        val itemsEnd = start + footerAt
        val items = mutableListOf<Item>()
        var p = (itemsStart - start).toInt()
        val end = (itemsEnd - start).toInt()
        repeat(count.toInt().coerceAtMost(100_000)) {
            if (p + 8 > end) return@repeat
            val valueLength = u32(buf, p).toInt()
            val itemFlags = u32(buf, p + 4).toInt()
            p += 8
            if (valueLength < 0 || p >= end) return@repeat
            val keyEnd = (p until end).firstOrNull { buf[it] == 0.toByte() } ?: return@repeat
            val key = String(buf, p, keyEnd - p, Charsets.UTF_8)
            p = keyEnd + 1
            if (valueLength > end - p) return@repeat
            val value = buf.copyOfRange(p, p + valueLength)
            p += valueLength
            items += Item(key, value, itemFlags)
        }
        return Tag(
            items = items,
            tagStart = if (hasHeader) tagStart - FOOTER_SIZE else tagStart,
            tagEnd = tagEnd,
            hasHeader = hasHeader,
        )
    }

    /**
     * 把标签写到 [file] 末尾（覆盖已有 APEv2，保留其后的 ID3v1）。
     *
     * 调用方负责「在临时副本上操作」，因此这里直接截断重写，不需要原子性。
     */
    fun write(
        file: File,
        values: Map<String, ByteArray?>,
        removeKeys: Collection<String> = emptyList(),
    ) {
        val existing = read(file)
        val merged = LinkedHashMap<String, Item>()
        existing?.items?.forEach { merged[it.key.lowercase()] = it }
        for ((key, value) in values) {
            val k = key.lowercase()
            if (value == null) merged.remove(k) else merged[k] = Item(key, value)
        }
        for (key in removeKeys) merged.remove(key.lowercase())
        val items = merged.values.toList()
        val payload = buildItems(items)
        val footer = buildFooter(payload.size, items.size, hasHeader = false)
        val tagBytes = payload + footer

        // 保留标签之后的 ID3v1
        var id3v1: ByteArray? = null
        if (file.length() >= 128) {
            val tail = ByteArray(128)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 128)
                raf.read(tail, 0, 128)
            }
            if (String(tail, 0, 3, Charsets.ISO_8859_1) == "TAG") id3v1 = tail
        }
        val audioEnd = existing?.audioEnd ?: (file.length() - (id3v1?.size ?: 0))
        val audio = ByteArray(audioEnd.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            raf.read(audio, 0, audio.size)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(audio.size.toLong())
            raf.seek(audio.size.toLong())
            raf.write(tagBytes)
            id3v1?.let { raf.write(it) }
        }
    }

    private fun buildItems(items: List<Item>): ByteArray {
        var size = 0
        for (item in items) size += 8 + item.key.toByteArray(Charsets.UTF_8).size + 1 + item.value.size
        val out = java.io.ByteArrayOutputStream(size)
        for (item in items) {
            val key = item.key.toByteArray(Charsets.UTF_8)
            // APEv2 的整数字段是**小端**
            out.write(le32(item.value.size))
            out.write(le32(item.flags))
            out.write(key)
            out.write(0)
            out.write(item.value)
        }
        return out.toByteArray()
    }

    private fun buildFooter(
        payloadSize: Int,
        itemCount: Int,
        hasHeader: Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(FOOTER_SIZE)
        out.write(FOOTER.toByteArray(Charsets.ISO_8859_1))
        out.write(le32(2000)) // version，小端
        out.write(le32(payloadSize + FOOTER_SIZE))
        out.write(le32(itemCount))
        out.write(le32(if (hasHeader) FLAG_HAS_HEADER.toInt() else 0))
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private fun u32(
        b: ByteArray,
        o: Int,
    ): Long =
        ((b[o].toLong() and 0xFF) shl 0) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    /** 从 `Cover Art (Front)` 的值里剥掉文件名前缀（值是「文件名 0 图片字节」）。 */
    fun coverBytes(value: ByteArray): ByteArray {
        val sep = value.indexOf(0)
        return if (sep in 0 until value.size - 1) value.copyOfRange(sep + 1, value.size) else value
    }

    /** 标准键名。 */
    object Key {
        const val TITLE = "Title"
        const val ARTIST = "Artist"
        const val ALBUM = "Album"
        const val ALBUM_ARTIST = "Album Artist"
        const val GENRE = "Genre"
        const val YEAR = "Year"
        const val TRACK = "Track"
        const val DISC = "Disc"
        const val COMMENT = "Comment"
        const val LYRICS = "Lyrics"
        const val COVER_FRONT = "Cover Art (Front)"
    }

    /** 未使用的常量：保留给后续「只读/二进制」标记逻辑。 */
    private const val UNUSED_ITEM_FLAGS = ITEM_READ_ONLY
}
