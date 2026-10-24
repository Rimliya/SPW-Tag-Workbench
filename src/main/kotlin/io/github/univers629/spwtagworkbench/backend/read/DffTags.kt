// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File

/**
 * DFF（DSDIFF）标签读写。
 *
 * 文件是「表单 + 块」结构：`FRM8` + 8 字节大端长度 + 表单类型 `DSD `，
 * 之后是若干块：`FVER`、`PROP`（内含 `FS`/`CHNL`/`CMPR` 等子块）、`DSD`（音频）。
 * 块头 = 4 字节 ID + 8 字节大端长度，负载按偶数对齐补 1 字节。
 *
 * 标签是 `ID3 ` 块，规范允许两种位置：根层（最常见）或 `PROP` 内部。
 * 写入时统一重建：删掉两处的旧 `ID3 `，在末尾追加一份根层 `ID3 `，并修正
 * `FRM8` 与 `PROP` 的长度——音频块原样保留。
 */
object DffTags {
    private const val FORM_ID = "FRM8"
    private const val ID3_ID = "ID3 "

    /** 解析结果：标签 + 音频数据范围（供上层断言「音频未被改动」）。 */
    class Parsed(
        val tag: Id3v2.Tag?,
        val fileSize: Long,
        val formSize: Long,
    )

    /** 读标签（根层优先，其次 PROP 内部）。 */
    fun read(file: File): Id3v2.Tag? = parse(file.readBytes())?.tag

    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < 16) return null
        if (String(bytes, 0, 4, Charsets.ISO_8859_1) != FORM_ID) return null
        val formSize = be64(bytes, 4)
        val chunks = parseChunks(bytes, 16, (12 + formSize).coerceAtMost(bytes.size.toLong()).toInt()) ?: return null
        val rootId3 = chunks.firstOrNull { it.id == ID3_ID }
        val propId3 =
            chunks
                .firstOrNull { it.id == "PROP" }
                ?.let { prop -> parseProp(prop.data)?.firstOrNull { it.id == ID3_ID } }
        val tagBytes = rootId3?.data ?: propId3?.data
        val tag = tagBytes?.let { Id3v2.readAt(it, 0) }
        return Parsed(tag = tag, fileSize = bytes.size.toLong(), formSize = formSize)
    }

    /**
     * 写入标签：删掉旧的 `ID3 `（根层与 PROP 内都删），在末尾追加新的根层 `ID3 `。
     *
     * @return 新文件的总字节数
     */
    fun write(
        file: File,
        tagBytes: ByteArray,
    ): Long {
        val bytes = file.readBytes()
        if (bytes.size < 16 || String(bytes, 0, 4, Charsets.ISO_8859_1) != FORM_ID) {
            throw IllegalStateException("不是可识别的 DFF（DSDIFF）文件")
        }
        val formSize = be64(bytes, 4)
        val formEnd = (12 + formSize).coerceAtMost(bytes.size.toLong()).toInt()
        val formType = if (bytes.size >= 16) bytes.copyOfRange(12, 16) else "DSD ".toByteArray()
        val chunks = parseChunks(bytes, 16, formEnd) ?: throw IllegalStateException("DFF 块结构异常")

        val out = java.io.ByteArrayOutputStream(bytes.size + tagBytes.size + 64)
        // 表单类型先占位，最后回填 FRM8 长度
        out.write(FORM_ID.toByteArray(Charsets.ISO_8859_1))
        out.write(ByteArray(8))
        out.write(formType)
        for (chunk in chunks) {
            when (chunk.id) {
                ID3_ID -> Unit // 旧标签丢弃，稍后统一追加
                "PROP" -> writeChunk(out, "PROP", stripId3FromProp(chunk.data))
                else -> writeChunk(out, chunk.id, chunk.data)
            }
        }
        writeChunk(out, ID3_ID, tagBytes)
        val result = out.toByteArray()
        // FRM8 长度 = 总长 - (4 字节 ID + 8 字节长度)
        val newFormSize = result.size.toLong() - 12
        putBe64(result, 4, newFormSize)
        file.writeBytes(result)
        return result.size.toLong()
    }

    // ── 块解析/写入 ─────────────────────────────────────────────────────────

    private class Chunk(
        val id: String,
        val data: ByteArray,
    )

    private fun parseProp(data: ByteArray): List<Chunk>? {
        if (data.size < 4) return null
        return parseChunks(data, 4, data.size)
    }

    private fun parseChunks(
        bytes: ByteArray,
        from: Int,
        until: Int,
    ): List<Chunk>? {
        val list = mutableListOf<Chunk>()
        var p = from
        while (p + 12 <= until) {
            val id = String(bytes, p, 4, Charsets.ISO_8859_1)
            if (!id.matches(Regex("^[ -~]{4}$"))) break
            val size = be64(bytes, p + 4)
            if (size < 0 || p + 12 + size > until) break
            val data = bytes.copyOfRange(p + 12, (p + 12 + size).toInt())
            list += Chunk(id, data)
            p += 12 + size.toInt() + (size.toInt() % 2)
        }
        return list
    }

    private fun stripId3FromProp(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        val children = parseChunks(data, 4, data.size) ?: return data
        if (children.none { it.id == ID3_ID }) return data
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, 4) // 表单类型，例如 "SND "
        for (child in children) {
            if (child.id == ID3_ID) continue
            writeChunk(out, child.id, child.data)
        }
        return out.toByteArray()
    }

    private fun writeChunk(
        out: java.io.ByteArrayOutputStream,
        id: String,
        data: ByteArray,
    ) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val size = ByteArray(8)
        putBe64(size, 0, data.size.toLong())
        out.write(size)
        out.write(data)
        if (data.size % 2 == 1) out.write(0) // 偶数对齐
    }

    private fun be64(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    private fun putBe64(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
}
