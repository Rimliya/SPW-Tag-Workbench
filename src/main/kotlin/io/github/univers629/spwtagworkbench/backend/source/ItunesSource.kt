// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * iTunes Search API。
 *
 * 公开接口、无需密钥，能同时给出元数据与封面：
 * - 元数据：trackName / artistName / collectionName / trackTimeMillis
 * - 封面：artworkUrl100，把尺寸段换成 1200x1200 拿到大图
 *
 * 能力：META + COVER（没有歌词）。
 */
class ItunesSource : MusicSource {
    override val id: String = "itunes"

    override val label: String = "iTunes"

    override val capabilities: Set<Capability> = setOf(Capability.META, Capability.COVER)

    override val weight: Int = 20

    private val base = "https://itunes.apple.com/search"

    override suspend fun search(query: SearchQuery): SourceResult {
        val term =
            query.keyword
        if (term.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        val response =
            Http.get(
                url = base,
                query =
                    mapOf(
                        "term" to term,
                        "media" to "music",
                        "entity" to "song",
                        "limit" to "50",
                        // **不要传 country=CN**：实测 CN / US 都返回 resultCount=0，
                        // 不传则按 IP 自动选商店（实测能返回 3 条）。
                        // 该行为已在 iTunes Search API 规格中记录。
                    ),
            )
        if (!response.ok) {
            Diag.log("itunes: 搜索失败 ${response.error} status=${response.status}")
            return SourceResult(id, label, failure = response.error.ifBlank { "HTTP ${response.status}" })
        }

        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("itunes: 响应解析失败 ${t.javaClass.simpleName}")
                return SourceResult(id, label, failure = "响应格式异常")
            }

        val candidates =
            parsed.arr("results").mapNotNull { item ->
                val title = item.str("trackName") ?: return@mapNotNull null
                val artist = item.str("artistName").orEmpty()
                val album = item.str("collectionName").orEmpty()
                val durationMs = item.num("trackTimeMillis")?.toLong() ?: 0L
                val artwork = item.str("artworkUrl100").orEmpty()
                SourceCandidate(
                    sourceId = id,
                    sourceLabel = label,
                    remoteId = item.num("trackId")?.toLong()?.toString() ?: "$title|$artist",
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    hasLyrics = false,
                    hasCover = artwork.isNotBlank(),
                    artworkUrl = bigArtwork(artwork),
                    // 下面四个 iTunes 一次响应里就有，零额外请求
                    year = parseYear(item.str("releaseDate")),
                    genre = item.str("primaryGenreName").orEmpty(),
                    track = item.num("trackNumber")?.toInt() ?: 0,
                    disc = item.num("discNumber")?.toInt() ?: 0,
                    // iTunes 不给专辑艺术家：只在单艺术家时推断，多艺术家不猜
                    albumArtist =
                        if (artist.isNotBlank() && !artist.contains("&") && !artist.contains('/')) {
                            artist
                        } else {
                            ""
                        },
                )
            }
        Diag.log("itunes: 搜索「$term」返回 ${candidates.size} 条")
        return SourceResult(id, label, candidates = candidates)
    }

    /**
     * iTunes 的 `releaseDate` 是美式日期串，形如 `08/03/2004 12:00:00`。
     *
     * 顺序是 **月/日/年**，不是 ISO，不能直接取前 4 位。
     * 同时兼容 `yyyy-MM-dd` / `yyyy/MM/dd`。
     */
    private fun parseYear(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        if (text.length >= 5 && text[0].isDigit() && (text[4] == '-' || text[4] == '/')) {
            return text.substring(0, 4).toIntOrNull() ?: 0
        }
        val parts = text.substringBefore(' ').split('/')
        if (parts.size == 3) return parts[2].take(4).toIntOrNull() ?: 0
        return 0
    }

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        val url = candidate.artworkUrl
        if (url.isBlank()) return null
        val bytes = Http.getBytes(url)
        if (bytes == null) Diag.log("itunes: 封面下载失败 ${candidate.title}")
        return bytes
    }

    /** artworkUrl100 的尺寸在路径段里，换成 1200x1200 拿大图。 */
    private fun bigArtwork(url: String): String = if (url.isBlank()) "" else url.replace("100x100", "1200x1200")
}
