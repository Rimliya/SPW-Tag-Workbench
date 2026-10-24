// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import java.io.File

/**
 * WAV / AIFF 里的 RIFF `INFO` 标签读取。
 *
 * 老一点的音频工具（Sound Forge、GoldWave、部分刻录软件）只写 `LIST/INFO` 块，
 * 不写 ID3。这类文件的 ID3 是空的，光靠标签库会显示「无标题」。
 *
 * 结构：`RIFF`(或 `FORM`) + 长度 + 表单类型，之后是若干块：4 字节 ID + 长度 + 数据（偶数对齐）。
 * `LIST` 块的负载先是一个 4 字节类型（`INFO`），再是若干 `INAM`/`IART` 之类的子块。
 * WAV 的长度是小端，AIFF 是大端。
 */
object RiffInfo {
    /** 返回 标题/艺术家/专辑/流派/年份/音轨/备注，缺失的键不出现。 */
    fun read(file: File): Map<String, String> =
        try {
            parse(file.readBytes())
        } catch (_: Throwable) {
            emptyMap()
        }

    private fun parse(bytes: ByteArray): Map<String, String> {
        if (bytes.size < 12) return emptyMap()
        val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
        val little =
            when (magic) {
                "RIFF" -> true
                "FORM" -> false
                else -> return emptyMap()
            }
        val form = String(bytes, 8, 4, Charsets.ISO_8859_1)
        if (form != "WAVE" && form != "AIFF" && form != "AIFC") return emptyMap()
        val result = LinkedHashMap<String, String>()
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.ISO_8859_1)
            val size = if (little) le32(bytes, p + 4) else be32(bytes, p + 4)
            if (size < 0 || p + 8 + size > bytes.size) break
            val data = bytes.copyOfRange(p + 8, p + 8 + size)
            when (id) {
                "LIST" -> {
                    if (data.size >= 4 && String(data, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        readInfoChildren(data, little, result)
                    }
                }
                // AIFF 自带的文本块（部分工具只写这些）
                "NAME" -> result.putIfAbsent("title", text(data))
                "AUTH" -> result.putIfAbsent("artist", text(data))
                "ANNO" -> result.putIfAbsent("comment", text(data))
            }
            p += 8 + size + (size % 2)
        }
        return result
    }

    private fun readInfoChildren(
        data: ByteArray,
        little: Boolean,
        out: MutableMap<String, String>,
    ) {
        var p = 4
        while (p + 8 <= data.size) {
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            val size = if (little) le32(data, p + 4) else be32(data, p + 4)
            if (size < 0 || p + 8 + size > data.size) break
            val value = text(data.copyOfRange(p + 8, p + 8 + size))
            if (value.isNotBlank()) {
                when (id) {
                    "INAM" -> out.putIfAbsent("title", value)
                    "IART" -> out.putIfAbsent("artist", value)
                    "IPRD" -> out.putIfAbsent("album", value)
                    "IGNR" -> out.putIfAbsent("genre", value)
                    "ICRD" -> out.putIfAbsent("year", value)
                    "ITRK", "IPRT" -> out.putIfAbsent("track", value)
                    "ICMT" -> out.putIfAbsent("comment", value)
                    "IENG" -> out.putIfAbsent("engineer", value)
                }
            }
            p += 8 + size + (size % 2)
        }
    }

    /**
     * INFO 规范写的是 ASCII/Latin-1，但实际文件里中文字段常见 UTF-8 甚至是 GBK。
     * 先按 UTF-8 严格解码，失败再退回 Latin-1（这样既不会把 UTF-8 中文读成乱码，
     * 也不会把纯 ASCII 弄坏）。
     */
    private fun text(data: ByteArray): String {
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        val raw = data.copyOfRange(0, end)
        if (raw.isEmpty()) return ""
        val utf8 =
            runCatching {
                val decoder =
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
            }.getOrNull()
        return (utf8 ?: String(raw, Charsets.ISO_8859_1)).trim()
    }

    private fun le32(
        b: ByteArray,
        o: Int,
    ): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun be32(
        b: ByteArray,
        o: Int,
    ): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
}
