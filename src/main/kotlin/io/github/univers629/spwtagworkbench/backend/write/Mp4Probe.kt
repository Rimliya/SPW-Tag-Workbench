// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.write

import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 / M4A（ISO-BMFF）容器探测，用于在写入前识别两类无法安全处理的文件。
 *
 * Apple Music 与 iTunes 的文件是 MP4 容器：标签在 `moov/udta/meta/ilst`，音频在 `mdat`，
 * 而 `stco` / `co64` 记录每个音频块的**绝对偏移**。改标签会改变 `moov` 大小，块偏移必须
 * 同步重算；索引与真实数据对不上时，写入库在定位时会越过缓冲区（典型表现就是 JDK 的
 * `java.nio.Buffer` 抛出 `newPosition > limit`）。另外加密文件（FairPlay，`sinf` / `drms`）
 * 的音频数据本身不可读，写标签没有意义。
 *
 * 这类文件在动手写之前就能判断出来，因此这里只做「读结构与索引」，不修改任何内容。
 */
object Mp4Probe {
    /** 写入前发现的问题。 */
    sealed interface Issue {
        val message: String
    }

    /** Apple Music / iTunes 的加密文件（FairPlay），标签写入无意义。 */
    data object Drm : Issue {
        override val message = "Apple Music 加密文件（FairPlay），无法写入标签"
    }

    /** 容器结构或索引异常，继续写会破坏文件。 */
    data class Broken(
        override val message: String,
    ) : Issue

    /** 只有一套封面（`covr`）的容器，写入艺术家图会覆盖专辑封面。 */
    const val SINGLE_ARTWORK_MESSAGE = "M4A/MP4 容器只有一套封面（covr），不支持单独写入艺术家图"

    private val BRANDS =
        setOf("M4A ", "M4B ", "M4P ", "M4V ", "M4R ", "mp41", "mp42", "isom", "iso2", "qt  ", "3gp4", "3gp5", "f4v ")

    /** 需要继续向下遍历的容器原子。 */
    private val CONTAINERS =
        setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "dinf", "edts", "mvex", "moof", "traf")

    private const val MAX_DEPTH = 10

    /** `meta` 原子在子原子之前有 4 字节 version/flags。 */
    private const val META_PREAMBLE = 4L

    private class State {
        var moov = false
        var drm = false
        var offsetOverflow: Long? = null
        var broken: String? = null
    }

    /** 是否是 MP4 系容器（按 `ftyp` 品牌判断，而不是只看扩展名）。 */
    fun isMp4(file: File): Boolean =
        try {
            RandomAccessFile(file, "r").use { raf -> hasMp4Brand(raf) }
        } catch (_: Throwable) {
            false
        }

    /**
     * 检查文件能否安全写入。
     *
     * @return 非 MP4 或结构正常时返回 null
     */
    fun inspect(file: File): Issue? =
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (!hasMp4Brand(raf)) return null
                val fileSize = raf.length()
                val state = State()
                walk(raf, 0L, fileSize, 0, state)
                when {
                    state.drm -> Drm
                    state.broken != null -> Broken(state.broken!!)
                    !state.moov -> Broken("文件容器结构异常（缺少 moov），写入会破坏文件")
                    state.offsetOverflow != null ->
                        Broken(
                            "文件容器索引异常（音频块偏移 ${state.offsetOverflow} 超出文件大小 $fileSize），写入会破坏文件",
                        )
                    else -> null
                }
            }
        } catch (_: Throwable) {
            // 结构坏到连原子都读不出来；交给写入路径按普通错误处理
            null
        }

    private fun hasMp4Brand(raf: RandomAccessFile): Boolean {
        raf.seek(0)
        val head = ByteArray(12)
        if (raf.read(head, 0, 12) < 12) return false
        if (String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
        return String(head, 8, 4, Charsets.ISO_8859_1) in BRANDS
    }

    /** 遍历 [start, end) 区间内的原子。 */
    private fun walk(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        state: State,
    ) {
        if (depth > MAX_DEPTH) return
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val h = ByteArray(8)
            if (raf.read(h, 0, 8) < 8) return
            var size = readU32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L) {
                val big = ByteArray(8)
                if (raf.read(big, 0, 8) < 8) return
                size = readU64(big, 0)
                headerLen = 16L
            } else if (size == 0L) {
                // 保留到父区间末尾
                size = end - pos
            }
            if (size < headerLen || pos + size > end) {
                state.broken = "原子 $type 长度越界（$pos + $size > $end）"
                return
            }
            if (type == "moov") state.moov = true
            when {
                type == "sinf" || type == "drms" || type == "pinf" -> state.drm = true
                type == "stco" -> checkChunkOffsets(raf, pos + headerLen, size - headerLen, state, raf.length(), wide = false)
                type == "co64" -> checkChunkOffsets(raf, pos + headerLen, size - headerLen, state, raf.length(), wide = true)
                type == "stsd" -> scanSampleDescriptions(raf, pos + headerLen, size - headerLen, state)
                type in CONTAINERS -> {
                    val childStart = pos + headerLen + if (type == "meta") META_PREAMBLE else 0L
                    if (childStart < pos + size) walk(raf, childStart, pos + size, depth + 1, state)
                }
            }
            pos += size
        }
    }

    /** 读取 `stco` / `co64` 的块偏移表，记录超出文件大小的最大值。 */
    private fun checkChunkOffsets(
        raf: RandomAccessFile,
        start: Long,
        length: Long,
        state: State,
        fileSize: Long,
        wide: Boolean,
    ) {
        if (length < 8) return
        raf.seek(start + 4)
        val countBytes = ByteArray(4)
        if (raf.read(countBytes, 0, 4) < 4) return
        val count = readU32(countBytes, 0).toInt()
        val entrySize = if (wide) 8 else 4
        val maxReadable = ((length - 8) / entrySize).toInt()
        val n = minOf(count, maxReadable, 20_000)
        if (n <= 0) return
        val buf = ByteArray(n * entrySize)
        if (raf.read(buf, 0, buf.size) < buf.size) return
        for (i in 0 until n) {
            val offset = if (wide) readU64(buf, i * 8) else readU32(buf, i * 4)
            if (offset > fileSize) {
                state.offsetOverflow = maxOf(state.offsetOverflow ?: 0L, offset)
            }
        }
    }

    /**
     * 样本描述（`stsd`）里分辨加密音频：`sinf` / `drms` / `pinf` 都是 FairPlay 的痕迹。
     * `stsd` 自身是叶子结构，直接读这一小段内容做字符串匹配即可。
     */
    private fun scanSampleDescriptions(
        raf: RandomAccessFile,
        start: Long,
        length: Long,
        state: State,
    ) {
        val n = minOf(length, 64L * 1024).toInt()
        if (n <= 0) return
        val buf = ByteArray(n)
        raf.seek(start)
        if (raf.read(buf, 0, n) < n) return
        val text = String(buf, Charsets.ISO_8859_1)
        if (text.contains("sinf") || text.contains("drms") || text.contains("pinf")) {
            state.drm = true
        }
    }

    private fun readU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
