// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File

/**
 * 自有 ID3v2 编解码器。
 *
 * 标签库（jaudiotagger）只对它认识的容器（MP3 / AIFF / DSF / WAV）写 ID3v2，
 * AAC 裸流、DFF 这类容器需要直接操作 ID3v2 字节，因此这里实现一层最小可用的
 * 读写：文本帧、歌词（USLT）、封面（APIC），版本 2.3 / 2.4。
 *
 * 结构要点：
 * - 头 10 字节：`ID3` + 主版本 + 修订 + 标志 + 4 字节**同步安全整数**长度（每字节 7 位）
 * - 2.3 帧长是普通大端 32 位；2.4 帧长同样是同步安全整数
 * - 文本帧首字节是编码：0=ISO-8859-1、1=UTF-16（带 BOM）、2=UTF-16BE、3=UTF-8
 */
object Id3v2 {
    const val HEADER_SIZE = 10

    /** 一张内嵌图片。 */
    class Picture(
        val type: Int,
        val mime: String,
        val description: String,
        val bytes: ByteArray,
    )

    /** 解析结果。 */
    class Tag(
        val major: Int,
        val fields: Map<String, String>,
        val lyrics: String?,
        val pictures: List<Picture>,
        /** 整个标签占用的字节数（含头与内边距）。 */
        val totalSize: Int,
    ) {
        fun text(frameId: String): String? = fields[frameId]
    }

    /** 文本帧 ID（2.3 与 2.4 多数相同，年份字段不同）。 */
    fun yearFrameId(major: Int): String = if (major >= 4) "TDRC" else "TYER"

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 读文件开头的 ID3v2 标签；没有返回 null。 */
    fun read(file: File): Tag? =
        try {
            val bytes = file.readBytes()
            readAt(bytes, 0)
        } catch (_: Throwable) {
            null
        }

    /** 从 [offset] 处读标签。 */
    fun readAt(
        bytes: ByteArray,
        offset: Int,
    ): Tag? {
        if (offset + HEADER_SIZE > bytes.size) return null
        if (bytes[offset] != 'I'.code.toByte() || bytes[offset + 1] != 'D'.code.toByte() || bytes[offset + 2] != '3'.code.toByte()) {
            return null
        }
        val major = bytes[offset + 3].toInt() and 0xFF
        if (major !in 3..4) return null // 2.2 用 3 字节帧 ID，单独一种解析，暂不支持
        val flags = bytes[offset + 5].toInt() and 0xFF
        val declared = syncsafe(bytes, offset + 6)
        val bodyEnd = minOf(offset + HEADER_SIZE + declared, bytes.size)
        var body = bytes.copyOfRange(offset + HEADER_SIZE, bodyEnd)
        if ((flags and 0x80) != 0) body = deUnsynchronise(body)

        var p = 0
        // 扩展头：2.3 是 4 字节大小（不含自身），2.4 是同步安全整数（含自身）
        if ((flags and 0x40) != 0) {
            p =
                if (major == 4) {
                    val size = syncsafe(body, 0)
                    size.coerceIn(0, body.size)
                } else {
                    val size = be32(body, 0)
                    (size + 4).coerceIn(0, body.size)
                }
        }

        val fields = LinkedHashMap<String, String>()
        var lyrics: String? = null
        val pictures = mutableListOf<Picture>()
        while (p + 10 <= body.size) {
            if (body[p] == 0.toByte()) break // 进入内边距
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (!id.matches(Regex("^[A-Z0-9]{4}$"))) break
            val size = if (major == 4) syncsafe(body, p + 4) else be32(body, p + 4)
            if (size <= 0 || p + 10 + size > body.size) break
            val frame = body.copyOfRange(p + 10, p + 10 + size)
            when {
                id == "USLT" -> lyrics = parseLyrics(frame) ?: lyrics
                id == "APIC" -> parsePicture(frame)?.let { pictures += it }
                id == "COMM" -> parseComment(frame)?.let { fields.putIfAbsent("COMM", it) }
                id.startsWith("T") -> parseText(frame)?.let { fields.putIfAbsent(id, it) }
            }
            p += 10 + size
        }
        val total = HEADER_SIZE + declared
        return Tag(major = major, fields = fields, lyrics = lyrics, pictures = pictures, totalSize = total)
    }

    private fun parseText(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val text = decodeText(frame[0].toInt() and 0xFF, frame, 1)
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseLyrics(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        // 3 字节语言 + 描述（以 0 结尾）+ 正文
        var p = 4
        p = skipTerminated(frame, p, encoding)
        val text = decodeText(encoding, frame, p)
        return text?.trimEnd()?.takeIf { it.isNotBlank() }
    }

    private fun parseComment(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val encoding = frame[0].toInt() and 0xFF
        var p = 4
        p = skipTerminated(frame, p, encoding)
        return decodeText(encoding, frame, p)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parsePicture(frame: ByteArray): Picture? {
        if (frame.size < 6) return null
        val encoding = frame[0].toInt() and 0xFF
        var p = 1
        val mimeEnd = (p until frame.size).firstOrNull { frame[it] == 0.toByte() } ?: return null
        val mime = String(frame, p, mimeEnd - p, Charsets.ISO_8859_1)
        p = mimeEnd + 1
        if (p >= frame.size) return null
        val type = frame[p].toInt() and 0xFF
        p += 1
        val descEnd = terminatorEnd(frame, p, encoding)
        val description = decodeText(encoding, frame, p, descEnd)?.trimStart('\u0000') ?: ""
        p = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (p > frame.size) return null
        val data = frame.copyOfRange(p, frame.size)
        if (data.isEmpty()) return null
        return Picture(type = type, mime = mime, description = description, bytes = data)
    }

    private fun skipTerminated(
        frame: ByteArray,
        start: Int,
        encoding: Int,
    ): Int {
        val end = terminatorEnd(frame, start, encoding)
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        return (end + step).coerceAtMost(frame.size)
    }

    /** 找到以 0 结尾的字符串的终止位置（UTF-16 是双字节 0）。 */
    private fun terminatorEnd(
        frame: ByteArray,
        start: Int,
        encoding: Int,
    ): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < frame.size) {
                if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte()) return i
                i += 2
            }
            return frame.size
        }
        for (i in start until frame.size) if (frame[i] == 0.toByte()) return i
        return frame.size
    }

    private fun decodeText(
        encoding: Int,
        frame: ByteArray,
        from: Int,
        to: Int = frame.size,
    ): String? {
        if (from > to || from >= frame.size) return null
        val slice = frame.copyOfRange(from, minOf(to, frame.size))
        return when (encoding) {
            0 -> String(slice, Charsets.ISO_8859_1)
            1 -> String(slice, Charsets.UTF_16)
            2 -> String(slice, Charsets.UTF_16BE)
            3 -> String(slice, Charsets.UTF_8)
            else -> null
        }?.trim('\u0000')
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 构造 ID3v2 标签字节。
     *
     * @param version 3 = ID3v2.3（文本用 UTF-16）、4 = ID3v2.4（文本用 UTF-8）
     * @param values 帧 ID → 文本值（null 或空串表示不写）
     */
    fun build(
        version: Int,
        values: Map<String, String?>,
        lyrics: String? = null,
        pictures: List<Picture> = emptyList(),
        padding: Int = 1024,
    ): ByteArray {
        val major = if (version == 4) 4 else 3
        val encoding = if (major == 4) 3 else 1
        val frames = java.io.ByteArrayOutputStream()
        for ((id, value) in values) {
            val text = value?.trim().orEmpty()
            if (text.isEmpty() || !id.matches(Regex("^[A-Z0-9]{4}$"))) continue
            frames.write(frameBytes(major, id, textFrameBody(encoding, text)))
        }
        lyrics?.takeIf { it.isNotBlank() }?.let {
            frames.write(frameBytes(major, "USLT", lyricsBody(encoding, it)))
        }
        for (picture in pictures) {
            frames.write(frameBytes(major, "APIC", pictureBody(encoding, picture)))
        }
        val frameBytes = frames.toByteArray()
        val bodySize = frameBytes.size + padding
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + bodySize)
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0) // 修订号
        out.write(0) // 标志位（不写 unsynchronisation，保持结构简单）
        out.write(syncsafeBytes(bodySize))
        out.write(frameBytes)
        if (padding > 0) out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun frameBytes(
        major: Int,
        id: String,
        body: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_SIZE + body.size)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) syncsafeBytes(body.size) else be32Bytes(body.size))
        out.write(ByteArray(2)) // 帧标志
        out.write(body)
        return out.toByteArray()
    }

    private fun textFrameBody(
        encoding: Int,
        text: String,
    ): ByteArray = byteArrayOf(encoding.toByte()) + encodeText(encoding, text)

    private fun lyricsBody(
        encoding: Int,
        lyrics: String,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            "XXX".toByteArray(Charsets.ISO_8859_1) +
            terminator(encoding) +
            encodeText(encoding, lyrics)

    private fun pictureBody(
        encoding: Int,
        picture: Picture,
    ): ByteArray =
        byteArrayOf(encoding.toByte()) +
            picture.mime.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            byteArrayOf(picture.type.toByte()) +
            encodeText(encoding, picture.description) +
            terminator(encoding) +
            picture.bytes

    private fun terminator(encoding: Int): ByteArray = if (encoding == 1 || encoding == 2) ByteArray(2) else ByteArray(1)

    private fun encodeText(
        encoding: Int,
        text: String,
    ): ByteArray =
        when (encoding) {
            0 -> text.toByteArray(Charsets.ISO_8859_1)
            1 -> text.toByteArray(Charsets.UTF_16) // 会带 BOM
            2 -> text.toByteArray(Charsets.UTF_16BE)
            else -> text.toByteArray(Charsets.UTF_8)
        }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    fun syncsafe(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    fun syncsafeBytes(value: Int): ByteArray =
        byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    fun be32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun be32Bytes(value: Int): ByteArray =
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )

    private fun deUnsynchronise(body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            val b = body[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < body.size && body[i + 1] == 0.toByte()) i += 2 else i += 1
        }
        return out.toByteArray()
    }
}
