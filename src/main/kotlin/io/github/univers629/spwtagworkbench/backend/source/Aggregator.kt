// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.match.Matching
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 排序后的候选：带匹配等级、合并了哪些源、以及每个源的原始 ID。 */
data class RankedCandidate(
    val candidate: SourceCandidate,
    val level: Int,
    /** 同一首歌被多个源命中时，这里列出全部源 */
    val allSources: List<String>,
    /** 源 → 源内 ID（封面可能从 A 取、歌词从 B 取） */
    val idsBySource: Map<String, String>,
) {
    val needsConfirm: Boolean get() = level >= 3
}

/** 聚合结果：候选 + 失败源 + 进度统计。 */
data class AggregateResult(
    val candidates: List<RankedCandidate> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
    val responded: Int = 0,
    val queried: Int = 0,
) {
    val hasFailure: Boolean get() = failures.isNotEmpty()
}

/**
 * 多源聚合。
 *
 * 顺序固定：按能力筛选 → 并发请求（单源超时不等待）→ 计算匹配等级 →
 * 去重（归一化标题相同 + 艺术家相似 + 时长差 < 3s）→ 排序（等级 > 源权重 > 顺序）→ 按字段合并。
 */
class Aggregator(
    private val sources: List<MusicSource>,
) {
    /**
     * 可用源：同时满足两个条件
     * 1. 声明了目标能力（能力声明必须被尊重）
     * 2. 没有被用户在「标签源」里关掉
     *
     * 第 2 条以前漏了——`isSourceEnabled` 只在界面里被读，backend 从没消费过，
     * 于是关掉源只改了勾选状态、搜索照打。
     */
    private fun usableSources(capability: Capability): List<MusicSource> =
        sources.filter {
            it.supports(capability) &&
                io.github.univers629.spwtagworkbench.core.Settings
                    .isSourceEnabled(it.id)
        }

    /** 单源超时：超时算失败，不阻塞其它源。 */
    private val searchTimeoutMs = 9000L

    /**
     * 搜索。
     *
     * @param capability 只请求声明了该能力的源（能力声明必须被尊重）
     * @param perSourceTimeoutMs 单源超时；超时只影响该源，不阻塞整体
     */
    suspend fun search(
        query: SearchQuery,
        capability: Capability,
        perSourceTimeoutMs: Long = 9000,
    ): AggregateResult {
        val usable = usableSources(capability)
        if (usable.isEmpty()) {
            return AggregateResult(queried = 0)
        }

        // 已有「足够好」的候选时不再发起剩余请求。
        //
        // 注意收益的**真实边界**：所有 `async` 是一次性创建的，协程调度顺序不确定，
        // 所以这里只能省掉「还没被调度到」的那些源的请求，省不掉已经在飞的请求。
        // 真正的价值在于：`awaitAll` 仍会等所有协程结束，但被跳过的那几个是立即返回的，
        // 因此不会为「已经确定不需要」的源多等一个 9 秒超时。
        //
        // 不做「取消在飞请求」的原因：取消会让那个源记为失败，
        // 在批量日志里制造假失败，反而更难排查。让它自然完成、结果照常参与 rank 更稳。
        val excellent =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        val results: List<SourceResult> =
            coroutineScope {
                usable
                    .map { source ->
                        async {
                            // 已经有好结果就不再发起新请求（能省掉的请求才是真省）
                            if (excellent.get()) {
                                return@async SourceResult(source.id, source.label, failure = "")
                            }
                            val result =
                                withTimeoutOrNull(perSourceTimeoutMs) {
                                    try {
                                        source.search(query)
                                    } catch (t: Throwable) {
                                        SourceResult(source.id, source.label, failure = t.javaClass.simpleName)
                                    }
                                } ?: SourceResult(source.id, source.label, failure = "请求超时")
                            // 拿到结果立刻判定：够好就置位，让尚未开始的源直接跳过
                            if (hasExcellent(query, result)) excellent.set(true)
                            result
                        }
                    }.awaitAll()
            }

        val failures = results.filter { !it.ok }.map { it.sourceLabel to it.failure }
        failures.forEach { (label, reason) -> Diag.log("aggregate fail: $label -> $reason") }
        val responded = results.count { it.ok }
        val all = results.flatMap { it.candidates }
        val ranked = rank(query, all, weightOf = { id -> sources.firstOrNull { it.id == id }?.weight ?: 0 })
        Diag.log(
            "aggregate: cap=$capability queried=${usable.size} responded=$responded " +
                "candidates=${all.size} ranked=${ranked.size} failures=${failures.size}" +
                if (excellent.get()) "（已优秀，提前收工）" else "",
        )
        // 把全局并发快照记下来：排查限流时能直接看到实际峰值有没有超过 8
        io.github.univers629.spwtagworkbench.backend.net.NetGate
            .logSnapshot("aggregate:")
        return AggregateResult(
            candidates = ranked,
            failures = failures,
            responded = responded,
            queried = usable.size,
        )
    }

    /**
     * 某个源的返回里是否已有「优秀到不必再看其它源」的候选。
     *
     * 判据与 `MatchRules.isExcellent` 一致：等级 1/2（标题确定）+ 时长差 ≤2.5s。
     * 这里内联一份是为了不让 backend 反向依赖 data 层。
     */
    private fun hasExcellent(
        query: SearchQuery,
        result: SourceResult,
    ): Boolean {
        if (!result.ok) return false
        for (candidate in result.candidates) {
            val level = CandidateScoring.level(query, candidate)
            if (level == 0 || level > 2) continue
            if (query.durationMs <= 0L || candidate.durationMs <= 0L) return true
            if (kotlin.math.abs(query.durationMs - candidate.durationMs) <= 2_500L) return true
        }
        return false
    }

    /**
     * 按源分组搜索：每个源单独一组，组内按匹配等级、时长接近度排序。
     *
     * 与 [search] 的区别：不做跨源去重与合并，也不丢弃弱匹配——
     * 歌词匹配页要的是「各个源分别罗列」，让用户自己挑。
     */
    suspend fun searchGrouped(
        query: SearchQuery,
        capability: Capability,
    ): List<SourceGroup> {
        val groups = java.util.Collections.synchronizedList(mutableListOf<SourceGroup>())
        streamGrouped(query, capability) { group ->
            groups += group
        }
        return groups.toList().sortedWith(compareByDescending<SourceGroup> { it.weight })
    }

    /**
     * 流式版按源搜索：**每个源一返回就回调一次**，不等其它源。
     *
     * 这是排版图页 / 歌词页「先出的先看」的关键：旧版 [searchGrouped] 用 `awaitAll()`，
     * 用户要等最慢那个源才能看到任何候选；这里谁先回谁先上屏。
     *
     * @param onGroup 每个源完成时回调，可能在任意线程调用，调用方需自行切主线程
     */
    suspend fun streamGrouped(
        query: SearchQuery,
        capability: Capability,
        onGroup: suspend (SourceGroup) -> Unit,
    ) {
        val usable = usableSources(capability)
        Diag.log("grouped-stream: cap=$capability sources=${usable.joinToString(",") { it.label }}")
        if (usable.isEmpty()) return

        coroutineScope {
            usable.forEach { source ->
                launch(Dispatchers.IO) {
                    val outcome =
                        try {
                            withTimeoutOrNull(searchTimeoutMs) { source.search(query) }
                                ?: SourceResult(source.id, source.label, failure = "超时")
                        } catch (t: Throwable) {
                            SourceResult(
                                source.id,
                                source.label,
                                failure =
                                    t.javaClass.simpleName + ": " + (t.message ?: "").take(40),
                            )
                        }
                    val items =
                        outcome.candidates
                            .map { candidate ->
                                GroupedCandidate(
                                    candidate = candidate,
                                    level = CandidateScoring.level(query, candidate),
                                )
                            }.sortedWith(
                                compareByDescending<GroupedCandidate> { it.level }
                                    .thenBy { kotlin.math.abs(it.candidate.durationMs - query.durationMs) },
                            )
                    Diag.log(
                        "grouped-stream: ${source.label} -> ${items.size} 条" +
                            if (outcome.failure.isNotBlank()) "（失败：${outcome.failure}）" else "",
                    )
                    onGroup(
                        SourceGroup(
                            sourceId = source.id,
                            label = source.label,
                            weight = source.weight,
                            items = truncate(items),
                            failure = outcome.failure.ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    /** 参与**专辑封面**检索的源（不含只有艺术家图的源）。 */
    fun coverSources(): List<MusicSource> = usableSources(Capability.COVER)

    /**
     * 按源 id 找源实例。
     *
     * 批量匹配拿到候选后要回它自己的源取歌词/封面，
     * 而 `sources` 是私有的，这里开一个最小入口。
     */
    fun sourceById(id: String): MusicSource? = sources.firstOrNull { it.id == id }

    /** 参与**艺术家图**检索的源。与 [coverSources] 是两套端点，不能混用。 */
    fun artistSources(): List<MusicSource> = usableSources(Capability.ARTIST_COVER)

    /** 参与歌词检索的源（界面显示「支持的源」用）。 */
    fun lyricSources(): List<MusicSource> = usableSources(Capability.LYRICAL)

    /** 参与元数据匹配的源。 */
    fun metaSources(): List<MusicSource> = usableSources(Capability.META)

    /**
     * 每源最多保留多少条候选。
     *
     * 各源接口配额已调到 30~50 条，但界面每源只列 5 条就够挑了。
     * 在这里截断可以少建对象、少排一次序，界面侧也就不必再管上限。
     */
    private fun truncate(items: List<GroupedCandidate>): List<GroupedCandidate> =
        if (items.size <= MAX_PER_SOURCE) items else items.subList(0, MAX_PER_SOURCE)

    /**
     * 单源候选的排序依据。
     *
     * 截断到 [MAX_PER_SOURCE] 是**按排序结果取前几条**，所以排序键决定了
     * 谁会活下来。封面看标题（要有同一首歌的封面），艺术家图要看艺人
     * （艺人的图对他任何一首歌都一样），两者不能共用一个键。
     */
    enum class GroupRank {
        /** 按标题相关性 —— 封面、歌词、元数据用。 */
        TITLE,

        /** 艺术家兼容优先，其次才是标题相关性 —— 艺术家图页用。 */
        ARTIST,
    }

    /** 单源搜索（封面流式载入用）。 */
    suspend fun groupFor(
        source: MusicSource,
        query: SearchQuery,
        rank: GroupRank = GroupRank.TITLE,
    ): SourceGroup? {
        val result = withTimeoutOrNull(searchTimeoutMs) { source.search(query) }
        val items =
            result
                ?.candidates
                ?.map { candidate ->
                    GroupedCandidate(candidate = candidate, level = CandidateScoring.level(query, candidate))
                }.orEmpty()
                .sortedWith(
                    when (rank) {
                        GroupRank.TITLE -> compareByDescending<GroupedCandidate> { it.level }

                        // 否则「正确艺人的其他歌曲」会被标题更匹配的别人挤掉：
                        // 标题同名的翻唱排在前面，截断到 5 条后真艺人的歌被挤掉。
                        GroupRank.ARTIST ->
                            compareByDescending<GroupedCandidate> {
                                if (CandidateScoring.artistCompatible(query.artist, it.candidate.artist)) 1 else 0
                            }.thenByDescending { it.level }
                    },
                )
        return SourceGroup(
            sourceId = source.id,
            label = source.label,
            weight = source.weight,
            items = truncate(items),
            failure = result?.failure?.ifBlank { null },
        )
    }

    companion object {
        /** 每源最多保留几条候选（界面每源只列 5 条，多留只是拖慢排序）。 */
        const val MAX_PER_SOURCE = 5

        /**
         * 计算等级、去重、排序。纯逻辑，可单测。
         */
        fun rank(
            query: SearchQuery,
            candidates: List<SourceCandidate>,
            weightOf: (sourceId: String) -> Int = { 0 },
        ): List<RankedCandidate> {
            val groups = mutableListOf<MutableGroup>()

            for (candidate in candidates) {
                val level = CandidateScoring.level(query, candidate)
                if (level == 0) continue // 不匹配的直接丢弃，不占列表
                val existing = groups.firstOrNull { it.matches(candidate) }
                if (existing == null) {
                    groups +=
                        MutableGroup(
                            candidate = candidate,
                            level = level,
                            sources = mutableListOf(candidate.sourceId),
                            ids = mutableMapOf(candidate.sourceId to candidate.remoteId),
                            weight = weightOf(candidate.sourceId),
                        )
                } else {
                    existing.absorb(candidate, level, weightOf(candidate.sourceId))
                }
            }

            return groups
                .sortedWith(
                    compareBy<MutableGroup> { it.level } // 1 = 精确，排最前
                        .thenByDescending { it.weight } // 同等级看源权重
                        .thenByDescending { it.candidate.hasLyrics } // 再优先有歌词的
                        .thenByDescending { it.candidate.hasCover },
                ).map { group ->
                    RankedCandidate(
                        candidate = group.candidate,
                        level = group.level,
                        allSources = group.sources.toList(),
                        idsBySource = group.ids.toMap(),
                    )
                }
        }

        /**
         * 按字段合并：多源命中同一首歌时，逐字段取第一个非空值。
         * 某个源缺专辑不会把别的源给的专辑擦掉。
         */
        fun mergeFields(ranked: List<RankedCandidate>): TagPatch {
            val patch = TagPatch()
            for (field in listOf(TagField.Title, TagField.Artist, TagField.Album)) {
                if (patch.has(field)) continue
                for (item in ranked) {
                    val value =
                        when (field) {
                            TagField.Title -> item.candidate.title
                            TagField.Artist -> item.candidate.artist
                            TagField.Album -> item.candidate.album
                            else -> ""
                        }
                    if (value.isNotBlank()) {
                        patch.set(field, value)
                        break
                    }
                }
            }
            return patch
        }

        private class MutableGroup(
            var candidate: SourceCandidate,
            var level: Int,
            val sources: MutableList<String>,
            val ids: MutableMap<String, String>,
            var weight: Int,
        ) {
            fun matches(other: SourceCandidate): Boolean {
                if (Matching.normalizeTitle(candidate.title) != Matching.normalizeTitle(other.title)) return false
                if (!CandidateScoring.artistCompatible(candidate.artist, other.artist)) return false
                return Matching.durationMatches(candidate.durationMs, other.durationMs)
            }

            fun absorb(
                other: SourceCandidate,
                otherLevel: Int,
                otherWeight: Int,
            ) {
                if (other.sourceId !in sources) sources += other.sourceId
                ids[other.sourceId] = other.remoteId
                // 等级取更可信的；同等级时取信息更全的
                if (otherLevel < level) {
                    level = otherLevel
                    candidate = other
                } else if (otherLevel == level && informationScore(other) > informationScore(candidate)) {
                    candidate = other
                }
                if (otherWeight > weight) weight = otherWeight
            }

            private fun informationScore(c: SourceCandidate): Int =
                (if (c.album.isNotBlank()) 2 else 0) +
                    (if (c.hasLyrics) 2 else 0) +
                    (if (c.hasCover) 1 else 0) +
                    (if (c.durationMs > 0) 1 else 0)
        }
    }
}

/**
 * 按源分组的结果（歌词匹配页用）。
 *
 * 参考项目的行为：每个源独立罗列，不跨源合并，失败也按源显示原因。
 */
data class SourceGroup(
    val sourceId: String,
    val label: String,
    val weight: Int,
    val items: List<GroupedCandidate>,
    val failure: String? = null,
)

/** 一个源里的一条候选，带匹配等级。 */
data class GroupedCandidate(
    val candidate: SourceCandidate,
    val level: Int,
    val sources: List<String> = listOf(candidate.sourceId),
)
