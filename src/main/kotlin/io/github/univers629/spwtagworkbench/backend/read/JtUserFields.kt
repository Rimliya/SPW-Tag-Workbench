// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.read

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag

/**
 * jaudiotagger 标签里「自由键」的统一读取。
 *
 * ReplayGain、MusicBrainz ID、iTunNORM 这类数据不属于任何固定字段，
 * 各体系存法不同，**取法也不同**：
 *
 * | 体系 | 怎么取 |
 * | --- | --- |
 * | ID3v2 | TXXX 在 jaudiotagger 里挂在框架 ID `"TXXX"` 下，**按描述符查不到**， 必须遍历帧比对 `FrameBodyTXXX.description` |
 * | Vorbis comment（FLAC / OGG） | `getFirst(key)` 直接按键名取 |
 * | 其它（MP4 / WAV / ASF） | 没有自由键入口，取不到 |
 *
 * 读写两侧都走这里，避免同一套取法在两处各写一遍。
 */
internal object JtUserFields {
    /** 取一个自由键的值；取不到返回 null。键名忽略大小写。 */
    fun get(
        tag: Tag?,
        key: String,
    ): String? {
        if (tag == null) return null
        return when (tag) {
            is AbstractID3v2Tag -> fromId3(tag, key)
            is FlacTag -> tag.vorbisCommentTag.getFirst(key)?.takeIf { it.isNotBlank() }
            is VorbisCommentTag -> tag.getFirst(key)?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** ID3v2 的 TXXX 要逐个帧比对描述符。 */
    private fun fromId3(
        tag: AbstractID3v2Tag,
        key: String,
    ): String? {
        val frames = tag.fields
        while (frames.hasNext()) {
            val body = (frames.next() as? AbstractID3v2Frame)?.body
            if (body is FrameBodyTXXX && body.description.equals(key, ignoreCase = true)) {
                return body.text?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** 该标签体系是否支持自由键（不支持时调用方应跳过而不是假装成功）。 */
    fun supports(tag: Tag?): Boolean = tag is AbstractID3v2Tag || tag is FlacTag || tag is VorbisCommentTag
}
