// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.write

import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import java.io.File
import java.io.RandomAccessFile

/**
 * ID3v1 / ID3v1.1 尾部标签的同步与清理。
 *
 * 很多文件同时存在 ID3v2 与 ID3v1：只写 ID3v2 会留下旧的 ID3v1，
 * 而部分播放器优先读 ID3v1，结果是改完仍显示旧值。
 * 这里在写完 ID3v2 后把 ID3v1 同步成同一份值；若同步后所有字段都为空，
 * 直接把 128 字节尾巴删掉，避免留下一条空标签。
 *
 * ID3v1 只能存 Latin-1，无法表示的字符按标准做法写成 `?`。
 */
object Id3v1 {
    const val SIZE = 128
    private const val MAGIC = "TAG"

    class Tag(
        val title: String,
        val artist: String,
        val album: String,
        val year: String,
        val comment: String,
        val track: Int,
        val genre: Int,
    )

    fun read(file: File): Tag? {
        if (file.length() < SIZE) return null
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - SIZE)
            val buf = ByteArray(SIZE)
            if (raf.read(buf, 0, SIZE) != SIZE) return null
            if (String(buf, 0, 3, Charsets.ISO_8859_1) != MAGIC) return null
            Tag(
                title = text(buf, 3, 30),
                artist = text(buf, 33, 30),
                album = text(buf, 63, 30),
                year = text(buf, 93, 4),
                comment = text(buf, 97, 28),
                track = buf[125].toInt() and 0xFF,
                genre = buf[127].toInt() and 0xFF,
            )
        }
    }

    /**
     * 按补丁同步已有的 ID3v1。
     *
     * @return true 表示改动了文件（含删除空标签的情况）
     */
    fun sync(
        file: File,
        patch: TagPatch,
    ): Boolean {
        val existing = read(file) ?: return false

        fun pick(
            field: TagField,
            fallback: String,
        ): String = if (patch.has(field)) patch.value(field).orEmpty() else fallback

        val title = pick(TagField.Title, existing.title)
        val artist = pick(TagField.Artist, existing.artist)
        val album = pick(TagField.Album, existing.album)
        val year = pick(TagField.Year, existing.year)
        val comment = pick(TagField.Comment, existing.comment)
        val trackText = if (patch.has(TagField.Track)) patch.value(TagField.Track).orEmpty() else null
        val track = trackText?.let { leadingInt(it) } ?: existing.track

        val allEmpty =
            title.isBlank() && artist.isBlank() && album.isBlank() && year.isBlank() && comment.isBlank()
        if (allEmpty) {
            RandomAccessFile(file, "rw").use { it.setLength(it.length() - SIZE) }
            return true
        }

        val buf = ByteArray(SIZE)
        MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(buf, 0)
        latin1(title, 30).copyInto(buf, 3)
        latin1(artist, 30).copyInto(buf, 33)
        latin1(album, 30).copyInto(buf, 63)
        latin1(year, 4).copyInto(buf, 93)
        latin1(comment, 28).copyInto(buf, 97)
        buf[125] = 0
        buf[126] = (track and 0xFF).toByte()
        buf[127] = (existing.genre and 0xFF).toByte()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length() - SIZE)
            raf.write(buf)
        }
        return true
    }

    private fun text(
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        var end = offset
        while (end < offset + length && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.ISO_8859_1).trim()
    }

    private fun latin1(
        value: String,
        length: Int,
    ): ByteArray {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(length)
        bytes.copyInto(out, 0, 0, minOf(bytes.size, length))
        return out
    }

    private fun leadingInt(value: String): Int = value.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}
