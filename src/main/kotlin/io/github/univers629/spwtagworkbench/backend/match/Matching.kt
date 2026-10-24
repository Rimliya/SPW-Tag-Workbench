// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.match

import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track

/**
 * 匹配与合并的纯逻辑层。
 *
 * 这里不碰文件、不碰宿主、不碰 UI —— 全部可在无图形环境下单元测试。
 */
object Matching {
    /** 伴奏 / 纯音乐关键词。命中的文件必须跳过歌词与元数据匹配。 */
    private val INSTRUMENTAL =
        listOf(
            "伴奏",
            "纯音乐",
            "卡拉ok",
            "カラオケ",
            "off vocal",
            "offvocal",
            "instrumental",
            "inst.",
            "inst ver",
            "karaoke",
            "backing track",
            "无人声",
            "无 vocal",
        )

    /** 接口返回音轨号常是默认值，不能当真实数据写回。 */
    private val DEFAULT_TRACK_NO = Regex("^0*1(?:/0*1)?$")

    /** 带括号的修饰语：需要剥离后再比较主体。 */
    private val BRACKETS = Regex("[（(【\\[].*?[）)】\\]]")

    /** 常见噪声词，去掉后主体更可比。 */
    private val NOISE =
        Regex(
            "(?i)\\b(feat|ft|featuring|remix|remixed|version|ver|edit|live|acoustic|" +
                "cover|demo|remaster|remastered|mv|hq|hd|320k|flac)\\b",
        )

    private val PUNCT = Regex("[·・.．,，、!！?？~～\\-—_/\\\\|:：;；'\"“”‘’]")

    /**
     * 接口用来表示「没有数据」的占位值，写进标签就是污染。
     *
     * 清单对齐参考项目 `TrackMatcher.PLACEHOLDER`：`0`、`0/0`、`unknown`、
     * `null`、`n/a`、`-`。其中 `0/0` 这种「音轨/碟号」形式最隐蔽——
     * 看起来像有效数字，实际上什么都没有。
     */
    private val PLACEHOLDER = Regex("(?i)^(?:0+(?:/0+)?|unknown|null|n/a|-)$")

    /** 是否为占位值（「没有数据」的伪装形式）。空串也算。 */
    fun isPlaceholder(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return true
        return PLACEHOLDER.matches(v)
    }

    /** 是不是伴奏 / 纯音乐。 */
    fun isInstrumental(vararg texts: String?): Boolean {
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val lower = text.lowercase()
            for (keyword in INSTRUMENTAL) {
                if (lower.contains(keyword)) return true
            }
        }
        return false
    }

    /** 音轨号是否为「接口默认值」。 */
    fun isDefaultTrackNo(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return DEFAULT_TRACK_NO.matches(trimmed)
    }

    /** 标题归一化：全角转半角、去括号修饰、去噪声词、去标点、压空白。 */
    fun normalizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.trim()
        text = toHalfWidth(text)
        text = BRACKETS.replace(text, " ")
        text = NOISE.replace(text, " ")
        text = PUNCT.replace(text, " ")
        text = text.lowercase()
        return text.split(' ').filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 全角 → 半角（ASCII 区间与常用标点）。 */
    fun toHalfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            when {
                code == 0x3000 -> sb.append(' ')
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 标题四级判定，返回命中的层级（越小越可信）：
     * 1 完全相同；2 归一化后相同；3 归一化后互相包含；4 相似度达阈值；0 不匹配
     */
    fun titleLevel(
        a: String?,
        b: String?,
        threshold: Double = 0.72,
    ): Int {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0
        if (a.trim().equals(b.trim(), ignoreCase = true)) return 1
        val na = normalizeTitle(a)
        val nb = normalizeTitle(b)
        if (na.isEmpty() || nb.isEmpty()) return 0
        if (na == nb) return 2
        if (na.contains(nb) || nb.contains(na)) {
            // 短的一方至少要有 2 个字符，避免 "a" 命中一切
            val shorter = minOf(na.length, nb.length)
            if (shorter >= 2) return 3
        }
        return if (similarity(na, nb) >= threshold) 4 else 0
    }

    /** 归一化后的相似度（0..1），基于编辑距离。 */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return 1.0 - distance.toDouble() / longest
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] =
                    minOf(
                        cur[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost,
                    )
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /** 时长是否在容差内。任一方未知（<=0）时视为通过，交给其它条件判断。 */
    fun durationMatches(
        aMs: Long,
        bMs: Long,
        toleranceMs: Long = 3000L,
    ): Boolean {
        if (aMs <= 0L || bMs <= 0L) return true
        return kotlin.math.abs(aMs - bMs) <= toleranceMs
    }

    /**
     * 曲目能否作为匹配候选。
     *
     * 伴奏文件直接排除：否则演唱版歌词会被写进伴奏。
     */
    fun isMatchCandidate(track: Track): Boolean {
        if (!track.hasFile) return false
        if (isInstrumental(track.title, track.album, track.path.substringAfterLast('\\'))) return false
        return true
    }

    /**
     * 按字段合并。
     *
     * 规则：**只补空，不清空**。某个源缺封面/缺专辑时不能把已有值擦掉，
     * 这是「多源合并按字段取」而不是「整条替换」的落地。
     */
    fun mergeByField(
        base: Track,
        incoming: TagPatch,
    ): TagPatch {
        val merged = TagPatch()
        for ((field, value) in incoming.entries) {
            if (value.isBlank()) continue
            val current = currentValue(base, field)
            if (current.isBlank()) {
                merged.set(field, value)
            }
        }
        return merged
    }

    private fun currentValue(
        track: Track,
        field: TagField,
    ): String =
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

    /**
     * 从候选补丁里剔掉「接口默认值」字段（典型是音轨号 1/01/1/1）。
     */
    fun dropDefaultFields(patch: TagPatch): TagPatch {
        val cleaned = TagPatch()
        for ((field, value) in patch.entries) {
            if (field == TagField.Track && isDefaultTrackNo(value)) continue
            cleaned.set(field, value)
        }
        return cleaned
    }
}
