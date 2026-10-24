// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import io.github.univers629.spwtagworkbench.core.Settings

/**
 * 批量匹配时「本地已有标签」的处理策略。
 *
 * 只有两态：要么完整标签也用在线数据覆盖（[REFILL_ALL]），要么只补缺失（[FILL_MISSING]，默认）。
 */
enum class MatchingPolicy(
    val key: String,
    val label: String,
) {
    /** 全部重新匹配：完整标签也覆盖，用在线数据统一化。 */
    REFILL_ALL("refill", "全部重新匹配"),

    /** 仅补缺失（默认）：完整标签整首跳过，不完整才请求。 */
    FILL_MISSING("fill", "仅补缺失"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        /**
         * 未知取值回落到 [FILL_MISSING]。
         *
         * 旧配置里已下线的 `skip` 也走这条分支：它当年的行为与 [FILL_MISSING]
         * 完全一致（都只跳过标签完整的歌），所以不会因为删掉选项改变匹配结果。
         */
        fun of(key: String?): MatchingPolicy = byKey[key] ?: FILL_MISSING
    }
}

/**
 * 匹配策略判定。
 *
 * 「完整」的判定与参考项目 `TrackMatcher.hasCompleteCoreTags` 对齐：
 * 标题、艺术家、专辑三者不为空，且文件里已有封面、已有歌词。
 *
 * 后两项没法从 [TrackMeta] 直接看出（它不含歌词与图片字节），
 * 所以由调用方先探一次文件再传进来，避免这里重复做磁盘 IO。
 */
object MatchRules {
    /** 当前生效的策略。 */
    val current: MatchingPolicy get() = MatchingPolicy.of(Settings.matchingPolicy)

    /**
     * 这首歌是否应当在批量匹配时整首跳过。
     *
     * @param hasLyrics 文件里是否已有歌词
     * @param hasCover 文件里是否已有封面
     */
    fun shouldSkipComplete(
        meta: TrackMeta,
        hasLyrics: Boolean,
        hasCover: Boolean,
    ): Boolean {
        if (current == MatchingPolicy.REFILL_ALL) return false
        return hasCompleteCoreTags(meta, hasLyrics, hasCover)
    }

    /** 标题、艺术家、专辑、歌词、封面都不为空才算「完整」。 */
    fun hasCompleteCoreTags(
        meta: TrackMeta,
        hasLyrics: Boolean,
        hasCover: Boolean,
    ): Boolean =
        meta.title.isNotBlank() &&
            meta.artist.isNotBlank() &&
            meta.album.isNotBlank() &&
            hasLyrics &&
            hasCover

    /**
     * 候选是否「足够好，可以立刻采用」——命中后不再继续翻其它候选。
     *
     * 对齐参考项目 `isExcellent` 的思路：时长是最可靠的同曲判据，
     * 差 2.5 秒以内基本可以确认是同一录音（不同版本通常差几十秒）。
     *
     * 但这里**要求等级也够高**：等级 1/2 是标题完全相同或归一后相同。
     * 只看时长不看标题的话，同一张专辑里时长相近的两首歌会被搞混。
     *
     * @param level [CandidateScoring.level] 的结果（1 最可信，0 不匹配）
     */
    fun isExcellent(
        level: Int,
        localDurationMs: Long,
        remoteDurationMs: Long,
    ): Boolean {
        if (level == 0) return false
        // 等级 1/2 = 标题基本确定，再加时长吻合就是铁证
        if (level <= 2) {
            if (localDurationMs <= 0L || remoteDurationMs <= 0L) return true
            return kotlin.math.abs(localDurationMs - remoteDurationMs) <= 2_500L
        }
        return false
    }
}
