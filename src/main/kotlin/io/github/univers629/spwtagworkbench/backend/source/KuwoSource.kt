// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * 酷我音乐（公开检索接口）。
 *
 * 能力：歌词 + 元数据 + 封面。一个源把三件事全做了，性价比最高。
 *
 * 两个关键点：
 * - **封面尺寸可改写**：接口给的 `pic` 是 `.../albumcover/240/...`，把 `240` 段换成
 *   `1200` 就是 1200×1200 原图（实测 240/500/700/1200 四档都在线）。
 *   服务端支持任意尺寸，接口默认只给缩略图。
 * - **限频**：连续快打会随机返回 `status=301 / msg=音乐查询失败`，
 *   等 ~2.5s 重试即恢复。
 */
class KuwoSource : MusicSource {
    override val id: String = "kuwo"

    override val label: String = "酷我音乐"

    override val capabilities: Set<Capability> =
        setOf(Capability.LYRICAL, Capability.META, Capability.COVER)

    override val weight: Int = 20

    /**
     * 专辑图床。
     *
     * **只能用 http**：https 实测全部连接失败。
     * 后面接的是去掉尺寸段之后的相对路径。
     */
    private val ALBUM_COVER_HOST = "http://img1.kwcdn.kuwo.cn/star/albumcover/1200/"

    private val headers =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://kuwo.cn",
        )

    override suspend fun search(query: SearchQuery): SourceResult {
        val keyword =
            query.keyword
        if (keyword.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        val response =
            Http.get(
                url = "https://search.kuwo.cn/r.s",
                query =
                    mapOf(
                        "all" to keyword,
                        "client" to "kt",
                        "pn" to "0",
                        "rn" to "30",
                        "ver" to "kwplayer_ar_9.2.3.2",
                        "vipver" to "1",
                        "show_copyright_off" to "1",
                        "newver" to "1",
                        "correct" to "1",
                        "ft" to "music",
                        "cluster" to "0",
                        "strategy" to "2012",
                        "encoding" to "utf8",
                        "rformat" to "json",
                        "vermerge" to "1",
                        "mobi" to "1",
                        "issubtitle" to "1",
                    ),
                headers = headers,
                timeoutMs = 8000,
            )
        if (!response.ok) {
            Diag.log("kuwo: 搜索失败 ${response.error} status=${response.status}")
            return SourceResult(id, label, failure = response.error.ifBlank { "HTTP ${response.status}" })
        }
        // 酷我返回的 JSON 里字符串偶尔带转义的引号，先规整再解析
        val text = response.body.trim()
        val parsed =
            try {
                Json.parse(text)
            } catch (t: Throwable) {
                Diag.log("kuwo: 响应解析失败 ${t.javaClass.simpleName} len=${text.length}")
                return SourceResult(id, label, failure = "响应格式异常")
            }
        val list = parsed.arr("abslist") ?: emptyList()
        val candidates =
            list.mapNotNull { song ->
                val title =
                    song
                        .str("SONGNAME")
                        ?.unescapeKuwo()
                        ?.trim()
                        .orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val musicId = musicId(song)
                if (musicId.isBlank()) return@mapNotNull null
                val artist =
                    song
                        .str("ARTIST")
                        ?.unescapeKuwo()
                        ?.trim()
                        .orEmpty()
                val album =
                    song
                        .str("ALBUM")
                        ?.unescapeKuwo()
                        ?.trim()
                        .orEmpty()
                val seconds = song.str("DURATION")?.toLongOrNull() ?: 0L
                val albumArt = albumArtUrl(song.str("web_albumpic_short"))
                SourceCandidate(
                    sourceId = id,
                    sourceLabel = label,
                    remoteId = musicId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = seconds * 1000L,
                    hasLyrics = true,
                    hasCover = albumArt.isNotBlank(),
                    lyricFormat = "lrc",
                    artworkUrl = albumArt,
                )
            }
        Diag.log(
            "kuwo: 搜索「$keyword」返回 ${candidates.size} 条，" +
                "带封面 ${candidates.count { it.hasCover }} 条",
        )
        return SourceResult(id, label, candidates = candidates)
    }

    /**
     * 从搜索响应直接拼专辑封面地址，**不走详情接口**。
     *
     * `web_albumpic_short` 是相对路径（如 `120/75/9/3640622973.jpg`），
     * 拼上图床前缀、把第一段尺寸换成 1200 就是大图：
     * `http://img1.kwcdn.kuwo.cn/star/albumcover/1200/75/9/3640622973.jpg`
     * 实测 **1200×1200 / 687KB**。
     *
     * 为什么必须绕开详情接口：`songinfoandlrc` 限频极重，连续请求直接
     * `status=301 音乐查询失败`，一旦被限，酷我在封面页就永远出不了图。
     *
     * 注意图床**不支持 https**（实测全部失败），只能用 http。
     */
    private fun albumArtUrl(short: String?): String {
        val rel = short?.trim().orEmpty()
        if (rel.isEmpty()) return ""
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        val tail = rel.replaceFirst(Regex("^\\d+/"), "")
        return "$ALBUM_COVER_HOST$tail"
    }

    /** `DC_TARGETID` 是纯数字；`MUSICRID` 形如 `MUSIC_588552`，要剥前缀。 */
    private fun musicId(song: Json): String {
        val direct = song.str("DC_TARGETID")?.trim().orEmpty()
        if (direct.isNotBlank() && direct.all { it.isDigit() }) return direct
        return song
            .str("MUSICRID")
            ?.replace("MUSIC_", "")
            ?.trim()
            .orEmpty()
    }

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        // 优先用搜索响应里拼好的 1200px 地址 —— 完全不碰限频的详情接口
        val direct = candidate.artworkUrl
        if (direct.isNotBlank()) {
            val bytes = Http.getBytes(direct, timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
            Diag.log("kuwo: 直链失败，退回详情接口 ${direct.takeLast(40)}")
        }
        // 兜底才问详情接口（会被限频，所以只试一次）
        val pic = picUrl(candidate.remoteId) ?: return null
        val urls =
            listOf(
                pic.replace(Regex("/albumcover/\\d+/"), "/albumcover/1200/"),
                pic.replace(Regex("/albumcover/\\d+/"), "/albumcover/500/"),
                pic,
            ).distinct()
        for (url in urls) {
            val bytes = Http.getBytes(url.replace("https:", "http:"), timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
        }
        Diag.log("kuwo: 取封面失败（试了 ${urls.size} 个尺寸）")
        return null
    }

    override suspend fun lyrics(candidate: SourceCandidate): String? {
        val data = songInfo(candidate.remoteId) ?: return null
        val lrcList = data.arr("lrclist") ?: return null
        if (lrcList.isEmpty()) return null
        // [{lineLyric, time}] → 标准 LRC 文本，交给统一的 lyrics 解析器
        val sb = StringBuilder()
        for (item in lrcList) {
            val text =
                item
                    .str("lineLyric")
                    ?.unescapeKuwo()
                    ?.trim()
                    .orEmpty()
            if (text.isEmpty()) continue
            val ms =
                (item.num("time") ?: 0.0)
                    .let { (it * 1000.0).toLong() }
                    .coerceAtLeast(0L)
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            val cs = (ms % 1000) / 10
            sb
                .append('[')
                .append(min.toString().padStart(2, '0'))
                .append(':')
                .append(sec.toString().padStart(2, '0'))
                .append('.')
                .append(cs.toString().padStart(2, '0'))
                .append(']')
                .append(text)
                .append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 详情接口（歌词 + 元数据 + 封面同一个）。
     *
     * 限频时 `status=301`，等 2.5s 重试一次。
     */
    private suspend fun songInfo(
        musicId: String,
        retry: Boolean = true,
    ): Json? {
        if (musicId.isBlank()) return null
        val response =
            Http.get(
                url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc",
                query = mapOf("musicId" to musicId),
                headers = headers,
                timeoutMs = 8000,
            )
        if (!response.ok) {
            Diag.log("kuwo: 详情失败 ${response.error}")
            return null
        }
        val parsed =
            try {
                Json.parse(response.body)
            } catch (t: Throwable) {
                Diag.log("kuwo: 详情解析失败 ${t.javaClass.simpleName}")
                return null
            }
        val status = parsed.num("status")?.toInt() ?: 0
        val data = parsed.obj("data")
        if (data == null || status != 200) {
            Diag.log("kuwo: 详情 status=$status msg=${parsed.str("msg")}")
            if (retry) {
                // 限频：等一会儿再试一次
                kotlinx.coroutines.delay(2500)
                return songInfo(musicId, retry = false)
            }
            return null
        }
        return data
    }

    private suspend fun picUrl(musicId: String): String? = songInfo(musicId)?.obj("songinfo")?.str("pic")?.takeIf { it.isNotBlank() }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()
        return jpeg || png || webp
    }

    /** 酷我的字符串里带 `&nbsp;` / `&amp;` 之类的 HTML 实体。 */
    private fun String.unescapeKuwo(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
}
