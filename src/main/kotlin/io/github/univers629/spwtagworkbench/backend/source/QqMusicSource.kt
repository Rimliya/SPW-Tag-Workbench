// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.source

import io.github.univers629.spwtagworkbench.backend.crypto.QrcTripleDes
import io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
import io.github.univers629.spwtagworkbench.backend.net.Http
import io.github.univers629.spwtagworkbench.backend.net.Json
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * QQ 音乐（公开检索接口）。
 *
 * 能力：歌词 + 元数据 + 封面。接口需要 Referer，否则返回空。
 * 封面按 `albummid` 拼公开图床地址，取 800×800 而不是缩略图。
 */
class QqMusicSource : MusicSource {
    override val id: String = "qqmusic"

    override val label: String = "QQ音乐"

    override val capabilities: Set<Capability> =
        setOf(Capability.LYRICAL, Capability.META, Capability.COVER, Capability.ARTIST_COVER)

    override val weight: Int = 40

    /**
     * 专辑图床前缀。
     *
     * **必须用 `R800x800`**：`T002R0x0M000`（原图）对多数专辑直接 404。
     * 实测同一个 albummid：`R0x0` → 404，`R800x800` → 157KB JPEG，
     * `R500x500` → 79KB。`y.qq.com` 与 `y.gtimg.cn` 返回同一份文件。
     */
    private val coverHost = "https://y.gtimg.cn/music/photo_new/T002R1200x1200M000"

    /** 歌手图：同一图床，前缀 T001。 */
    private val ARTIST_COVER_HOST = "https://y.gtimg.cn/music/photo_new/T001R1200x1200M000"

    private val searchHeaders = mapOf("Referer" to "https://y.qq.com/")

    private val lyricHeaders =
        mapOf(
            "Referer" to "https://y.qq.com/portal/player.html",
            "Accept" to "*/*",
        )

    override suspend fun search(query: SearchQuery): SourceResult {
        val keyword = query.keyword
        if (keyword.isEmpty()) return SourceResult(id, label, failure = "标题为空")

        // QQ 搜索：**Lite 优先、Desktop 回退、双主机轮询**。
        //
        // 之前只用 Desktop 方法（`req_0` 键、无 `comm`），返回 0 条——
        // 歌词候选里没有 QQ 源，也没有报错。
        // 参考项目 SPW-Tag-Workbench 的 `qqSearch` 就是这么修的：
        // - Lite 用顶层键 `request`，歌曲在 `item_song`
        // - Desktop 用顶层键 `req_1`，歌曲在 `song.list`
        // - 两种都要看，且**取第一个非空的**（Desktop 响应里可能同时有空的 item_song）
        // - 必须带 `comm`（`ct=19` / `cv=1859`），否则被风控
        val list = qqSearch(keyword)
        if (list.isEmpty()) {
            Diag.log("qqmusic: 搜索「$keyword」两种方法与两台主机均返回 0 条")
            return SourceResult(id, label)
        }
        val candidates =
            list.mapNotNull { song ->
                val title = song.str("name") ?: return@mapNotNull null
                val mid = song.str("mid") ?: return@mapNotNull null
                val singerObj = song.arr("singer").firstOrNull()
                val artist = singerObj?.str("name").orEmpty()
                val singerMid = singerObj?.str("mid").orEmpty()
                val albumObj = song.obj("album")
                val album = albumObj?.str("name").orEmpty()
                val albumMid = albumObj?.str("mid").orEmpty()
                val seconds = song.num("interval")?.toLong() ?: 0L
                val artwork = albumCover(albumMid)
                SourceCandidate(
                    sourceId = id,
                    sourceLabel = label,
                    remoteId = mid,
                    // 数字 songID：QRC 逐字接口只认它，而 LRC 只认上面的 mid
                    altId = song
                        .num("id")
                        ?.toLong()
                        ?.toString()
                        .orEmpty(),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = seconds * 1000L,
                    hasLyrics = true,
                    hasCover = artwork.isNotBlank(),
                    // 优先取 qrc（逐字）；取不到会退回 lrc
                    lyricFormat = "qrc",
                    artworkUrl = artwork,
                    artistArtworkUrl = artistCover(singerMid),
                    // client_search_cp 上没有 time_public / index_album / index_cd（都是空串），
                    // 但 pubtime 是 Unix 秒级时间戳，能换出年份
                    year = parseYear(song.str("pubtime")),
                    albumArtist =
                        if (artist.isNotBlank() && !artist.contains("&") && !artist.contains('/')) {
                            artist
                        } else {
                            ""
                        },
                )
            }
        Diag.log(
            "qqmusic: 搜索「$keyword」返回 ${candidates.size} 条，" +
                "带专辑封面 ${candidates.count { it.hasCover }} 条，" +
                "带艺人图 ${candidates.count { it.artistArtworkUrl.isNotBlank() }} 条",
        )
        return SourceResult(id, label, candidates = candidates)
    }

    /**
     * 艺术家图地址。
     *
     * 同一套图床把前缀从 `T002`（专辑）换成 **`T001`（歌手）** 即可。
     * 实测 `T001R{1500}x{1500}` = 1500×1500 / 527KB，`R1200` = 1200×1200 / 341KB。
     */
    private fun artistCover(singerMid: String): String = if (singerMid.isBlank()) "" else "$ARTIST_COVER_HOST$singerMid.jpg"

    /**
     * `pubtime` 是 Unix 秒级时间戳（实测 `1059580800` = 2003-07-30）。
     * 当作毫秒处理会算出 1970 年，必须按秒。
     */
    private fun parseYear(raw: String?): Int {
        val seconds = raw?.trim()?.toLongOrNull() ?: return 0
        if (seconds <= 0L) return 0
        val millis = if (seconds > 100_000_000_000L) seconds else seconds * 1000L
        return try {
            java.time.Instant
                .ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .year
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * JSON 字符串转义。
     *
     * 曲名里可能有引号、反斜杠，直接拼进 body 会让请求变成非法 JSON。
     */
    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * 专辑图地址。
     *
     * 用 `R1200x1200`：实测同一 albummid 下 `R300`=300×300、`R800`=800×800、
     * `R1200`=1200×1200，服务端都支持。`R0x0`（原图）对多数专辑直接 404。
     */
    private fun albumCover(albumMid: String): String = if (albumMid.isBlank()) "" else "$coverHost$albumMid.jpg"

    override suspend fun cover(candidate: SourceCandidate): ByteArray? {
        val mid =
            candidate.artworkUrl
                .substringAfterLast("M000", "")
                .substringBefore(".jpg")
        // 逐档降级：1200 → 800 → 500（不是每个 albummid 都有大图）
        val urls =
            buildList {
                if (candidate.artworkUrl.isNotBlank()) add(candidate.artworkUrl)
                if (mid.isNotBlank()) {
                    add("https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg")
                    add("https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg")
                }
            }.distinct()
        for (url in urls) {
            val bytes = Http.getBytes(url, timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
        }
        Diag.log("qqmusic: 取封面失败（试了 ${urls.size} 个地址）")
        return null
    }

    /**
     * 艺术家图（歌手头像）。
     *
     * 与专辑封面**同一图床、不同前缀**：`T001` 是歌手、`T002` 是专辑。
     * 实测 `T001R1500` = 1500×1500 / 527KB，`R1200` = 1200×1200 / 341KB。
     */
    override suspend fun artistCover(candidate: SourceCandidate): ByteArray? {
        val singerMid =
            candidate.artistArtworkUrl
                .substringAfterLast("M000", "")
                .substringBefore(".jpg")
        if (singerMid.isBlank()) return null
        val urls =
            listOf(
                "https://y.gtimg.cn/music/photo_new/T001R1500x1500M000$singerMid.jpg",
                "https://y.gtimg.cn/music/photo_new/T001R1200x1200M000$singerMid.jpg",
                "https://y.gtimg.cn/music/photo_new/T001R800x800M000$singerMid.jpg",
            )
        for (url in urls) {
            val bytes = Http.getBytes(url, timeoutMs = 9000)
            if (bytes != null && looksLikeImage(bytes)) return bytes
        }
        Diag.log("qqmusic: 取艺术家图失败（试了 ${urls.size} 个尺寸）")
        return null
    }

    /**
     * QQ 搜索的完整尝试链：Lite 与 Desktop 各试两台主机，返回第一个非空结果。
     *
     * 为什么这么绕：QQ 的搜索接口对客户端类型和主机都有风控，单点失败就会**静默返回 0 条**，
     * 歌词候选里没有 QQ 源，也没有错误提示。
     * 参考项目 SPW-Tag-Workbench 的做法是逐层回退，这里照搬。
     */
    private suspend fun qqSearch(keyword: String): List<Json> {
        // Lite 优先：它的响应更小、风控更松
        for (desktop in listOf(false, true)) {
            for (host in QQ_SEARCH_HOSTS) {
                val body = if (desktop) qqDesktopBody(keyword) else qqLiteBody(keyword)
                val response =
                    Http.postJson(
                        url = "https://$host/cgi-bin/musicu.fcg",
                        body = body,
                        headers = searchHeaders + mapOf("Content-Type" to "application/json"),
                        timeoutMs = 9000,
                    )
                if (!response.ok) {
                    Diag.log("qqmusic: $host ${if (desktop) "Desktop" else "Lite"} 请求失败 ${response.error}")
                    continue
                }
                val parsed =
                    runCatching { Json.parse(response.body) }.getOrElse {
                        Diag.log("qqmusic: $host 响应解析失败 ${it.javaClass.simpleName}")
                        null
                    } ?: continue
                val songs = extractQqSongs(parsed)
                if (songs.isNotEmpty()) {
                    Diag.log("qqmusic: $host ${if (desktop) "Desktop" else "Lite"} 命中 ${songs.size} 条")
                    return songs
                }
                Diag.log("qqmusic: $host ${if (desktop) "Desktop" else "Lite"} 返回 0 条")
            }
        }
        return emptyList()
    }

    /**
     * 从 musicu 响应里取出歌曲数组，兼容两种形状：
     * - Lite：顶层键 `request`，歌曲在 `data.body.item_song`
     * - Desktop：顶层键 `req_1`，歌曲在 `data.body.song.list`
     *
     * **两种都要看，取第一个非空的**：Desktop 响应里可能同时出现一个空的 `item_song`，
     * 见空就返回会遮蔽 `song.list` 里的真实结果。
     */
    private fun extractQqSongs(root: Json): List<Json> {
        val body =
            listOf("req_1", "request")
                .firstNotNullOfOrNull { key -> root.obj(key)?.obj("data")?.obj("body") }
                ?: return emptyList()
        body.arr("item_song").takeIf { it.isNotEmpty() }?.let { return it }
        return body.obj("song")?.arr("list") ?: emptyList()
    }

    /** Lite 方法：顶层键 `request`，KM 端常用于规避 Desktop 的风控。 */
    private fun qqLiteBody(keyword: String): String =
        buildString {
            append("""{"comm":{"ct":11,"cv":"0"},"request":{""")
            append(""""method":"DoSearchForQQMusicLite",""")
            append(""""module":"music.search.SearchCgiService",""")
            append(""""param":{"search_id":"""")
            append(System.currentTimeMillis() % 86400000 + 36028797018963968L)
            append(""","remoteplace":"search.android.keyboard",""")
            append(""""query":""")
            append(jsonEscape(keyword))
            append(""","search_type":0,"num_per_page":30,"page_num":1,"highlight":0,"nqc_flag":0,"page_id":1,"grp":1}}}""")
        }

    /** Desktop 方法：顶层键 `req_1`，必须带 `comm` 否则被风控。 */
    private fun qqDesktopBody(keyword: String): String =
        buildString {
            append("""{"comm":{"ct":"19","cv":"1859"},"req_1":{""")
            append(""""method":"DoSearchForQQMusicDesktop",""")
            append(""""module":"music.search.SearchCgiService",""")
            append(""""param":{"query":""")
            append(jsonEscape(keyword))
            append(""","num_per_page":30,"page_num":1,"search_type":0,"grp":1}}}""")
        }

    /** 非图片响应（错误页、占位图）不要写进文件。 */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()
        return jpeg || png || webp
    }

    /**
     * 取歌词：优先**逐字 QRC**，拿不到再回退逐行 LRC。
     *
     * QRC 走 musicu 的 `GetPlayLyricInfo`，返回的是 3DES 加密 + zlib 压缩 + XML 包裹的
     * base64/hex，解密链见 [decodeQrc]。老接口 `fcg_query_lyric_new.fcg` 只有逐行 LRC。
     */
    override suspend fun lyrics(candidate: SourceCandidate): String? {
        val qrcText = fetchQrc(candidate)
        if (!qrcText.isNullOrBlank()) {
            // **必须转成结构化歌词再返回**，不能把 QRC 原始机器格式交给界面。
            //
            // QRC 的形态是 `[起始,时长]字(起始,时长)字(起始,时长)…`，
            // 它不属于本项目支持的任何格式（LRC / SPL / ESLyric / SRT / ASS / 纯文本）。
            // 直接显示就是用户在预览里看到的那一堆数字——编辑器、打轴页、
            // 格式下拉全都认不出它，等于把内部中间格式泄漏到了界面。
            //
            // 参考项目 SPW-Tag-Workbench 的 `StructuredLyrics.render` 就是这么做的：
            // 取到 QRC 立刻转成 SPL 写法 `[t]词[t]词[t]`（时间用标准 LRC 时间戳）。
            // 这样歌词能正常显示，逐字时间也完整保留在每个字前后的 [t] 里。
            val structured =
                runCatching { LyricsFormat.qrcToStructured(qrcText) }
                    .getOrElse { "" }
            if (structured.isNotBlank()) {
                Diag.log("qqmusic: qrc ${qrcText.length} 字 -> 结构化 ${structured.length} 字")
                return structured
            }
            Diag.log("qqmusic: qrc 转结构化失败，回退逐行")
        }
        val lrcText = fetchLrc(candidate)
        Diag.log("qqmusic: 回退逐行 lrc ${lrcText?.length ?: 0} 字")
        return lrcText
    }

    /** 逐字 QRC：musicu RPC + 3DES 解密。失败返回 null。 */
    private suspend fun fetchQrc(candidate: SourceCandidate): String? {
        // 优先用 altId（真正的数字 songID）。
        // 回退到从 remoteId 里抠数字是**错的**：remoteId 是 songmid（如 002XrxE703W2RE），
        // filter { isDigit } 会抠出 002 这类假 ID，接口返回空，于是永远回退 LRC。
        val songId = candidate.altId.ifBlank { candidate.remoteId.filter { it.isDigit() } }
        if (songId.isBlank()) return null
        val body =
            buildString {
                append("""{"req_0":{"method":"GetPlayLyricInfo",""")
                append(""""module":"music.musichallSong.PlayLyricInfo",""")
                append(""""param":{"songID":""").append(songId).append(""",""")
                append(""""songName":""").append("\"\"").append(""",""")
                append(""""albumName":""").append("\"\"").append(""",""")
                append(""""singerName":""").append("\"\"").append(""",""")
                append(""""crypt":1,"qrc":1,"trans":0,"roma":0,"type":0}}}""")
            }
        val response =
            runCatching {
                Http.postJson(
                    url = "https://u.y.qq.com/cgi-bin/musicu.fcg",
                    body = body,
                    headers = lyricHeaders + mapOf("Content-Type" to "application/json"),
                    timeoutMs = 8000,
                )
            }.getOrNull() ?: return null
        if (!response.ok) {
            Diag.log("qqmusic: qrc 请求失败 ${response.error}")
            return null
        }
        val parsed = runCatching { Json.parse(response.body) }.getOrNull() ?: return null
        val lyricRaw = parsed
            .obj("req_0")
            ?.obj("data")
            ?.str("lyric")
            .orEmpty()
        if (lyricRaw.isBlank()) return null
        return decodeQrc(lyricRaw)
    }

    /**
     * 解码 QRC 载荷。
     *
     * 顺序：`hex 字符串 → 3DES 解密 → zlib 解压 → 取出 XML 里的 LyricContent`。
     * 有些响应首部带长度前缀，所以先去掉前 8 个字符再解。
     */
    private fun decodeQrc(payload: String): String? =
        runCatching {
            val hex = payload.trim().removePrefix("QRC").trim()
            // 有些响应带 "hex=..." 前缀
            val pureHex = hex.substringAfter("hex=", hex)
            if (pureHex.length < 16) return@runCatching null
            val cipher = hexToBytes(pureHex)
            val plain = QrcTripleDes.decrypt(cipher, QRC_KEY.toByteArray(Charsets.UTF_8))
            val text =
                java.io.ByteArrayInputStream(plain).use { input ->
                    java.util.zip
                        .InflaterInputStream(input)
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                }
            // 取出 <Lyric_1 LyricType="1" LyricContent="..."/>
            val m = Regex("""<Lyric_\d+\s+LyricType="\d+"\s+LyricContent="([\s\S]*?)"\s*/>""").find(text)
            val content = m?.groupValues?.get(1) ?: text
            decodeXmlEntities(content)
        }.getOrElse { t ->
            Diag.log("qqmusic: qrc 解码失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun decodeXmlEntities(text: String): String =
        text
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    /** 逐行 LRC：老接口，作为 QRC 失败时的回退。 */
    private suspend fun fetchLrc(candidate: SourceCandidate): String? {
        val response =
            Http.get(
                url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                query = mapOf("songmid" to candidate.remoteId, "format" to "json", "nobase64" to "1"),
                headers = lyricHeaders,
                timeoutMs = 7000,
            )
        if (!response.ok) {
            Diag.log("qqmusic: 取歌词失败 ${response.error}")
            return null
        }
        // 该接口有时返回 JSONP 包裹，先剥一层
        val body = response.body.trim()
        val jsonText =
            if (body.startsWith("{")) {
                body
            } else {
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start in 0 until end) body.substring(start, end + 1) else body
            }
        val parsed =
            try {
                Json.parse(jsonText)
            } catch (t: Throwable) {
                Diag.log("qqmusic: 歌词响应解析失败 ${t.javaClass.simpleName}")
                return null
            }
        return parsed.str("lyric")?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /** QRC 3DES 密钥（与参考项目 `qq/source.js` 的 QRC_KEY 一致）。 */
        const val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL"

        /** 两台搜索主机：前者主用，后者在风控时兜底。 */
        val QQ_SEARCH_HOSTS = listOf("u.y.qq.com", "shu6.y.qq.com")
    }
}
