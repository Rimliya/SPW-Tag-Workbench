// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * LRCLIB：公开歌词库，无需密钥。
 *
 * 能力：歌词 + 元数据（标题/艺术家/专辑/时长）。**不提供封面**，
 * 所以它绝不会出现在封面候选里 —— 这就是能力声明的作用。
 *
 * 接口：
 * - `GET /api/search?q=...` 或 `?track_name=&artist_name=&album_name=`
 * - `GET /api/get?track_name=&artist_name=&album_name=&duration=`
 */
class LrclibSource : MusicSource {
    override val id: String = "lrclib"

    override val label: String = "LRCLIB"

    override val capabilities: Set<Capability> = setOf(Capability.LYRICAL, Capability.META)

    override val weight: Int = 10

    private val base = "https://lrclib.net/api"

    override suspend fun search(query: SearchQuery): SourceResult {
        if (query.title.isBlank()) {
            return SourceResult(id, label, failure = "标题为空")
        }
        val response =
            Http.get(
                url = "$base/search",
                query =
                    buildMap {
                        put("track_name", query.title)
                        if (query.artist.isNotBlank()) put("artist_name", query.artist)
                        if (query.album.isNotBlank()) put("album_name", query.album)
                    },
            )
        if (!response.ok) {
            Diag.log("lrclib: 搜索失败 ${response.error} status=${response.status}")
            return SourceResult(id, label, failure = response.error.ifBlank { "HTTP ${response.status}" })
        }

        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("lrclib: 响应解析失败 ${t.javaClass.simpleName}")
                return SourceResult(id, label, failure = "响应格式异常")
            }

        val items =
            when (parsed) {
                is Json.Arr -> parsed.items
                is Json.Obj -> listOf(parsed)
                else -> emptyList()
            }
        if (items.isEmpty()) {
            return SourceResult(id, label, candidates = emptyList())
        }

        val candidates =
            items.mapNotNull { item ->
                val title = item.str("trackName") ?: return@mapNotNull null
                val artist = item.str("artistName").orEmpty()
                val album = item.str("albumName").orEmpty()
                val durationSec = item.num("duration") ?: 0.0
                val instrumental = item.bool("instrumental") == true
                if (instrumental) return@mapNotNull null
                val hasSynced = !item.str("syncedLyrics").isNullOrBlank()
                val hasPlain = !item.str("plainLyrics").isNullOrBlank()
                val remoteId = item.num("id")?.toLong()?.toString() ?: "$title|$artist"
                SourceCandidate(
                    sourceId = id,
                    sourceLabel = label,
                    remoteId = remoteId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (durationSec * 1000).toLong(),
                    hasLyrics = hasSynced || hasPlain,
                    hasCover = false,
                    lyricFormat =
                        if (hasSynced) {
                            "lrc"
                        } else if (hasPlain) {
                            "text"
                        } else {
                            ""
                        },
                )
            }
        Diag.log("lrclib: 搜索「${query.title}」返回 ${candidates.size} 条")
        return SourceResult(id, label, candidates = candidates)
    }

    override suspend fun lyrics(candidate: SourceCandidate): String? {
        coroutineContext.ensureActive()
        val response =
            Http.get(
                url = "$base/get",
                query =
                    buildMap {
                        put("track_name", candidate.title)
                        if (candidate.artist.isNotBlank()) put("artist_name", candidate.artist)
                        if (candidate.album.isNotBlank()) put("album_name", candidate.album)
                        if (candidate.durationMs > 0) put("duration", (candidate.durationMs / 1000).toString())
                    },
            )
        if (!response.ok) {
            Diag.log("lrclib: 取歌词失败 ${response.error}")
            return null
        }
        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("lrclib: 歌词响应解析失败 ${t.javaClass.simpleName}")
                return null
            }
        val synced = parsed.str("syncedLyrics")
        val plain = parsed.str("plainLyrics")
        return synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
    }
}
