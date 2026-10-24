// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.univers629.spwtagworkbench.backend.audio.Spectrogram
import io.github.univers629.spwtagworkbench.backend.host.HostBridge
import io.github.univers629.spwtagworkbench.backend.host.PlaybackSignal
import io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
import io.github.univers629.spwtagworkbench.backend.match.Matching
import io.github.univers629.spwtagworkbench.backend.model.BatchProgress
import io.github.univers629.spwtagworkbench.backend.model.BatchReport
import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.model.WriteOutcome
import io.github.univers629.spwtagworkbench.backend.read.CoverStore
import io.github.univers629.spwtagworkbench.backend.read.EmbeddedPicture
import io.github.univers629.spwtagworkbench.backend.read.PictureTypeStandard
import io.github.univers629.spwtagworkbench.backend.source.Aggregator
import io.github.univers629.spwtagworkbench.backend.source.Capability
import io.github.univers629.spwtagworkbench.backend.source.ItunesSource
import io.github.univers629.spwtagworkbench.backend.source.KugouSource
import io.github.univers629.spwtagworkbench.backend.source.KuwoSource
import io.github.univers629.spwtagworkbench.backend.source.LrclibSource
import io.github.univers629.spwtagworkbench.backend.source.MusicSource
import io.github.univers629.spwtagworkbench.backend.source.NeteaseSource
import io.github.univers629.spwtagworkbench.backend.source.QqMusicSource
import io.github.univers629.spwtagworkbench.backend.source.RankedCandidate
import io.github.univers629.spwtagworkbench.backend.source.SearchQuery
import io.github.univers629.spwtagworkbench.backend.source.SourceCandidate
import io.github.univers629.spwtagworkbench.backend.source.SourceGroup
import io.github.univers629.spwtagworkbench.backend.write.AudioTagWriter
import io.github.univers629.spwtagworkbench.backend.write.BatchWriter
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 插件的数据中枢。
 *
 * 所有异步任务都带一个 epoch 令牌：切歌、换页、重新发起任务都会让旧令牌过期，
 * 过期回调直接丢弃，不会把上一首歌的数据写进当前界面。
 */
object Repo {
    /** 主封面下方的槽位数（装艺术家类图片）。 */
    private const val SLOT_COUNT = 2

    /**
     * 批量匹配的**外层**并发度（同时处理几首歌）。
     *
     * 为什么是 4 而不是 12：真正的并发上限是 [NetGate] 的 12 个许可，
     * 而单首歌内部最多同时打 5~6 个源。外层开 4 路 → 4×3≈12，
     * 既吃满闸门又不会让某一首的源请求被挤到饿死。
     *
     * 为什么不按 CPU 核数算：这里全程在等网络，等 IO 不占 CPU，
     * 并发度与核数无关（参考 MusicTag 的 `ONLINE_CONCURRENCY` 也是常量而非核数）。
     */
    private const val MATCH_CONCURRENCY = 4

    /**
     * 每个源最多取多少张图（封面 / 艺术家图）。
     *
     * 旧值 3 太保守：MusicTag 的 `WebSearchItemsLimit` 默认 100，
     * 实际每源能拿到 6~20 张候选。候选越少，用户越觉得「没搜到」。
     * 取 10 是吞吐与内存的折中——单张约 200KB，10 张约 2MB，且只是候选区的临时数据。
     */
    private const val PER_SOURCE_PICKS = 10

    /** 上一次发起封面检索的曲目 key，用于防重复加载。 */
    private var lastCoverKey: String = ""

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 播放跟随 ─────────────────────────────────────────────────────────────
    var track by mutableStateOf(TrackMeta.Empty)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set

    /**
     * 宿主推来的最新位置（尚未提交到 Compose 状态）。
     *
     * 宿主推得很密，若每次都写进 [positionMs]（Compose 状态），
     * 整个打轴页都会跟着重组，帧率被拖垮。
     *
     * 所以推送只更新这个原始值；真正搬进状态的是 [publishPlayhead]，
     * 由界面侧在 `withFrameNanos` 的帧回调里调用——与渲染帧严格同步。
     */
    @Volatile
    private var rawPositionMs: Long = 0L

    /**
     * 待确认的批量写入曲目数；> 0 表示需要用户在**页面级弹窗**里确认。
     *
     * 放在 Repo 而不是 BatchRow 的局部状态里：弹窗必须在页面根级渲染
     * （`Box(fillMaxSize)` 只有父容器铺满整页时才能居中），
     * 而按钮在深层的元数据框里。
     */
    var pendingAlbumBatch by mutableStateOf(false)
        private set

    /** 同专辑可写入的曲目数（用于弹窗文案）。 */
    val albumBatchCount: Int
        get() {
            val src = focused()
            if (!src.isPresent || src.album.isBlank()) return 0
            return library.count {
                it.hasFile && it.album.equals(src.album, ignoreCase = true)
            }
        }

    /** 请求批量写入（弹出确认）。 */
    fun requestAlbumBatch() {
        if (albumBatchCount == 0) {
            postStatus(Status("没有同专辑的曲目可写", Status.Kind.Warn))
            return
        }
        pendingAlbumBatch = true
    }

    fun dismissAlbumBatch() {
        pendingAlbumBatch = false
    }

    // ── 播放倍速（打轴用） ────────────────────────────────────────────────

    /**
     * 打轴时宿主播放倍速。1.0 为原速。
     *
     * 降速能让字边界听/看得更清，是打轴最实用的功能之一。
     * 宿主 API 未暴露倍速，[HostBridge.setPlaybackSpeed] 走反射改。
     */
    var playbackSpeed by mutableStateOf(1.0f)
        private set

    /** 可选的倍速档位。 */
    val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

    /** 切到下一个/上一个倍速档位。[dir] 为 +1 / -1。 */
    fun stepPlaybackSpeed(dir: Int) {
        val idx = speedSteps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
        val cur = if (idx >= 0) idx else speedSteps.indexOf(1.0f)
        val next = (cur + dir).coerceIn(0, speedSteps.size - 1)
        val target = speedSteps[next]
        if (HostBridge.setPlaybackSpeed(target)) {
            playbackSpeed = target
            postStatus(Status("倍速：${target}x", Status.Kind.Ok))
        } else {
            postStatus(Status("宿主的倍速接口不可用", Status.Kind.Warn))
        }
    }

    /** 归零到原速。 */
    fun resetPlaybackSpeed() {
        if (playbackSpeed == 1.0f) return
        if (HostBridge.setPlaybackSpeed(1.0f)) playbackSpeed = 1.0f
    }

    /** 上一拍已提交的位置；相同就跳过，避免无意义的重组。 */
    @Volatile
    private var lastPublishedMs: Long = -1L

    /**
     * 收到 [rawPositionMs] 那一刻的单调时钟（`System.nanoTime`）。
     *
     * 宿主推送位置非常稀疏（实测 3 秒内 0～3 次），如果画面只跟着推送走，
     * 显示就会「一顿一顿」。用这个时间戳在两次推送之间做线性外推，
     * 让每帧都有连续的位置，收到新推送时再校正。
     */
    @Volatile
    private var rawPositionNanos: Long = 0L

    /** 是否已经收到过至少一次位置。 */
    @Volatile
    private var hasPosition: Boolean = false

    /**
     * 把播放位置提交到 Compose 状态；**播放中用本地时钟插值外推**。
     *
     * 为什么必须插值：实测宿主 `onPositionUpdated` 3 秒内只推 0～3 次，
     * 只跟着推送走的话，打轴页的谱轴/进度条/预览就是「一秒跳一两下」——
     * 用户描述为「一帧一帧的，所有部分都卡」。
     * 这不是渲染慢（实测绘制合计 <1ms/帧），是数据源太稀。
     *
     * 做法：记住收到位置时的 `nanoTime`，每帧用
     * `上次位置 + (now - 收到时刻)` 外推；新推送到来时用它校正（可能与外推值
     * 有几十毫秒偏差，直接采纳，避免累积漂移）。暂停时不外推。
     */
    fun publishPlayhead(): Boolean {
        io.github.univers629.spwtagworkbench.core.FrameStats
            .notePlayhead(playing, hasPosition, rawPositionMs)
        if (!hasPosition) return false
        val want =
            if (playing) {
                val elapsedMs = (System.nanoTime() - rawPositionNanos) / 1_000_000L
                // 外推上限：一次推送超过 1 秒没来就不再往前跑，
                // 否则数据源卡住时画面会跑飞（例如缓冲、暂停未及时通知）。
                rawPositionMs + elapsedMs.coerceIn(0L, 1000L)
            } else {
                rawPositionMs
            }
        if (want == lastPublishedMs) return false
        lastPublishedMs = want
        positionMs = want
        updateCurrentLine(want)
        io.github.univers629.spwtagworkbench.core.FrameStats
            .countPositionUpdate()
        return true
    }

    /** 按位置更新当前行（原 onPosition 的内联逻辑）。 */
    private fun updateCurrentLine(ms: Long) {
        if (lines.isEmpty()) return
        var index = -1
        for (i in lines.indices) {
            if (lines[i].startMs <= ms) index = i else break
        }
        if (index != currentLine) currentLine = index
    }

    // ── 曲库 ────────────────────────────────────────────────────────────────
    var library by mutableStateOf<List<TrackMeta>>(emptyList())
        private set
    var selected by mutableStateOf<TrackMeta?>(null)
        private set
    var libraryLoading by mutableStateOf(false)
        private set

    // ── 歌词 ────────────────────────────────────────────────────────────────
    var lines by mutableStateOf<List<LyricLine>>(emptyList())
        private set

    /**
     * 歌词的**源文本**（未解析的原始内容）。
     *
     * 关键区别：标签里存的可能是逐字 LRC（`[00:12.34][00:12.50]字`）、SPL 或纯文本。
     * [lines] / [editedLines] 是解析后的「一行一句」视图，会丢掉时间标签与逐字结构。
     * 主视图编辑栏与歌词匹配页右栏显示的都是这份源文本。
     */
    var lyricText by mutableStateOf("")
        private set

    /** 整首歌词的累计时间轴偏移（毫秒），设置区加减按钮改的就是它。 */
    var lyricOffsetMs by mutableStateOf(0)
        private set

    /**
     * 直接改写某一行的行首/行尾时间（毫秒）。
     *
     * 打轴页右栏「行」那一块用的是它：用户看到的是**真实秒数**，
     * 加减号与输入框都是直接改这个值，而不是偏移量。
     * 传 -1 表示未标记。
     */
    fun setLineTime(
        lineIndex: Int,
        startMs: Long,
        endMs: Long,
    ) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return
        val s = if (startMs < 0L) -1L else startMs.coerceAtLeast(0L)
        val e = if (endMs < 0L) -1L else endMs.coerceAtLeast(0L)
        list[lineIndex] = line.copy(startMs = s, endMs = e)
        editedLines = list
        lines = list
    }

    /**
     * **补全时间戳**：把一行里没打轴的字按前后已知时间插值补齐。
     *
     * 语义参考 StrangeUtaGame `_execute_complete_timestamp`：
     * - 中间的字：在左右最近的两个已知时间戳之间**线性插值**
     * - 行首缺失：用第一个已知时间戳 **减去** [headOffsetMs]
     * - 行尾缺失：用最后一个已知时间戳 **加上** [tailOffsetMs]
     *
     * 只在字数为 0（无逐字单元）时按整行均分，避免破坏已有数据。
     *
     * @return 补全的字数
     */
    fun completeTimestamps(
        lineIndex: Int,
        headOffsetMs: Long = 150L,
        tailOffsetMs: Long = 150L,
    ): Int {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return 0
        val cells = line.cells
        if (cells.size < 2) return 0
        // 找已知起点的索引
        val known = cells.indices.filter { cells[it].startMs >= 0L }
        if (known.isEmpty()) return 0
        val out = cells.toMutableList()
        var filled = 0
        for (i in cells.indices) {
            if (cells[i].startMs >= 0L) continue
            // 找左右最近的已知起点
            val left = known.lastOrNull { it < i }
            val right = known.firstOrNull { it > i }
            val start =
                when {
                    left != null && right != null -> {
                        // 线性插值：按索引比例分
                        val lv = cells[left].startMs
                        val rv = cells[right].startMs
                        val steps = (right - left).toLong()
                        lv + (rv - lv) * (i - left) / steps
                    }
                    left != null -> cells[left].startMs + headOffsetMs
                    right != null -> (cells[right].startMs - headOffsetMs).coerceAtLeast(0L)
                    else -> continue
                }
            // 终点取下一个字的起点，或行尾
            val end =
                out.getOrNull(i + 1)?.startMs?.takeIf { it >= 0L }
                    ?: (line.endMs.takeIf { it > start } ?: (start + tailOffsetMs))
            out[i] = cells[i].copy(startMs = start.coerceAtLeast(0L), endMs = end)
            filled++
        }
        if (filled > 0) {
            list[lineIndex] = line.copy(cells = out)
            editedLines = list
            lines = list
        }
        return filled
    }

    /**
     * **批量平移**某一行所有时间戳（行首/行尾/字首/字尾一起动）。
     *
     * 参考 StrangeUtaGame 的「调整原始时间戳」：每次点击叠加一次，可反复微调。
     */
    fun shiftLineTimestamps(
        lineIndex: Int,
        deltaMs: Long,
    ) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return

        fun shift(v: Long) = if (v < 0L) v else (v + deltaMs).coerceAtLeast(0L)
        list[lineIndex] =
            line.copy(
                startMs = shift(line.startMs),
                endMs = shift(line.endMs),
                cells =
                    line.cells.map {
                        it.copy(startMs = shift(it.startMs), endMs = shift(it.endMs))
                    },
            )
        editedLines = list
        lines = list
        postStatus(Status("整行时间 ${if (deltaMs >= 0) "+" else ""}$deltaMs ms", Status.Kind.Ok))
    }

    /** 批量平移当前选中字的时间戳。 */
    fun shiftCellTimestamps(
        lineIndex: Int,
        cellIndex: Int,
        deltaMs: Long,
    ) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return
        val cells = line.cells.toMutableList()
        val cell = cells.getOrNull(cellIndex) ?: return

        fun shift(v: Long) = if (v < 0L) v else (v + deltaMs).coerceAtLeast(0L)
        cells[cellIndex] = cell.copy(
            startMs = shift(cell.startMs),
            endMs = shift(cell.endMs),
        )
        list[lineIndex] = line.copy(cells = cells)
        editedLines = list
        lines = list
        postStatus(Status("该字时间 ${if (deltaMs >= 0) "+" else ""}$deltaMs ms", Status.Kind.Ok))
    }

    /** 直接改写某个字的首/尾时间（毫秒）。传 -1 表示未标记。 */
    fun setCellTime(
        lineIndex: Int,
        cellIndex: Int,
        startMs: Long,
        endMs: Long,
    ) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return
        val cells = line.cells.toMutableList()
        val cell = cells.getOrNull(cellIndex) ?: return
        val s = if (startMs < 0L) -1L else startMs.coerceAtLeast(0L)
        val e = if (endMs < 0L) -1L else endMs.coerceAtLeast(0L)
        cells[cellIndex] = cell.copy(startMs = s, endMs = e)
        list[lineIndex] = line.copy(cells = cells)
        editedLines = list
        lines = list
    }

    /**
     * 语言轨道开关：同一时间戳下的第 1/2/3 条歌词依次视为原文/译文/罗马音。
     *
     * 这是参考项目的约定（`LyricTextCodec.selectLanguages`）：多语言歌词会把
     * 三种文本挂在**同一个时间戳**上，靠出现顺序区分。关掉某一档就会把对应行滤掉。
     */
    var lyricShowOriginal by mutableStateOf(true)
        private set
    var lyricShowTranslation by mutableStateOf(true)
        private set
    var lyricShowRomanization by mutableStateOf(false)
        private set

    /**
     * **基准歌词**：从文件/在线取回、或经过简繁转换之后的原始内容。
     *
     * 这是整套设置区的锚点（对应参考项目的 `rawFocusedLyrics`）：
     * 格式、偏移、语言轨道都只在**渲染时**从它推导，绝不回写它。
     *
     * 为什么必须有它：把转换结果写回源文本会让下一次转换从「已经转过一次的内容」
     * 再转一遍，于是切换格式越来越乱、偏移越点越偏、撤销也回不到原样。
     * 有了基准，每次改设置都是「基准 → 应用全部设置 → 显示」的纯函数，
     * 点几次都稳定，把偏移调回 0 也等同于没偏移。
     */
    private var lyricBase by mutableStateOf("")

    /**
     * 歌词「外部加载」的版本号，每次从文件/网络载入新歌词时 +1。
     *
     * 编辑框用它判断「该丢弃本地缓冲了」，而**不是**去比较文本内容——
     * 用户每敲一个字 lyricText 都会变，按内容比较会把输入误判成外部变化并清空缓冲。
     */
    var lyricsEpoch by mutableStateOf(0)
        private set

    /** 当前的显式格式选择；null = 不转换，直接显示原文。 */
    var lyricFormatChoice by mutableStateOf<String?>(null)
        private set

    /** 格式标签：有显式选择就显示它，否则按原始文本识别。 */
    val lyricFormat: String
        get() = lyricFormatChoice ?: LyricsFormat.detect(lyricBase).label

    /**
     * 把「基准 + 当前全部设置」渲染成应当显示的歌词文本。
     *
     * 纯函数：同样的基准与设置永远得到同样的结果，不做任何状态写入。
     */
    private fun renderLyrics(): String {
        if (lyricBase.isBlank()) return ""
        // 1. 语言轨道筛选
        var text = filterLyricTracks(lyricBase)
        // 2. 时间轴偏移
        if (lyricOffsetMs != 0) text = shiftText(text, lyricOffsetMs)
        // 3. 格式转换（没选格式就保持原样，保证「不选=不改」）
        val choice = lyricFormatChoice
        if (choice != null) {
            val target = LyricsFormat.Format.entries.firstOrNull { it.label == choice }
            if (target != null) {
                val parsed = LyricsFormat.parse(text)
                if (parsed.isNotEmpty()) text = LyricsFormat.toFormat(parsed, target)
            }
        }
        return text
    }

    /** 按当前设置重算显示文本，并同步到界面与草稿。 */
    private fun rerenderLyrics(post: Boolean = false) {
        val text = renderLyrics()
        lyricText = text
        val parsed = LyricsFormat.parse(text)
        editedLines = parsed
        lines = parsed
        val key = focused().key
        if (key.isNotEmpty()) Drafts.stageLyrics(key, text)
        Diag.log(
            "lyric-render: 基准=${lyricBase.length}字 显示=${text.length}字 " +
                "格式=${lyricFormatChoice ?: "原始"} 偏移=$lyricOffsetMs " +
                "轨道=$lyricShowOriginal/$lyricShowTranslation/$lyricShowRomanization",
        )
        if (post) {
            val on =
                listOfNotNull(
                    "原文".takeIf { lyricShowOriginal },
                    "译文".takeIf { lyricShowTranslation },
                    "罗马音".takeIf { lyricShowRomanization },
                )
            postStatus(
                Status(
                    text =
                        "格式=" + (lyricFormatChoice ?: "原始") +
                            " 偏移=${lyricOffsetMs}ms" +
                            " 轨道=" + if (on.isEmpty()) "（无）" else on.joinToString("、"),
                    kind = Status.Kind.Ok,
                ),
            )
        }
    }

    var lyricSource by mutableStateOf("")
        private set
    var editedLines by mutableStateOf<List<LyricLine>>(emptyList())
    var currentLine by mutableStateOf(-1)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    // ── 封面 ────────────────────────────────────────────────────────────────

    /**
     * 界面上显示的封面：左栏预览窗格 + 主视图大图。
     *
     * 一开始是文件里**内嵌**的那张；只有 [applyCover]（写入草稿）才会换成候选图。
     * 勾选候选（[selectCover]）不动它 —— 左栏的语义是「预览」，不是「已勾选」。
     */
    var cover by mutableStateOf<ImageBitmap?>(null)

    var candidates by mutableStateOf<List<CoverCandidate>>(emptyList())

    /**
     * 导入进来的封面候选。
     *
     * 单独存一份：候选网格只渲染网络结果，而 [candidates] 会被各来源流式返回
     * 整体重建 —— 混在一起的话导入的图在下一次重建时就被丢掉，勾选跟着失效。
     */
    private var importedCovers: List<CoverCandidate> = emptyList()

    /** 本次检索得到的网络候选（不含导入的）。 */
    private var netCandidates: List<CoverCandidate> = emptyList()

    /** 重建 [candidates]：导入的在前，网络候选在后。 */
    private fun rebuildCandidates() {
        candidates = importedCovers + netCandidates
    }

    /** 切歌时把所有候选与勾选一起清掉。 */
    private fun clearCandidates() {
        importedCovers = emptyList()
        netCandidates = emptyList()
        candidates = emptyList()
        selectedCoverId = null
    }

    /**
     * 预览重解的世代号。
     *
     * 同一首曲目上连续写入两张图时，先发起的重解可能后回来；
     * 只认最后一次发起的，避免预览被旧图覆盖。
     */
    private var coverPreviewGen = 0
    private var artistPreviewGen = 0

    /**
     * 当前**勾选**的封面候选 id；没勾选时为 null。
     *
     * 与 [cover]（预览）严格分开：勾选只改这里。
     * 存 id 而不是对象 —— 候选列表会随各来源流式返回被整体重建，存对象会丢勾选，
     * 表现就是「网络候选勾不上」。
     */
    var selectedCoverId by mutableStateOf<String?>(null)
        private set

    /** 勾选的那张候选。 */
    val selectedCover: CoverCandidate? get() = candidates.firstOrNull { it.id == selectedCoverId }

    /** 封面网格槽位（流式载入用）。 */
    var networkCovers by mutableStateOf<List<CoverTile>>(emptyList())
        private set
    var coverLoading by mutableStateOf(false)
        private set

    /**
     * 主封面下方两个槽位。
     *
     * 装艺术家类图片（Artist / LeadArtist / Band），按 [PictureTypeStandard.ARTIST_CHAIN]
     * 的顺序取前两张可用的。标签里没有这类图时对应槽位为 null，界面显示占位。
     */
    var artistSlots by mutableStateOf<List<CoverSlot?>>(listOf(null, null))
        private set

    /**
     * 艺术家页左栏预览：本地艺术家图，或「写入草稿」后选中的网络图。
     *
     * 直接复用 [artistSlots] 的第一格 —— 它的语义就是「当前显示的艺术家图」：
     * 由 [loadCovers] 用本地图播种、由 [applyArtistCover] 换成选中的网络图，
     * 而勾选（[chooseArtistCover]）不会改它。
     */
    val artistPreview: ImageBitmap? get() = artistSlots.firstOrNull()?.image

    /** 艺术家页左栏的分辨率 · 体积描述；没有图时为空串。 */
    val artistPreviewInfo: String
        get() =
            artistSlots
                .firstOrNull()
                ?.let { slot ->
                    infoOfSize(slot.width, slot.height, slot.bytes)
                }.orEmpty()

    /** 封面预览的信息（分辨率 · 体积）：本地封面或已写入草稿的候选图。 */
    var localCoverInfo by mutableStateOf("")
        private set

    // ── 频谱 ────────────────────────────────────────────────────────────────

    var spectrum by mutableStateOf<SpectrumState>(SpectrumState.Idle)

    /** 真实渲染出来的双声道频谱图。 */
    var spectrogram by mutableStateOf<SpectrogramState>(SpectrogramState.Idle)
        private set

    // ── 可见终态 ─────────────────────────────────────────────────────────────

    var status by mutableStateOf<Status?>(null)

    // ── 令牌 ────────────────────────────────────────────────────────────────

    /**
     * 按域拆分的版本号。
     *
     * 之前只有一个全局 epoch，九个异步操作共用：任何操作一启动就把其他在途任务作废，
     * 于是封面加载被频谱/搜索打断后永远落不了地（cover 一直 null）。
     * 现在每个域只作废自己，只有切歌/切选中才整体作废。
     */
    private object Epoch {
        private var lyric = 0
        private var cover = 0
        private var spectrum = 0
        private var online = 0
        private var save = 0

        fun nextLyric(): Int = ++lyric

        fun nextCover(): Int = ++cover

        fun nextSpectrum(): Int = ++spectrum

        fun nextOnline(): Int = ++online

        fun nextSave(): Int = ++save

        fun lyricAlive(t: Int): Boolean = t == lyric

        fun coverAlive(t: Int): Boolean = t == cover

        fun spectrumAlive(t: Int): Boolean = t == spectrum

        fun onlineAlive(t: Int): Boolean = t == online

        fun saveAlive(t: Int): Boolean = t == save

        /** 切歌 / 切选中：对象换了，所有域都作废。 */
        fun invalidateAll() {
            lyric++
            cover++
            spectrum++
            online++
            save++
            Diag.log(
                "epoch: invalidateAll -> lyric=$lyric cover=$cover " +
                    "spectrum=$spectrum online=$online save=$save",
            )
        }
    }

    /** 播放回调可能在 IO 线程，状态写入统一挪到主线程。 */
    fun postOnMain(block: () -> Unit) {
        runCatching { mainScope.launch { block() } }
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    private var poller: kotlinx.coroutines.Job? = null

    /**
     * 轮询当前播放曲目。
     *
     * 不能只依赖 onAfterLoadLyrics：宿主默认歌词加载成功时不会调它，
     * 于是插件永远不知道在播什么，侧栏与封面也就永远不跟随。
     */
    fun startPlaybackFollow() {
        if (poller?.isActive == true) return
        poller =
            scope.launch {
                // isActive 双保险：作用域被 shutdown() 取消后立即退出，
                // 不依赖 delay 抛 CancellationException 这一条路径
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    try {
                        val item =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                WorkshopApi.playback.getCurrentMediaItem()
                            }
                        if (item != null) {
                            withContext(Dispatchers.Main) { onTrackChanged(item) }
                        }
                    } catch (t: Throwable) {
                        Diag.log("poll: 取当前曲目失败 ${t.javaClass.simpleName}")
                    }
                    kotlinx.coroutines.delay(2000L)
                }
            }
    }

    fun bootstrap() {
        // getCurrentMediaItem 会阻塞到数据库查询完成，必须离开主线程
        scope.launch {
            val item = runCatching { WorkshopApi.playback.getCurrentMediaItem() }.getOrNull()
            if (item != null) {
                withContext(Dispatchers.Main) { onTrackChanged(item) }
            }
        }
        refreshLibrary()
    }

    fun shutdown() {
        Epoch.invalidateAll()
        status = null
        spectrum = SpectrumState.Idle
        // **生命周期闭环**：取消两个作用域与轮询，否则禁用插件后
        // `while(true)` 的 getCurrentMediaItem 轮询继续在宿主进程里
        // 每 2 秒查一次数据库，postOnMain 排队的回调也会继续执行。
        poller?.cancel()
        poller = null
        scope.cancel()
        mainScope.cancel()
        stopped = true
    }

    /**
     * stop() 是否已执行。宿主对「禁用插件的扩展点是否停止分发」没有明文保证，
     * [io.github.univers629.spwtagworkbench.extension.PlaybackExt] 依此丢弃回调。
     */
    @Volatile
    var stopped: Boolean = false
        private set

    fun refreshLibrary() {
        val token = Epoch.nextOnline()
        libraryLoading = true
        scope.launch {
            val result =
                runCatching {
                    WorkshopApi.library
                        .getAllTracks()
                        .toCompletableFuture()
                        .get()
                }.getOrNull()
            withContext(Dispatchers.Main) {
                libraryLoading = false
                // **曲库写入不检查 online token**。
                //
                // online 域的语义是「切歌/换选中时，进行中的在线匹配作废」——
                // 那对 searchOnline / matchSelected / loadOnlineLyrics 等单曲操作是对的。
                // 但曲库是全局数据，与「当前编辑哪首」无关：bootstrap 发起的查询
                // 途中宿主推送了 onAfterLoadLyrics（onTrackChanged → invalidateAll →
                // online++），token 作废，返回的 39 首被静默丢弃 —— 新装宿主上
                // 表现为「曲库列表是空的」（本机日志 epoch: invalidateAll -> online=2
                // 正是证据）。切歌不该清曲库。
                //
                // 停用场景不需要 token：shutdown 里 scope.cancel() 已把本协程取消。
                if (result != null) {
                    library = result.map { it.toMeta() }
                    if (selected == null) selected = library.firstOrNull()
                    // 曲库里没有时至少保留当前播放
                    if (library.isEmpty() && track.isPresent) library = listOf(track)
                } else {
                    postStatus(Status("曲库读取失败", Status.Kind.Error))
                }
            }
        }
    }

    // ── 播放回调（由 Extension 推送） ─────────────────────────────────────────
    fun onTrackChanged(item: PlaybackExtensionPoint.MediaItem) {
        val meta = item.toMeta()
        if (meta.key == track.key && track.isPresent) {
            // 同一首歌刷新元数据（例如刚写完标签后宿主重读）
            track = meta
            return
        }
        Epoch.invalidateAll()
        track = meta
        PlaybackSignal.currentId = meta.id
        PlaybackSignal.currentPath = meta.path
        positionMs = 0L
        // 插值基准一并重置：否则新歌第一帧会用上一首的时间差外推
        rawPositionMs = 0L
        rawPositionNanos = System.nanoTime()
        hasPosition = false
        lastPublishedMs = -1L
        currentLine = -1
        lines = emptyList()
        editedLines = emptyList()
        lyricBase = ""
        lyricText = ""
        lyricSource = ""
        cover = null
        clearCandidates()
        spectrum = SpectrumState.Idle
        // 艺术家候选同样属于上一首，不清会残留在新歌的封面上
        artistCandidate = null
        artistSlots = listOf(null, null)
        networkCovers = emptyList()
        networkArtistCovers = emptyList()
        // 切歌：正在渲染的频谱作废，旧结果不能留在新歌上
        Spectrogram.cancel()
        spectrogram = SpectrogramState.Idle
        if (selected == null || selected?.key == meta.key) selected = meta
        loadLyrics(meta)
        loadCovers(meta)
    }

    fun onPlayingChanged(value: Boolean) {
        playing = value
        // 播放状态切换时把外推基准对齐到现在，避免暂停期间累积的偏差
        rawPositionNanos = System.nanoTime()
        lastPublishedMs = -1L
        // 后端「写前释放播放」需要独立于 UI 的播放状态
        PlaybackSignal.isPlaying = value
    }

    fun onPosition(ms: Long) {
        // 只有「正在看的那首 == 正在播的那首」时才收播放进度。
        // 否则用户在曲库里选了别的歌，宿主的播放进度会推着谱轴播放头与
        // 歌词行高亮继续走 —— 看起来就像在跟播一首没在放的歌。
        if (!hostSynced) return
        // **只记原始值，不写 Compose 状态**：写状态会让整页重组，而宿主推得很密。
        // 真正的提交交给界面侧的帧循环（[publishPlayhead]），与渲染帧同步。
        rawPositionMs = ms
        // 记下「这个位置是什么时候收到的」：实测宿主 3 秒才推 0～3 次，
        // 光靠推送推进画面就会一顿一顿，需要用这个时间戳在帧循环里做线性外推。
        rawPositionNanos = System.nanoTime()
        hasPosition = true
    }

    fun onLyricLines(raw: List<PlaybackExtensionPoint.LyricsLine>) {
        // 宿主推送的是「正在播放那首」的时间轴。若用户手选了别的歌，
        // 这份时间轴不属于它，收下就会把另一首的歌词盖到当前编辑上。
        if (!hostSynced) return
        if (raw.isEmpty()) {
            if (lines.isEmpty()) loadLyrics(track)
            return
        }
        val parsed =
            raw.map { line ->
                LyricLine(
                    startMs = line.startTime,
                    endMs = line.endTime,
                    text = line.pureMainText.orEmpty(),
                    cells = line.lyricsCells.map { LyricCell(it.text, it.startTime, it.endTime) },
                )
            }
        if (parsed == lines) {
            return
        }
        lines = parsed
        editedLines = parsed
        // 宿主时间轴不是「源文本」——它是解析后的结果，所以这里不覆盖 lyricText，
        // 只把视图标记成已解析，避免编辑栏把空源文本当成用户改过
        lyricSource = "宿主时间轴"
    }

    fun onLyricLine(raw: PlaybackExtensionPoint.LyricsLine?) {
        if (!hostSynced) return
        if (raw == null) {
            currentLine = -1
            return
        }
        val index = lines.indexOfFirst { it.startMs == raw.startTime }
        if (index >= 0) currentLine = index
    }
    // ── 选择 ────────────────────────────────────────────────────────────────

    /** 把编辑焦点对齐到正在播放的曲目（打开宽视图时用）。 */
    fun focusPlaying() {
        val playing = track
        if (playing.isPresent) {
            select(playing)
        } else {
            if (selected == null) {
                library.firstOrNull()?.let { select(it) }
            }
        }
    }

    fun select(meta: TrackMeta) {
        if (selected?.key == meta.key) return
        selected = meta
        Epoch.invalidateAll()
        lines = emptyList()
        editedLines = emptyList()
        lyricBase = ""
        lyricText = ""
        lyricSource = ""
        cover = null
        clearCandidates()
        spectrum = SpectrumState.Idle
        // 换了编辑对象就把播放游标清掉：
        // 否则新歌第一帧会带着上一首的位置，谱轴播放头与行高亮都错位。
        positionMs = 0L
        // 插值基准一并重置：否则新歌第一帧会用上一首的时间差外推
        rawPositionMs = 0L
        rawPositionNanos = System.nanoTime()
        hasPosition = false
        lastPublishedMs = -1L
        currentLine = -1
        // 艺术家图与两套候选都属于「上一首」，不清会残留在新歌上：
        // 新歌本地没艺术家图时，两个小槽会继续显示上一首选中的那张。
        artistCandidate = null
        artistSlots = listOf(null, null)
        networkCovers = emptyList()
        networkArtistCovers = emptyList()
        // 切歌：正在渲染的频谱作废，旧结果不能留在新歌上
        Spectrogram.cancel()
        spectrogram = SpectrogramState.Idle
        loadLyrics(meta)
        loadCovers(meta)
    }

    /** 当前界面聚焦的曲目：优先用户手选，否则跟随正在播放。 */
    fun focused(): TrackMeta = selected ?: track

    // ── 歌词 ────────────────────────────────────────────────────────────────
    fun loadLyrics(meta: TrackMeta) {
        val token = Epoch.nextLyric()
        if (!meta.hasFile) {
            lyricsLoading = false
            lines = emptyList()
            editedLines = emptyList()
            lyricBase = ""
            lyricText = ""
            lyricSource = ""
            return
        }
        lyricsLoading = true
        scope.launch {
            val raw = runCatching { Tags.readLyrics(meta.path) }.getOrNull()
            // 必须走后端的 LyricsFormat.parse：它按格式分派，
            // 能正确处理「逐字 LRC」（一行里每个字各带时间戳）、SPL 与纯文本。
            // 之前这里用的是本地那份简易 parseLrc —— 逐字行会被解析成空文本，
            // 整首歌的歌词看起来就是一片空白。
            val parsed = raw?.let { parseLyrics(it) } ?: emptyList()
            withContext(Dispatchers.Main) {
                lyricsLoading = false
                // 只用 token 防过期：旧做法用 lines.isEmpty() 当守卫，
                // 上一首的歌词还在时新歌永远加载不进来
                if (!Epoch.lyricAlive(token)) return@withContext
                lines = parsed
                editedLines = parsed
                // 源文本原样保留；换了一份歌词就必须重建完整版轨道缓存
                lyricBase = raw.orEmpty()
                lyricText = lyricBase
                lyricsEpoch++
                lyricSource =
                    when {
                        parsed.isEmpty() -> ""
                        raw != null -> "内嵌歌词"
                        else -> ""
                    }
            }
        }
    }

    fun reloadLyrics() {
        loadLyrics(focused())
    }

    /**
     * 编辑框输入：**只写草稿，不改 `lyricText`/`lyricBase`**。
     *
     * 为什么不回写内存基准：编辑框是「非受控」的（自己持有内容与选区），
     * 若每次按键都把文本回写到 `lyricText`，下一帧的渲染会拿外部值参与竞争，
     * 与用户正在输入的本地状态打架，实测被吃成只剩最后一两个字符。
     *
     * 参考项目 SPW-Tag-Workbench 用的是 Swing `JTextArea`（非受控），
     * 只在离开页面时才读一次 `.text`——这里用同一种模型：输入只进草稿，
     * 基准要等保存成功、或用户在设置区做格式转换时才更新。
     */
    fun stageLyricEdit(text: String) {
        val key = focused().key
        if (key.isEmpty()) return
        if (text.isBlank()) {
            Drafts.unstageLyrics(key)
        } else {
            Drafts.stageLyrics(key, text)
        }
    }

    /**
     * 编辑栏改了歌词文本：同步基准 + **写入草稿**。
     *
     * 保留给「需要在编辑后立即让下游（打轴页、格式转换）看到新文本」的场景；
     * 主视图编辑框不走这里，改走 [stageLyricEdit]。
     */
    fun updateLyricText(text: String) {
        // 用户直接编辑的内容就是新的基准：基准变了，其它设置照常叠加
        lyricBase = text
        lyricText = text
        // 进草稿：与其它字段的预览制一致，保存前不落盘
        stageLyricEdit(text)
    }

    // ── 歌词设置（对应候选列表下方的设置区） ─────────────────────────────

    /**
     * 把歌词转换到指定格式。
     *
     * 做法统一为「解析成行 → 再序列化」：只有这样才能保证时间轴不丢，
     * 直接字符串替换是改不了格式结构的。
     *
     * @param label 下拉里显示的名字，与 [LyricsFormat.Format.label] 对应
     */
    fun convertLyricFormat(label: String) {
        val key = focused().key
        if (lyricText.isBlank()) {
            postStatus(Status("没有歌词可转换", Status.Kind.Warn))
            return
        }
        val target = LyricsFormat.Format.entries.firstOrNull { it.label == label }
        if (target == null) {
            postStatus(Status("不支持的格式：$label", Status.Kind.Warn))
            return
        }
        // TTML 只保留解析（读），不提供写出：
        // 它实际只存在于 Apple Music，而取它需要账号凭据，本插件没有合法取源。
        if (target == LyricsFormat.Format.TTML) {
            postStatus(Status("TTML 仅支持读取，暂不支持写出", Status.Kind.Warn))
            return
        }
        // 只记录「选了什么格式」，真正的转换在 rerenderLyrics 里从基准算一遍
        lyricFormatChoice = target.label
        rerenderLyrics()
        postStatus(Status("格式：$label（${lyricText.lineSequence().count { it.isNotBlank() }} 行）", Status.Kind.Ok))
    }

    /** 恢复「不转换格式」，直接显示基准原文。 */
    fun clearLyricFormat() {
        lyricFormatChoice = null
        rerenderLyrics()
        postStatus(Status("格式：原始（不做转换）", Status.Kind.Ok))
    }

    /**
     * 整首歌词时间轴平移。
     *
     * @param deltaMs 相对当前偏移的增量；偏移量本身是**设置值**，不是对文本的累积修改，
     * 所以调到 0 就等于完全没偏移过。
     */
    fun shiftLyrics(deltaMs: Int) {
        if (lyricBase.isBlank()) {
            postStatus(Status("没有歌词可偏移", Status.Kind.Warn))
            return
        }
        lyricOffsetMs += deltaMs
        rerenderLyrics()
        postStatus(Status("偏移：$lyricOffsetMs ms", Status.Kind.Ok))
    }

    /** 把偏移量直接设为 0（等价于没偏移过）。 */
    fun resetLyricOffset() {
        lyricOffsetMs = 0
        rerenderLyrics()
        postStatus(Status("偏移：0 ms", Status.Kind.Ok))
    }

    /**
     * 中文简繁转换。
     *
     * 这是**改基准内容**（不是显示设置）：转换结果写回 [lyricBase]，
     * 其余设置（格式、偏移、轨道）在重渲染时照常叠加。
     */
    fun convertLyricsChinese(traditional: Boolean) {
        if (lyricBase.isBlank()) {
            postStatus(Status("没有歌词可转换", Status.Kind.Warn))
            return
        }
        val converted = ChineseText.convert(lyricBase, traditional)
        if (converted == lyricBase) {
            postStatus(Status("没有需要转换的汉字", Status.Kind.Warn))
            return
        }
        lyricBase = converted
        rerenderLyrics()
        postStatus(
            Status(
                text = if (traditional) "已转为繁体" else "已转为简体",
                kind = Status.Kind.Ok,
            ),
        )
    }

    /**
     * 切换语言轨道（0=原文 / 1=译文 / 2=罗马音）。
     *
     * 只是改开关，然后从基准重渲染——所以关掉再打开一定恢复得回来。
     */
    fun setLyricLanguage(
        track: Int,
        enabled: Boolean,
    ) {
        when (track) {
            0 -> lyricShowOriginal = enabled
            1 -> lyricShowTranslation = enabled
            2 -> lyricShowRomanization = enabled
        }
        rerenderLyrics(post = true)
    }

    /** 把所有设置恢复默认（格式不转、偏移 0、三条轨道按常规显示）。 */
    fun resetLyricSettings() {
        lyricFormatChoice = null
        lyricOffsetMs = 0
        lyricShowOriginal = true
        lyricShowTranslation = true
        lyricShowRomanization = false
        rerenderLyrics()
        postStatus(Status("已恢复默认歌词设置", Status.Kind.Ok))
    }

    /** 按指定毫秒平移歌词里的所有时间戳。 */
    private fun shiftText(
        source: String,
        offsetMs: Int,
    ): String {
        val stamp = Regex("""([\[<])(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?([\]>])""")
        return stamp.replace(source) { m ->
            val open = m.groupValues[1]
            val close = m.groupValues[5]
            val minutes = m.groupValues[2].toLongOrNull() ?: 0L
            val seconds = m.groupValues[3].toLongOrNull() ?: 0L
            val fracText = m.groupValues[4]
            val millis =
                if (fracText.isEmpty()) {
                    0L
                } else {
                    (fracText.toLongOrNull() ?: 0L) *
                        when (fracText.length) {
                            1 -> 100L
                            2 -> 10L
                            else -> 1L
                        }
                }
            val value = (minutes * 60000L + seconds * 1000L + millis + offsetMs).coerceAtLeast(0L)
            buildString {
                append(open)
                append((value / 60000).toString().padStart(2, '0')).append(':')
                append((value / 1000 % 60).toString().padStart(2, '0')).append('.')
                append((value % 1000 / 10).toString().padStart(2, '0'))
                append(close)
            }
        }
    }

    /**
     * 按当前开关过滤语言轨道。
     *
     * 判定方式：逐行拿到行首时间戳，同一时间戳出现次数决定它是第几条轨道。
     * 没有时间戳的行（标题、说明）只在保留原文时输出。
     */
    private fun filterLyricTracks(source: String): String {
        val occurrence = HashMap<String, Int>()
        val out = StringBuilder()
        val stamp = Regex("""^\[(\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?)]""")
        for (line in source.lines()) {
            val m = stamp.find(line)
            if (m == null) {
                if (lyricShowOriginal) out.append(line).append('\n')
                continue
            }
            val key = m.groupValues[1]
            val count = occurrence.getOrDefault(key, 0)
            occurrence[key] = count + 1
            val keep =
                when (count) {
                    0 -> lyricShowOriginal
                    1 -> lyricShowTranslation
                    else -> lyricShowRomanization
                }
            if (keep) out.append(line).append('\n')
        }
        return out.toString().trimEnd()
    }

    fun saveLyrics(): Tags.Result {
        val meta = focused()
        if (!meta.hasFile) {
            return Tags.Result(false, "曲目没有可写文件").also {
                postStatus(Status(it.message, Status.Kind.Error))
            }
        }
        // **直接写源文本**，不经过 buildLrc 之类的重组。
        //
        // 歌词标签是「文本」，不是结构化数据：里面可能带逐字时间、带翻译轨道、
        // 带用户手写的格式，任何一次 parse→serialize 都会损耗（之前就是把
        // 每行的时间戳丢光，只剩纯文字）。除非用户在设置区显式做了格式转换，
        // 否则这里应该原样写入。
        val text =
            lyricText.ifBlank {
                buildLrc(editedLines)
            }
        val result = Tags.writeLyrics(meta.path, text, reloadLyricsForId = lyricReloadTarget(meta))
        postStatus(
            Status(
                result.message,
                if (result.ok) Status.Kind.Ok else Status.Kind.Error,
            ),
        )
        return result
    }

    // ── 封面 ────────────────────────────────────────────────────────────────
    fun loadCovers(meta: TrackMeta) {
        val token = Epoch.nextCover()
        // 只负责"当前封面"的展示。本地/同目录候选逻辑已取消：// 候选区只显示网络多源结果。
        if (meta.path.isBlank()) {
            cover = null
            coverLoading = false
            artistSlots = listOf(null, null)
            selectedCoverId = null
            return
        }

        coverLoading = true
        scope.launch {
            val bytes = runCatching { Tags.readEmbeddedCover(meta.path) }.getOrNull()
            val image = bytes?.let { Host.decodeImage(it) }
            val coverInfo = bytes?.let { infoOf(it) }.orEmpty()
            // 本地艺术家图（type=8/7/10）：作为艺术家页左栏的种子
            // 两个小槽只做展示：本地有艺术家图就显示本地的，
            // 否则显示艺术家匹配页里已写入草稿的那张
            val slots = buildArtistSlots(meta)
            withContext(Dispatchers.Main) {
                coverLoading = false
                if (!Epoch.coverAlive(token)) return@withContext
                cover = image
                localCoverInfo = coverInfo
                artistSlots = slots
            }
        }
    }

    /**
     * 封面候选是否「相关」。
     *
     * 比歌词那套 level > 0 宽松：
     * - 标题精确 / 归一化命中（level 1~2）→ 直接认；
     * - 标题只是相似（level 3~4）→ 还要求艺术家兼容。
     *
     * 这样 iTunes 用英文艺人名（Pu Shu 对 朴树）也能给出正确封面，
     * 同时挡掉「标题相似但其实是另一首歌」的图。
     */
    private fun coverRelevant(
        query: SearchQuery,
        candidate: io.github.univers629.spwtagworkbench.backend.source.SourceCandidate,
    ): Boolean {
        val titleLevel = Matching.titleLevel(query.title, candidate.title)
        if (titleLevel == 0) return false
        if (titleLevel <= 2) return true
        return io.github.univers629.spwtagworkbench.backend.source.CandidateScoring
            .artistCompatible(query.artist, candidate.artist)
    }

    /** 图片的「分辨率 · 体积」描述。 */
    private fun infoOf(bytes: ByteArray): String {
        val (w, h) = Host.imageSize(bytes)
        return infoOfSize(w, h, bytes.size)
    }

    /** 由已知尺寸与字节数拼「分辨率 · 体积」，与 [infoOf] 同格式。 */
    private fun infoOfSize(
        w: Int,
        h: Int,
        bytes: Int,
    ): String {
        val kb = bytes / 1024.0
        val size =
            if (kb >= 1024) {
                String.format(java.util.Locale.ROOT, "%.1fMB", kb / 1024)
            } else {
                String.format(java.util.Locale.ROOT, "%.0fKB", maxOf(1.0, kb))
            }
        return if (w > 0 && h > 0) "$w × ${h}px·$size" else size
    }

    /**
     * 主封面下方两个小槽的展示数据。
     *
     * 只负责「显示」：本地标签里有艺术家类图片就显示本地的，
     * 否则显示艺术家页里**已写入草稿**的那张（[artistCandidate] 只作兜底，
     * 用来让已应用的预览在重新读盘后仍然留得住）。
     * 真正的检索与挑选在 [loadNetworkArtistCovers] / [chooseArtistCover]。
     */
    private fun buildArtistSlots(meta: TrackMeta): List<CoverSlot?> {
        val pictures = runCatching { CoverStore.readPictures(meta.path) }.getOrDefault(emptyList())
        val localArtistPics =
            PictureTypeStandard.ARTIST_CHAIN
                .mapNotNull { type -> pictures.firstOrNull { it.type == type } }
        if (localArtistPics.isNotEmpty()) {
            Diag.log("artistSlots: 用本地图 ${localArtistPics.size} 张")
            return List(SLOT_COUNT) { i -> localArtistPics.getOrNull(i)?.toSlot() }
        }
        val chosen = artistCandidate
        if (chosen != null) {
            return listOf(
                CoverSlot(
                    label = "艺术家",
                    description = chosen.origin,
                    mime = "",
                    bytes = chosen.bytes?.size ?: 0,
                    width = chosen.width,
                    height = chosen.height,
                    image = chosen.image,
                ),
                null,
            )
        }
        return listOf(null, null)
    }

    /** 把一个内嵌图片转成槽位（含分辨率与字节数，界面要显示）。 */
    private fun EmbeddedPicture.toSlot(): CoverSlot {
        val decoded = Host.decodeImage(bytes)
        val dim = Host.imageSize(bytes)
        return CoverSlot(
            label = PictureTypeStandard.label(type),
            description = description,
            mime = mime,
            bytes = bytes.size,
            width = dim.first,
            height = dim.second,
            image = decoded,
        )
    }

    /**
     * 勾选一张候选封面：**只改勾选，不动预览**。
     *
     * 预览（左栏与主视图大图）只在 [applyCover]（写入草稿）时才换 ——
     * 于是「点一下候选就把左边换掉」不会再发生。
     */
    fun selectCover(id: String) {
        selectedCoverId = id
    }
    // ── 导入 / 导出 ─────────────────────────────────────────────────────────

    /**
     * 从文件导入一张图。
     *
     * - 封面：导入的图**不进候选网格**（网格只显示网络结果），所以直接按
     *   「写入草稿」处理 —— 进草稿并换预览；否则用户点完导入看不到任何变化。
     * - 艺术家图：只勾选，左栏预览与两个小槽等「写入草稿」才换。
     *
     * 读文件与解码放 IO 线程，避免大图卡界面。
     */
    fun importImage(
        path: String,
        artistMode: Boolean,
    ) {
        if (path.isBlank()) {
            return
        }
        scope.launch {
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
            val image = bytes?.let { Host.decodeImage(it) }
            withContext(Dispatchers.Main) {
                if (bytes == null || image == null) {
                    postStatus(Status("导入失败：不是可识别的图片", Status.Kind.Error))
                    return@withContext
                }
                val candidate =
                    CoverCandidate(
                        id = "import:${System.nanoTime()}",
                        bytes = bytes,
                        image = image,
                        sourceLabel = "导入",
                        width = image.width,
                        height = image.height,
                        pictureType =
                            if (artistMode) {
                                PictureTypeStandard.ARTIST
                            } else {
                                PictureTypeStandard.FRONT_COVER
                            },
                    )
                if (artistMode) {
                    artistCandidate = candidate
                    postStatus(
                        Status(
                            "已勾选导入的图 ${image.width}×${image.height}，点「写入草稿」才会换预览",
                            Status.Kind.Busy,
                        ),
                    )
                } else {
                    val meta = focused()
                    if (!meta.hasFile) {
                        // 导入会直接进草稿，没文件可写时不该留下悬空草稿
                        postStatus(Status("曲目没有可写文件，未导入", Status.Kind.Error))
                        return@withContext
                    }
                    importedCovers = importedCovers + candidate
                    rebuildCandidates()
                    selectedCoverId = candidate.id
                    stageCoverFor(meta = meta, bytes = bytes, image = image)
                    postStatus(Status("已导入并进草稿 ${image.width}×${image.height}，点保存才写文件", Status.Kind.Ok))
                }
            }
        }
    }

    /**     * 把当前勾选的那张图导出到文件。     *     * 导出的是**候选的原始字节**，不重压缩 —— 重压缩只发生在写标签时。     */
    fun exportCover(
        path: String,
        artistMode: Boolean,
    ): Boolean {
        val bytes =
            if (artistMode) {
                artistCandidate?.bytes
            } else {
                selectedCover?.bytes
            }
        if (bytes == null) {
            postStatus(Status("没有可导出的图", Status.Kind.Warn))
            return false
        }
        return try {
            java.io.File(path).writeBytes(bytes)
            postStatus(Status("已导出到 ${java.io.File(path).name}", Status.Kind.Ok))
            true
        } catch (t: Throwable) {
            postStatus(Status("导出失败：${t.message.orEmpty().take(40)}", Status.Kind.Error))
            false
        }
    }
    // ── 参与源状态（页面顶部显示「支持的源」用）────────────────────────────────

    /** * 封面页每个参与源的状态：搜索中 / 已出 N 张 / 无结果。     *     * 直接从流式槽位反推：槽位本来就是「每源 3 格」，     * 能准确反映各源进展，不用另存一份状态。     */
    fun coverSourceStates(): List<Pair<String, String>> =
        aggregator.coverSources().map { src ->
            val tiles =
                networkCovers.filter { tile ->
                    when (tile) {
                        is CoverTile.Searching -> tile.sourceId == src.id
                        is CoverTile.Ready -> tile.candidate.id.startsWith("net:${src.id}:")
                        is CoverTile.Empty -> tile.label == src.label
                    }
                }
            val text =
                when {
                    tiles.any { it is CoverTile.Searching } -> "搜索中"
                    else -> {
                        val count = tiles.count { it is CoverTile.Ready }
                        if (count > 0) "$count 张" else "无"
                    }
                }
            src.label to text
        }

    /** 歌词页每个参与源的状态：搜索中 / 已出 N 条 / 失败 / 无结果。 */
    fun lyricSourceStates(): List<Pair<String, String>> {
        val state = lyricMatch
        return aggregator.lyricSources().map { src ->
            val text =
                when (state) {
                    is LyricMatch.Searching -> "搜索中"
                    is LyricMatch.Done -> {
                        val group = state.groups.firstOrNull { it.sourceId == src.id }
                        when {
                            group == null -> "无"
                            group.failure != null -> "失败"
                            group.items.isEmpty() -> "无"
                            else -> "${group.items.size} 条"
                        }
                    }
                    is LyricMatch.Failed -> "失败"
                    LyricMatch.Idle -> "—"
                }
            src.label to text
        }
    }

    /** 艺术家图页每个参与源的状态。 */
    fun artistSourceStates(): List<Pair<String, String>> =
        aggregator.artistSources().map { src ->
            val tiles =
                networkArtistCovers.filter { tile ->
                    when (tile) {
                        is CoverTile.Searching -> tile.sourceId == src.id
                        is CoverTile.Ready -> tile.candidate.id.startsWith("artist:${src.id}:")
                        is CoverTile.Empty -> tile.label == src.label
                    }
                }
            val text =
                when {
                    tiles.any { it is CoverTile.Searching } -> "搜索中"
                    else -> {
                        val count = tiles.count { it is CoverTile.Ready }
                        if (count > 0) "$count 张" else "无"
                    }
                }
            src.label to text
        }

    /**
     * 把**勾选**的那张候选封面写进草稿（type=3）。
     *
     * 只进草稿、**不落盘**：真正写文件只发生在 [saveDrafts]。
     * 这样预览制才成立——分页里换来换去都只是内存里的草稿，
     * 用户点「保存」才动磁盘。
     */
    fun applyCover(): Tags.Result {
        val meta = focused()
        val target =
            selectedCover
                ?: return Tags.Result(false, "没有勾选的候选封面").also {
                    postStatus(Status(it.message, Status.Kind.Error))
                }
        val bytes =
            target.bytes
                ?: return Tags.Result(false, "候选封面没有数据").also {
                    postStatus(Status(it.message, Status.Kind.Error))
                }
        if (!meta.hasFile) {
            return Tags.Result(false, "曲目没有可写文件").also {
                postStatus(Status(it.message, Status.Kind.Error))
            }
        }
        stageCoverFor(meta, bytes, target.image)
        postStatus(
            Status(
                "封面已进草稿（${target.origin}），点保存才写文件",
                Status.Kind.Ok,
            ),
        )
        return Tags.Result(true, "已进草稿")
    }

    /**
     * 预览位图的最长边上限。
     *
     * 候选网格用 512px 降采样（几十张同时驻留，省内存），但左栏与主视图大图
     * 比它大，直接用网格图会发虚；所以**写入草稿**时按这个上限单独重解一张，
     * 同一时刻只有一张，内存可接受。
     */
    private const val PREVIEW_MAX_EDGE = 1024

    /**
     * 把一张图写进封面草稿，并把它换成左栏 / 主视图的预览。
     *
     * 预览先上候选网格里那张 512px 降采样图（已在内存，立刻可见），
     * 随后在 IO 线程按 [PREVIEW_MAX_EDGE] 重解一张替换上去 ——
     * 左栏与主视图大图都比 512px 大，直接用网格图会发虚。
     * 重解完成时若已切到别的曲目，结果直接丢弃。
     */
    private fun stageCoverFor(
        meta: TrackMeta,
        bytes: ByteArray,
        image: ImageBitmap?,
    ) {
        Drafts.stageCover(meta.key, bytes)
        cover = image
        localCoverInfo = infoOf(bytes)
        val key = meta.key
        val gen = ++coverPreviewGen
        scope.launch {
            val sharp =
                withContext(Dispatchers.IO) {
                    runCatching { Host.decodeImageSampled(bytes, PREVIEW_MAX_EDGE) }.getOrNull()
                }
            if (sharp != null && gen == coverPreviewGen && focused().key == key) {
                withContext(Dispatchers.Main) { cover = sharp }
            }
        }
    }

    // ── 标签写入 ─────────────────────────────────────────────────────────────
    fun saveField(
        key: Tags.Key,
        value: String,
    ): Tags.Result {
        val meta = focused()
        if (!meta.hasFile) {
            val result = Tags.Result(false, "曲目没有可写文件")
            postStatus(Status(result.message, Status.Kind.Error))
            return result
        }
        val result = Tags.writeField(meta.path, key, value, reloadLyricsForId = lyricReloadTarget(meta))
        postStatus(
            Status(
                result.message,
                if (result.ok) Status.Kind.Ok else Status.Kind.Error,
            ),
        )
        if (result.ok) {
            val updated =
                when (key) {
                    Tags.Key.Title -> meta.copy(title = value)
                    Tags.Key.Artist -> meta.copy(artist = value)
                    Tags.Key.Album -> meta.copy(album = value)
                    Tags.Key.AlbumArtist -> meta.copy(albumArtist = value)
                    Tags.Key.Genre -> meta.copy(genre = value)
                    Tags.Key.Year -> meta.copy(year = value)
                }
            if (selected?.key == meta.key) selected = updated
            if (track.key == meta.key) track = updated
            library =
                library.map {
                    if (it.key == meta.key) updated else it
                }
        }
        return result
    }
    // ── 频谱 ────────────────────────────────────────────────────────────────

    /** 当前正在分析的曲目路径，用于避免重复触发。 */
    var spectrumPath: String = ""
        private set

    fun analyze(meta: TrackMeta = focused()) {
        // 幂等：同一首已经在分析时忽略重复请求。
        // 之前页面切换会反复触发，导致频谱图闪几次。
        if (spectrumPath == meta.path && spectrogram is SpectrogramState.Working) {
            Diag.log("spectrum: 忽略重复请求（同一首正在分析）")
            return
        }
        spectrumPath = meta.path
        val token = Epoch.nextSpectrum()
        if (!meta.hasFile) {
            spectrogram = SpectrogramState.Failed("曲目没有可读文件")
            spectrum = SpectrumState.Failed("曲目没有可读文件")
            return
        }
        Spectrogram.cancel()
        spectrogram = SpectrogramState.Working(0f, null, 2, meta.durationMs)
        spectrum = SpectrumState.Running(0f)
        scope.launch {
            Spectrogram.render(
                meta.path,
                object : Spectrogram.Listener {
                    override fun onProgress(result: Spectrogram.Result) {
                        if (!Epoch.spectrumAlive(token)) {
                            return
                        }
                        postOnMain {
                            if (!Epoch.spectrumAlive(token)) return@postOnMain
                            spectrogram =
                                SpectrogramState.Working(
                                    progress = result.progress,
                                    image = result.image,
                                    displayChannels = result.displayChannels,
                                    durationMs = result.durationMs,
                                    sampleRate = result.sampleRate,
                                )
                            spectrum =
                                SpectrumState.Partial(
                                    channels = listOf(SpectrumChannel("包络", result.envelope)),
                                    progress = result.progress,
                                )
                        }
                    }

                    override fun onFinished(result: Spectrogram.Result?) {
                        if (!Epoch.spectrumAlive(token)) {
                            return
                        }
                        postOnMain {
                            if (!Epoch.spectrumAlive(token)) return@postOnMain
                            if (result == null) {
                                spectrogram = SpectrogramState.Failed("已取消")
                                return@postOnMain
                            }
                            spectrogram =
                                SpectrogramState.Ready(
                                    image = result.image,
                                    displayChannels = result.displayChannels,
                                    envelope = result.envelope,
                                    durationMs = result.durationMs,
                                )
                            spectrum =
                                SpectrumState.Ready(
                                    channels = listOf(SpectrumChannel("包络", result.envelope)),
                                    derived = false,
                                )
                        }
                    }

                    override fun onFailed(failure: Spectrogram.Failure) {
                        if (!Epoch.spectrumAlive(token)) {
                            return
                        }
                        postOnMain {
                            if (!Epoch.spectrumAlive(token)) return@postOnMain
                            spectrogram = SpectrogramState.Failed(failure.reason)
                            spectrum = SpectrumState.Failed(failure.reason)
                        }
                    }
                },
            )
        }
    }

    /** 取消频谱渲染（切歌 / 离页时调用）。 */
    fun cancelSpectrum() {
        Spectrogram.cancel()
        spectrogram = SpectrogramState.Idle
    }

    // ── 播放控制 ─────────────────────────────────────────────────────────────
    fun seekTo(ms: Long) {
        runCatching { WorkshopApi.playback.seekTo(ms) }
        positionMs = ms
    }

    fun seekToLine(ms: Long) = seekTo(ms)

    fun togglePlay() {
        runCatching {
            if (playing) WorkshopApi.playback.pause() else WorkshopApi.playback.play()
        }
    }

    // ── 批量写入 ─────────────────────────────────────────────────────────────
    private val batchWriter by lazy { BatchWriter(scope) }
    val batchProgress: StateFlow<BatchProgress> get() = batchWriter.progress

    /** 最近一次批量结果，UI 用它给出可见终态。 */
    var batchReport by mutableStateOf<BatchReport?>(null)
        private set

    /**     * 把当前曲目的元数据批量写入同专辑的所有曲目。     *     * 走 BatchWriter：进度、取消、单点失败隔离都由它保证；     * 伴奏文件由匹配层剔除，不会把演唱版数据写进去。     */
    fun writeAlbumBatch(fields: List<Tags.Key>) {
        val source = focused()
        if (!source.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        val targets =
            library
                .filter {
                    it.hasFile &&
                        it.album.equals(source.album, ignoreCase = true) &&
                        it.album.isNotBlank()
                }.map { it.toBackendTrack() }
        if (targets.isEmpty()) {
            postStatus(Status("没有同专辑的曲目可写", Status.Kind.Warn))
            return
        }
        batchReport = null
        postStatus(Status("开始批量写入 ${targets.size} 首…", Status.Kind.Busy))
        batchWriter.start(
            tracks = targets,
            patchFor = { track ->
                val patch = TagPatch()
                for (key in fields) {
                    val value =
                        when (key) {
                            Tags.Key.Title -> ""
                            // 标题不批量套用
                            Tags.Key.Artist -> source.artist
                            Tags.Key.Album -> source.album
                            Tags.Key.AlbumArtist -> source.albumArtist
                            Tags.Key.Genre -> source.genre
                            Tags.Key.Year -> source.year
                        }
                    if (value.isNotBlank()) patch.set(key.field, value)
                }
                patch
            },
            reloadLyricsForId = lyricReloadTarget(source),
        ) { report ->
            batchReport = report
            postStatus(
                Status(
                    text = "批量写入：${report.summary}",
                    kind =
                        if (report.ok) Status.Kind.Ok else Status.Kind.Warn,
                ),
            )
            if (report.ok) refreshLibrary()
        }
    }

    fun cancelBatch() {
        batchWriter.cancel()
        postStatus(Status("已取消批量写入", Status.Kind.Warn))
    }

    private fun TrackMeta.toBackendTrack(): Track =
        Track(
            id = id,
            path = path,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            readable = readable,
        )

    /** 只有写的是正在播放的歌，才需要重载宿主歌词。 */
    private fun lyricReloadTarget(meta: TrackMeta): String? =
        if (meta.key.isNotEmpty() && meta.key == track.key) meta.id.ifEmpty { null } else null

    // ── 在线聚合源 ───────────────────────────────────────────────────────────
    private val itunes = ItunesSource()
    private val lrclib = LrclibSource()
    private val netease = NeteaseSource()
    private val qqmusic = QqMusicSource()
    private val kugou = KugouSource()
    private val kuwo = KuwoSource()
    private val sources: List<MusicSource> =
        listOf(itunes, lrclib, netease, qqmusic, kugou, kuwo)
    private val aggregator = Aggregator(sources)

    /** 元数据匹配状态。 */
    var metaMatch by mutableStateOf<MetaMatch>(MetaMatch.Idle)
        private set

    // ── 艺术家图候选（与专辑封面完全独立的端点与流程）─────────────────────────

    /** 各源艺术家图槽位（流式填充）。 */
    var networkArtistCovers by mutableStateOf<List<CoverTile>>(emptyList())
        private set

    /** 已选中的艺术家图（下面两个小槽显示的就是它）。 */
    var artistCandidate by mutableStateOf<CoverCandidate?>(null)
        private set
    var artistLoading by mutableStateOf(false)
        private set

    /**     * 流式装载艺术家图候选。     *     * 与 [loadNetworkCovers] 是**两套逻辑**：这里走 [MusicSource.artistCover]，     * 端点完全不同（QQ `T001`、酷狗 `softhead`、网易云 `img1v1Url`）。     */
    fun loadNetworkArtistCovers() {
        val meta = focused()
        if (!meta.isPresent) return
        val token = Epoch.nextOnline()
        val query = SearchQuery(meta.title, meta.artist, meta.album, meta.durationMs)
        val usable = aggregator.artistSources()
        // 流式模式：每源一个「搜索中」占位，返回即替换（与封面页一致）
        networkArtistCovers = usable.map { source -> CoverTile.Searching(source.id, source.label, 1) }
        artistLoading = true
        scope.launch {
            kotlinx.coroutines.coroutineScope {
                usable.forEach { source ->
                    launch(Dispatchers.IO) {
                        val group = runCatching { aggregator.groupFor(source, query) }.getOrNull()
                        // 与封面页同样的放宽逻辑：只看标题对得上，不套 level > 0
                        val pool =
                            group
                                ?.items
                                ?.filter {
                                    it.candidate.artistArtworkUrl.isNotBlank() &&
                                        coverRelevant(query, it.candidate)
                                }.orEmpty()
                        Diag.log(
                            "artistCovers: ${source.label} 搜索结果=" + (group?.items?.size ?: 0) +
                                " 可下载=" + pool.size,
                        )
                        // 并行下载
                        val downloaded =
                            pool
                                .take(6)
                                .map { item ->
                                    async(Dispatchers.IO) {
                                        val bytes =
                                            runCatching {
                                                source.artistCover(item.candidate)
                                            }.getOrNull() ?: return@async null
                                        // 尺寸过滤用**原始尺寸**（只读头部），
                                        // 降采样位图的尺寸不能用于最低分辨率判断
                                        val (origW, origH) = Host.imageSize(bytes)
                                        if (origW <= 0 || origH <= 0) return@async null
                                        val shortSide = minOf(origW, origH)
                                        // 艺人照不一定是正方形（网易云 640×420），下限比封面宽松
                                        if (shortSide < CoverCandidate.ARTIST_MIN_SHORT_SIDE) {
                                            Diag.log(
                                                "artistCovers: 跳过 ${source.label} " +
                                                    "${origW}x$origH",
                                            )
                                            return@async null
                                        }
                                        // **降采样解码**（同封面候选：几十张驻留时
                                        // 全解位图会打穿宿主软堆上限）
                                        val image =
                                            Host.decodeImageSampled(bytes) ?: return@async null
                                        CoverCandidate(
                                            id = "artist:${source.id}:${item.candidate.remoteId}",
                                            bytes = bytes,
                                            image = image,
                                            sourceLabel = source.label,
                                            width = origW,
                                            height = origH,
                                            pictureType = PictureTypeStandard.ARTIST,
                                        )
                                    }
                                }.awaitAll()
                        val picks = downloaded.filterNotNull()
                        // 流式上屏：这个源一返回就立刻补进去，不等其它源
                        withContext(Dispatchers.Main) {
                            if (!Epoch.onlineAlive(token)) return@withContext
                            val slots = mutableListOf<CoverTile>()
                            picks.forEach { slots += CoverTile.Ready(it) }
                            if (slots.isEmpty()) slots += CoverTile.Empty(source.label)
                            networkArtistCovers =
                                networkArtistCovers
                                    .filterNot { belongsToSource(it, source.id, source.label, "artist") }
                                    .let { rest -> rest + slots }
                            networkArtistCovers = sortSlots(networkArtistCovers)
                            Diag.log("artistCovers: ${source.label} 流式上屏=" + picks.size)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                artistLoading = false
                // 不再默认选中第一张：候选要用户自己挑，避免误写
                val ready = networkArtistCovers.filterIsInstance<CoverTile.Ready>()
                if (ready.isEmpty()) {
                    postStatus(Status("各源都没有可用的艺术家图", Status.Kind.Warn))
                }
            }
        }
    }

    /** 选中一张艺术家图（只改勾选，写入另给入口）。 */
    fun chooseArtistCover(candidate: CoverCandidate) {
        artistCandidate = candidate
        // **只改勾选，不动预览**：左栏与主视图两个小槽要等 [applyArtistCover] 才换
        postStatus(Status("已勾选 ${candidate.origin} ${candidate.info}", Status.Kind.Busy))
    }

    /**
     * 把选中的艺术家图写进草稿。
     *
     * 用 `type=8`（Artist），与专辑封面 `type=3` 分开存 ——
     * ID3v2 的 APIC / FLAC 的 PICTURE 允许多张不同类型共存，不会互相覆盖。
     * 只进草稿、不落盘；真正写文件在 [saveDrafts]。
     */
    fun applyArtistCover(): Tags.Result {
        val meta = focused()
        val chosen =
            artistCandidate
                ?: return Tags.Result(false, "没有选中的艺术家图").also {
                    postStatus(Status(it.message, Status.Kind.Error))
                }
        val bytes =
            chosen.bytes
                ?: return Tags.Result(false, "艺术家图数据为空").also {
                    postStatus(Status(it.message, Status.Kind.Error))
                }
        Drafts.stageArtistCover(meta.key, bytes)
        stageArtistPreview(meta, chosen, bytes)
        postStatus(
            Status(
                "艺术家图已进草稿（${chosen.origin}），点保存才写文件",
                Status.Kind.Ok,
            ),
        )
        return Tags.Result(true, "已进草稿")
    }

    /**
     * 把艺术家图换成预览：左栏与主视图两个小槽都读 [artistSlots]，改它就够。
     *
     * 与封面同样的两段式：先上网格图，再在 IO 线程重解一张更清晰的替换。
     * 重解完成时若已切歌，结果丢弃。
     */
    private fun stageArtistPreview(
        meta: TrackMeta,
        chosen: CoverCandidate,
        bytes: ByteArray,
    ) {
        artistSlots = artistSlotOf(chosen, chosen.image)
        val key = meta.key
        val gen = ++artistPreviewGen
        scope.launch {
            val sharp =
                withContext(Dispatchers.IO) {
                    runCatching { Host.decodeImageSampled(bytes, PREVIEW_MAX_EDGE) }.getOrNull()
                }
            if (sharp != null && gen == artistPreviewGen && focused().key == key) {
                withContext(Dispatchers.Main) { artistSlots = artistSlotOf(chosen, sharp) }
            }
        }
    }

    private fun artistSlotOf(
        chosen: CoverCandidate,
        image: ImageBitmap?,
    ): List<CoverSlot?> =
        listOf(
            CoverSlot(
                label = "艺术家",
                description = chosen.origin,
                mime = "",
                bytes = chosen.bytes?.size ?: 0,
                width = chosen.width,
                height = chosen.height,
                image = image,
            ),
            null,
        )

    /** 用当前曲目搜索在线候选。 */
    fun searchOnline() {
        val meta = focused()
        if (!meta.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        val token = Epoch.nextOnline()
        // 失败时状态条据此判断"离线"还是"无结果"
        kotlin.concurrent.thread(isDaemon = true) {
            io.github.univers629.spwtagworkbench.backend.net.NetProbe
                .probe()
        }
        metaMatch = MetaMatch.Searching
        scope.launch {
            val result =
                aggregator.search(
                    query =
                        SearchQuery(
                            title = meta.title,
                            artist = meta.artist,
                            album = meta.album,
                            durationMs = meta.durationMs,
                        ),
                    capability = Capability.META,
                )
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                metaMatch =
                    if (result.candidates.isEmpty() && result.failures.isNotEmpty()) {
                        MetaMatch.Failed(result.failures.joinToString("；") { "${it.first}: ${it.second}" })
                    } else {
                        MetaMatch.Done(result)
                    }
                val weak = result.candidates.count { it.needsConfirm }
                postStatus(
                    Status(
                        text =
                            "在线匹配：${result.candidates.size} 个候选" +
                                if (result.failures.isNotEmpty()) {
                                    "，${result.failures.size} 个源失败（${result.failures.first().first}: ${result.failures.first().second.take(
                                        24,
                                    )}）"
                                } else {
                                    ""
                                } +
                                if (weak > 0) "，$weak 个需确认" else "",
                        kind =
                            if (result.candidates.isEmpty()) Status.Kind.Warn else Status.Kind.Ok,
                    ),
                )
            }
        }
    }

    /** 把选中的候选字段写进草稿（预览，不落盘）。 */
    fun applyCandidate(ranked: RankedCandidate) {
        val meta = focused()
        if (!meta.isPresent) return
        val key = meta.key
        if (ranked.candidate.title.isNotBlank()) Drafts.stageField(key, TagField.Title, ranked.candidate.title)
        if (ranked.candidate.artist.isNotBlank()) Drafts.stageField(key, TagField.Artist, ranked.candidate.artist)
        if (ranked.candidate.album.isNotBlank()) Drafts.stageField(key, TagField.Album, ranked.candidate.album)
        postStatus(Status("已把「${ranked.candidate.display}」写入待保存", Status.Kind.Busy))
    }

    /**     * 取候选的在线歌词。     *     * 回退链是显式的：     * 1. 候选自身的源（同源优先，避免拿 A 源候选去 B 源要歌词）     * 2. 其余有歌词能力的源，按权重从高到低     * 3. 都没有 → 明确提示"无歌词"，不静默     */
    private suspend fun fetchLyrics(ranked: RankedCandidate): Pair<String?, String?> {
        val own =
            sources.firstOrNull {
                it.id == ranked.candidate.sourceId && it.supports(Capability.LYRICAL)
            }
        if (own != null) {
            val text =
                try {
                    own.lyrics(ranked.candidate)
                } catch (t: Throwable) {
                    Diag.log("lyrics: ${own.label} 抛异常 ${t.javaClass.simpleName}")
                    null
                }
            Diag.log("lyrics: 同源 ${own.label} -> ${if (text.isNullOrBlank()) "无" else "${text.length}B"}")
            if (!text.isNullOrBlank()) {
                return text to own.label
            }
        }
        val others =
            sources
                .filter { it.supports(Capability.LYRICAL) && it.id != ranked.candidate.sourceId }
                .sortedByDescending { it.weight }
        for (source in others) {
            val text =
                try {
                    source.lyrics(ranked.candidate)
                } catch (t: Throwable) {
                    Diag.log("lyrics: ${source.label} 抛异常 ${t.javaClass.simpleName}")
                    null
                }
            Diag.log("lyrics: 回退 ${source.label} -> ${if (text.isNullOrBlank()) "无" else "${text.length}B"}")
            if (!text.isNullOrBlank()) {
                return text to source.label
            }
        }
        return null to null
    }

    /** 取候选的在线歌词，进草稿并加载到编辑区预览。 */
    fun loadOnlineLyrics(ranked: RankedCandidate) {
        val meta = focused()
        if (!meta.isPresent) return
        val token = Epoch.nextOnline()
        scope.launch {
            val (text, label) = fetchLyrics(ranked)
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                if (text.isNullOrBlank()) {
                    postStatus(Status("该候选没有可用歌词（已问过 ${sources.count { it.supports(Capability.LYRICAL) }} 个歌词源）", Status.Kind.Warn))
                    return@withContext
                }
                Drafts.stageLyrics(meta.key, text)
                val parsed = parseLyrics(text)
                editedLines =
                    parsed.ifEmpty {
                        listOf(LyricLine(startMs = 0L, text = text.lineSequence().firstOrNull().orEmpty()))
                    }
                lines = editedLines
                // 在线取回的原始 LRC 就是新的源文本；默认按源文本显示，用户点「解析」再看时间轴
                lyricBase = text
                lyricText = text
                lyricsEpoch++
                lyricSource = "在线 · ${label ?: "未知源"}"
                postStatus(Status("在线歌词已加载（来源：${label ?: "未知"}），保存后写入", Status.Kind.Ok))
            }
        }
    }

    /**
     * 网络封面流式载入。
     *
     * 对齐参考项目：每个源预留 3 个槽位，先放「正在搜索 i/3」占位；
     * 该源返回后原地替换成图片或「无更多匹配」占位，并重新排序。
     * 结果边到边出现，不等全部完成。
     */
    fun loadNetworkCovers() {
        val meta = focused()
        if (!meta.isPresent) {
            return
        }
        // 防重复：同一首歌已经在加载中就不再发起
        if (coverLoading && lastCoverKey == meta.key) {
            Diag.log("covers: 已在加载中，跳过重复触发 key=${meta.key.take(12)}")
            return
        }
        lastCoverKey = meta.key
        val token = Epoch.nextOnline()
        val query = SearchQuery(meta.title, meta.artist, meta.album, meta.durationMs)
        val usable = aggregator.coverSources()
        // 流式模式：每个源先放一个「搜索中」占位，返回后原地换成真实图。
        // 不再像旧实现那样预留 3 个槽位——那会让网格一开始就铺满空格，
        // 而且限制了每源最多 3 张。
        networkCovers = usable.map { source -> CoverTile.Searching(source.id, source.label, 1) }
        netCandidates = emptyList()
        rebuildCandidates()
        coverLoading = true
        Diag.log(
            "covers: 开始检索 key=${meta.key.take(12)} 源=" + usable.joinToString(",") { it.label },
        )
        scope.launch {
            kotlinx.coroutines.coroutineScope {
                usable.forEach { source ->
                    launch(Dispatchers.IO) {
                        val group =
                            try {
                                aggregator.groupFor(source, query)
                            } catch (t: Throwable) {
                                null
                            }
                        // 封面候选的筛选标准比歌词宽：**只看标题是否对得上**。
                        // 不能套 level > 0 —— 那一层要求艺术家的中文/英文写法也兼容，
                        // 于是 iTunes 的「Pu Shu」对上本地的「朴树」会直接判 0，
                        // 明明封面是对的却被丢掉。时长同理：Live 版封面往往就是同一张。
                        val pool =
                            group
                                ?.items
                                ?.filter { coverRelevant(query, it.candidate) }
                                .orEmpty()
                        Diag.log(
                            "covers: ${source.label} 搜索结果=" + (group?.items?.size ?: 0) +
                                " 可下载=" + pool.size,
                        )
                        // 并行下载：谁先回谁先用，并发由 NetGate 统一收口。
                        val downloaded =
                            pool
                                .take(PER_SOURCE_PICKS)
                                .map { item ->
                                    async(Dispatchers.IO) {
                                        val bytes =
                                            try {
                                                source.cover(item.candidate)
                                            } catch (t: Throwable) {
                                                null
                                            } ?: return@async null
                                        // 尺寸过滤用**原始终身**（imageSize 只读头部不解码），
                                        // 降采样后的位图尺寸变小，不能拿来做最低分辨率判断
                                        val (origW, origH) = Host.imageSize(bytes)
                                        if (origW <= 0 || origH <= 0) return@async null
                                        val shortSide = minOf(origW, origH)
                                        if (shortSide < CoverCandidate.MIN_SHORT_SIDE) {
                                            Diag.log(
                                                "covers: 跳过低清 ${source.label} " +
                                                    "${origW}x$origH",
                                            )
                                            return@async null
                                        }
                                        // **降采样解码**：候选一次驻留几十张，
                                        // 全解位图最坏 ~250MB（宿主 SoftMaxHeapSize=128M）。
                                        // 列表/预览显示不超过 320dp，512px 足够。
                                        // 原始 bytes 原样保留，写标签不受影响。
                                        val image =
                                            Host.decodeImageSampled(bytes) ?: return@async null
                                        CoverCandidate(
                                            id = "net:${source.id}:${item.candidate.remoteId}",
                                            bytes = bytes,
                                            image = image,
                                            sourceLabel = source.label,
                                            width = origW,
                                            height = origH,
                                        )
                                    }
                                }.awaitAll()
                        val picks = downloaded.filterNotNull()
                        // 流式上屏：这个源一返回就立刻补进去，不等其它源。
                        // MusicTag 就是这么做的——先出的先看，首图时间取决于最快的源。
                        withContext(Dispatchers.Main) {
                            if (!Epoch.onlineAlive(token)) return@withContext
                            val slots = mutableListOf<CoverTile>()
                            picks.forEach { slots += CoverTile.Ready(it) }
                            // 该源一张都没有时占一个「无更多匹配」，让用户知道这个源查过了
                            if (slots.isEmpty()) slots += CoverTile.Empty(source.label)
                            networkCovers =
                                networkCovers
                                    .filterNot { belongsToSource(it, source.id, source.label, "net") }
                                    .let { rest -> rest + slots }
                            networkCovers = sortSlots(networkCovers)
                            // 候选同步跟上，封面页/主视图能立刻看到新图。
                            // 走 rebuildCandidates：导入的候选不在网格里，
                            // 直接赋值会把它们连同勾选一起丢掉。
                            netCandidates =
                                networkCovers
                                    .filterIsInstance<CoverTile.Ready>()
                                    .map { it.candidate }
                            rebuildCandidates()
                            Diag.log("covers: ${source.label} 流式上屏=" + picks.size)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                coverLoading = false
                // 候选已在各源返回时流式更新过，这里只做收尾
                val ready = networkCovers.filterIsInstance<CoverTile.Ready>().map { it.candidate }
                netCandidates = ready
                rebuildCandidates()
                // 不默认选中、也不默认预览第一张：左栏保持本地图，
                // 候选要用户自己勾选后点「写入草稿」才生效
                postStatus(
                    Status(
                        text =
                            if (ready.isEmpty()) "所有来源均无可用封面" else "网络封面：${ready.size} 张",
                        kind =
                            if (ready.isEmpty()) Status.Kind.Warn else Status.Kind.Ok,
                    ),
                )
            }
        }
    }

    /**
     * 判断一个槽位是否属于某个源。
     *
     * [CoverTile.Ready] 靠 candidate.id 的 `前缀:源id:远程id` 反查，
     * [CoverTile.Searching] 直接看 sourceId，[CoverTile.Empty] 只能靠 label 匹配
     * （它没有 sourceId 字段，而 label 是源的可读名，在本项目里唯一）。
     */
    private fun belongsToSource(
        tile: CoverTile,
        sourceId: String,
        sourceLabel: String,
        idPrefix: String,
    ): Boolean =
        when (tile) {
            is CoverTile.Searching -> tile.sourceId == sourceId
            is CoverTile.Ready -> tile.candidate.id.startsWith("$idPrefix:$sourceId:")
            is CoverTile.Empty -> tile.label == sourceLabel
        }

    private fun sortSlots(slots: List<CoverTile>): List<CoverTile> {
        val ready =
            slots
                .filterIsInstance<CoverTile.Ready>()
                .sortedWith(
                    compareByDescending<CoverTile.Ready> { it.candidate.width.toLong() * it.candidate.height.toLong() },
                )
        val others = slots.filterNot { it is CoverTile.Ready }
        return ready + others
    }

    /**
     * 匹配并**直接写入文件**：批量处理曲库里已勾选的曲目。
     *
     * 按参考项目（SPW-Tag-Workbench / MusicTag）的做法：
     * - 逐首搜索元数据，按标题+艺术家+时长校验后合并
     * - 校验通过的**直接写进文件标签**，不经草稿预览
     * - 每完成一首就取消该行勾选；失败的保留勾选，供重试
     *
     * 为什么不再走草稿：预览只服务「当前正在编辑的那一首」（[matchCurrent] 走完整预览链路）。
     * 批量把几百首的封面字节塞进内存会 OOM，且用户点保存时还要再遍历一次，纯属多余。
     */
    fun matchSelected() {
        val keys = Selection.checkedKeys
        if (keys.isEmpty()) {
            postStatus(Status("先勾选要匹配的曲目", Status.Kind.Warn))
            return
        }
        val token = Epoch.nextOnline()
        postStatus(Status("批量匹配 ${keys.size} 首…", Status.Kind.Busy))
        scope.launch {
            val released = HostBridge.releaseAudioEngine()
            Diag.log("match-batch: releaseAudioEngine=$released tracks=${keys.size}")
            // 计数全部用原子量：多个 worker 并发累加，普通 var 会丢更新
            val okCount = java.util.concurrent.atomic
                .AtomicInteger()
            val skipCount = java.util.concurrent.atomic
                .AtomicInteger()
            val failCount = java.util.concurrent.atomic
                .AtomicInteger()
            val doneCount = java.util.concurrent.atomic
                .AtomicInteger()
            val total = keys.size
            val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()

            // 并发跑：受 NetGate 全局闸门（12）约束，这里再套一层以让「单首内部多源并发」有空间。
            // 单首内部最多同时打 5~6 个源，外层开 4 路就能吃满 12 个许可而不至于互相饿死。
            val gate = Semaphore(MATCH_CONCURRENCY)
            kotlinx.coroutines.coroutineScope {
                keys.forEach { key ->
                    launch(Dispatchers.IO) {
                        gate.withPermit {
                            if (!Epoch.onlineAlive(token)) return@withPermit
                            val meta =
                                library.firstOrNull { it.key == key }
                                    ?: selected?.takeIf { it.key == key }
                                    ?: track.takeIf { it.key == key }
                                    ?: return@withPermit
                            if (!meta.hasFile) {
                                failCount.incrementAndGet()
                                failures += "${meta.title.ifBlank { key }}：没有可写文件"
                                return@withPermit
                            }
                            // 已有完整标签且策略是「仅补缺失」时整首跳过，省掉搜索与下载
                            val probe = runCatching { Tags.probe(meta.path) }.getOrNull()
                            if (probe != null &&
                                MatchRules.shouldSkipComplete(meta, probe.hasLyrics, probe.hasCover)
                            ) {
                                skipCount.incrementAndGet()
                                doneCount.incrementAndGet()
                                withContext(Dispatchers.Main) {
                                    Selection.applyMatchResult(key, SelectReason.MatchOk)
                                }
                                return@withPermit
                            }
                            val outcome = runCatching { matchAndWriteOne(meta) }.getOrNull()
                            doneCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                if (!Epoch.onlineAlive(token)) return@withContext
                                if (outcome?.ok == true) {
                                    okCount.incrementAndGet()
                                    // 成功即取消勾选，用户一眼能看出哪些还没处理
                                    Selection.setByUser(key, false)
                                } else {
                                    // 失败保留勾选，方便重试
                                    failCount.incrementAndGet()
                                    Selection.applyMatchResult(key, SelectReason.MatchFailed)
                                    failures += "${meta.title.ifBlank { key }}：${outcome?.message ?: "匹配失败"}"
                                }
                                // 进度合并刷新：不逐条改状态，只更新一行文本，避免 N 次重组
                                postStatus(
                                    Status(
                                        "批量匹配 ${doneCount.get()}/$total" +
                                            "（成功 ${okCount.get()} 跳过 ${skipCount.get()} 失败 ${failCount.get()}）",
                                        Status.Kind.Busy,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            val ok = okCount.get()
            val skipped = skipCount.get()
            if (ok > 0) HostBridge.refreshLibrary("批量匹配 $ok 首")
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                val msg =
                    buildString {
                        append("批量匹配完成：成功 $ok")
                        if (skipped > 0) append("，跳过完整标签 $skipped")
                        if (failCount.get() > 0) append("，失败 ${failCount.get()}")
                    }
                failures.take(5).forEach { Diag.log("match-batch fail: $it") }
                postStatus(Status(msg, if (failCount.get() == 0) Status.Kind.Ok else Status.Kind.Warn))
            }
        }
    }

    /**
     * 匹配并写入**当前这一首**（主视图「匹配当前」按钮）。
     *
     * 与批量版本的区别：只处理 [focused] 这一首，结果同样直接写文件。
     */
    fun matchCurrent() {
        val meta = focused()
        if (!meta.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        if (!meta.hasFile) {
            postStatus(Status("这首没有可写文件", Status.Kind.Error))
            return
        }
        val token = Epoch.nextOnline()
        postStatus(Status("匹配当前：${meta.title.ifBlank { "未命名" }}…", Status.Kind.Busy))
        scope.launch {
            val released = HostBridge.releaseAudioEngine()
            Diag.log("match-current: releaseAudioEngine=$released track=${meta.key.take(12)}")
            val outcome = runCatching { matchAndWriteOne(meta) }.getOrNull()
            if (outcome?.ok == true) {
                HostBridge.refreshLibrary("匹配当前 ${meta.title.ifBlank { "未命名" }}")
                // 写完后把这份曲目重新读一遍，主视图显示的就是磁盘上的真实值
                refreshFocusedFromDisk(meta)
            }
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                postStatus(
                    Status(
                        text = when {
                            outcome == null -> "匹配当前失败"
                            outcome.ok -> "已匹配并写入：${meta.title.ifBlank { "未命名" }}"
                            else -> "匹配当前失败：${outcome.message}"
                        },
                        kind = if (outcome?.ok == true) Status.Kind.Ok else Status.Kind.Warn,
                    ),
                )
            }
        }
    }

    /**
     * 单首的「搜索 → 校验 → 合并 → 写文件」。
     *
     * 与参考项目的 `MatchRowProcessor.process + TagMergePolicy.merge` 对齐：
     * 只在候选通过校验时才写；写的是合并后的值（远端有值才覆盖，空值保留本地）。
     *
     * @return 写入结果；没有可用候选时返回失败
     */
    private suspend fun matchAndWriteOne(meta: TrackMeta): WriteOutcome {
        val query = SearchQuery(meta.title, meta.artist, meta.album, meta.durationMs)

        // 纯音乐 / 伴奏：**零网络请求直接跳过**。
        //
        // 纯音乐几乎没有逐字歌词，一旦进入多源匹配必然全部失败——
        // 每首白跑一整套请求，批量几百首时这是最大的浪费之一。
        // 而且标题里的「伴奏」在归一化后会被剥掉，继续匹配有把演唱版歌词
        // 写进伴奏文件的真实风险。
        if (Matching.isInstrumental(meta.title, meta.album, meta.path.substringAfterLast('\\'))) {
            Diag.log("match-skip: ${meta.title} 标记为纯音乐/伴奏，未请求任何源")
            return WriteOutcome.failure(meta.path, "纯音乐/伴奏，按策略跳过（避免误配演唱版）")
        }

        val result = aggregator.search(query = query, capability = Capability.META)
        if (result.candidates.isEmpty()) {
            // **给出具体拒绝理由**，而不是笼统的「没有匹配到」。
            //
            // 批量匹配后用户没法逐首复核，失败原因必须能直接定位问题：
            // 是标题对不上？艺术家不同？还是时长差太多？
            val reason =
                if (result.failures.isNotEmpty() && result.queried == result.failures.size) {
                    "来源请求失败：" + result.failures.joinToString("、") { "${it.first}(${it.second})" }
                } else {
                    "没有匹配到候选（标题「${meta.title}」/ 艺术家「${meta.artist}」" +
                        "/ 时长 ${meta.durationMs / 1000}s 均未通过校验）"
                }
            return WriteOutcome.failure(meta.path, reason)
        }

        // **多候选校验后择优**，而不是拿第一条就用。
        //
        // 原来的写法是 `candidates.firstOrNull()`：哪个源先回就用谁，
        // 而网络快的源返回的未必是最匹配的那条。批量匹配无人逐首复核，
        // 一旦选错就会把错误标签写进文件。
        //
        // `aggregator.search` 返回的候选**已经过 CandidateScoring 校验**
        // （标题四级 + 艺术家 + 时长，不匹配的 level=0 已被丢弃），
        // 并按「等级 → 源权重 → 有歌词 → 有封面」排好序。
        // 这里再按「等级优先、时长差次之」挑一次，确保取到集合里最优的那条。
        val best =
            result.candidates.minWithOrNull(
                compareBy<RankedCandidate> { it.level } // 1 最可信
                    .thenBy { durationGap(it.candidate.durationMs, query.durationMs) }
                    .thenByDescending { it.candidate.hasLyrics }
                    .thenByDescending { it.candidate.hasCover },
            ) ?: return WriteOutcome.failure(meta.path, "候选均未通过校验")

        val cand = best.candidate
        // 等级 4（仅相似度达标）在无人复核的批量里风险偏高，记日志便于事后排查
        val weak = best.level >= 4
        Diag.log(
            "match: ${meta.title} -> ${cand.title} / ${cand.artist} " +
                "level=${best.level}${if (weak) "（弱匹配）" else ""} " +
                "候选数=${result.candidates.size}",
        )

        // 合并策略：远端非空且非占位值才覆盖；专辑艺术家这类不可信字段只填空缺
        val patch = TagPatch()
        keepInto(patch, TagField.Title, cand.title, meta.title)
        keepInto(patch, TagField.Artist, cand.artist, meta.artist)
        keepInto(patch, TagField.Album, cand.album, meta.album)
        keepIfBlankInto(patch, TagField.AlbumArtist, cand.albumArtist, meta.albumArtist)
        // 年份 / 流派：只在本地为空时补。TrackMeta 不含音轨号与碟号，
        // 这两项若要写必须另外读盘，批量场景下不值得为它多一次 IO。
        keepIfBlankInto(patch, TagField.Year, cand.year.takeIf { it > 0 }?.toString(), meta.year)
        keepIfBlankInto(patch, TagField.Genre, cand.genre, meta.genre)

        // 歌词与封面：**只在校验已通过、且本地缺这两样时才去取**。
        //
        // 顺序很关键——先确认候选是对的，再花请求下载内容。反过来做就会为
        // 错误候选白下载一遍图/词，批量几百首时代价很大。
        //
        // 而且要按策略判断「该不该补」：仅补缺失时已有歌词/封面的本不该被覆盖。
        val needLyrics = shouldFetchLyrics(meta)
        val needCover = shouldFetchCover(meta)

        var cover: CoverArt? = null
        if (needLyrics) {
            val text = runCatching { fetchLyricsFor(cand) }.getOrNull()
            if (!text.isNullOrBlank()) {
                patch.set(TagField.Lyrics, text)
                val wordSynced = LyricsFormat.isReallyWordSynced(text)
                Diag.log("match: ${meta.title} 歌词已取（${if (wordSynced) "逐字" else "逐行"}）")
            } else {
                Diag.log("match: ${meta.title} 歌词未取到，保持原样")
            }
        }
        if (needCover) {
            val bytes = runCatching { aggregatorFetchCover(cand) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                cover = CoverArt(bytes, sniffMime(bytes))
                Diag.log("match: ${meta.title} 封面已取（${bytes.size / 1024}KB）")
            } else {
                Diag.log("match: ${meta.title} 封面未取到，保持原样")
            }
        }

        val backend = Track(id = meta.id, path = meta.path)
        return AudioTagWriter.write(
            track = backend,
            patch = patch,
            cover = cover,
            refreshHost = false,
        )
    }

    /**
     * 批量匹配时该不该为这首歌取歌词。
     *
     * `REFILL_ALL` 一律重取；其余策略只在文件里还没有歌词时取——
     * 「仅补缺失」的语义就是「不缺的不动」。
     */
    private fun shouldFetchLyrics(meta: TrackMeta): Boolean {
        if (MatchRules.current == MatchingPolicy.REFILL_ALL) return true
        val probe = runCatching { Tags.probe(meta.path) }.getOrNull() ?: return true
        return !probe.hasLyrics
    }

    /** 同上，封面。 */
    private fun shouldFetchCover(meta: TrackMeta): Boolean {
        if (MatchRules.current == MatchingPolicy.REFILL_ALL) return true
        val probe = runCatching { Tags.probe(meta.path) }.getOrNull() ?: return true
        return !probe.hasCover
    }

    /** 按候选所属源取歌词。 */
    private suspend fun fetchLyricsFor(cand: SourceCandidate): String? {
        val source = aggregator.sourceById(cand.sourceId) ?: return null
        if (!source.supports(Capability.LYRICAL)) return null
        return withTimeoutOrNull(9_000L) { source.lyrics(cand) }
    }

    /** 按候选所属源取封面。 */
    private suspend fun aggregatorFetchCover(cand: SourceCandidate): ByteArray? {
        val source = aggregator.sourceById(cand.sourceId) ?: return null
        if (!source.supports(Capability.COVER)) return null
        val bytes = withTimeoutOrNull(9_000L) { source.cover(cand) } ?: return null
        // 内存峰值保护：批量 4 路并发、每路一张图，超大的图会让堆瞬间涨上去。
        // 超过上限就放弃这一张（保留本地原封面），比 OOM 好。
        if (bytes.size > MAX_BATCH_COVER_BYTES) {
            Diag.log("match: 封面过大 ${bytes.size / 1024}KB，丢弃")
            return null
        }
        return bytes
    }

    /** 批量匹配时单张封面的上限。正常专辑封面 500KB 以内，2MB 已经很大。 */
    private val MAX_BATCH_COVER_BYTES = 2 * 1024 * 1024

    /** 按文件头猜 MIME；猜不出用 image/jpeg（各源都是 JPEG/PNG）。 */
    private fun sniffMime(bytes: ByteArray): String =
        when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "image/webp"
            else -> "image/jpeg"
        }

    /** 时长差；任一侧未知时给一个大值，让已知的先排前面。 */
    private fun durationGap(
        remoteMs: Long,
        localMs: Long,
    ): Long {
        if (remoteMs <= 0L || localMs <= 0L) return Long.MAX_VALUE / 2
        return kotlin.math.abs(remoteMs - localMs)
    }

    /** 远端有非空值才写；空值 / 占位值一律保留本地原值。 */
    private fun keepInto(
        patch: TagPatch,
        field: TagField,
        incoming: String?,
        existing: String,
    ) {
        val v = incoming?.trim().orEmpty()
        if (v.isEmpty() || isPlaceholder(v)) return
        if (v == existing) return
        patch.set(field, v)
    }

    /**
     * 占位值判定：接口用这些值表示「没有数据」，写进标签就是污染。
     *
     * 实现放在 [Matching] 里，纯函数、有单测覆盖，这里只做转发。
     */
    private fun isPlaceholder(value: String): Boolean = Matching.isPlaceholder(value)

    /** 只填空缺：本地已有值时永不覆盖（用于专辑艺术家这类来源不可信的字段）。 */
    private fun keepIfBlankInto(
        patch: TagPatch,
        field: TagField,
        incoming: String?,
        existing: String,
    ) {
        if (existing.isNotBlank() && !isPlaceholder(existing)) return
        keepInto(patch, field, incoming, existing)
    }

    /** 写完文件后，把这一首的元数据从磁盘重读一遍，保证界面显示的是真实值。 */
    private suspend fun refreshFocusedFromDisk(meta: TrackMeta) {
        val fresh = runCatching { withContext(Dispatchers.IO) { Tags.read(meta.path) } }.getOrNull() ?: return
        // Tags.read 返回后端的 Track，镜像成界面用的 TrackMeta；id 沿用曲库里的那条
        val updated =
            TrackMeta(
                id = meta.id,
                title = fresh.title,
                artist = fresh.artist,
                album = fresh.album,
                albumArtist = fresh.albumArtist,
                genre = fresh.genre,
                year = fresh.year,
                path = meta.path,
                durationMs = meta.durationMs,
                readable = true,
            )
        withContext(Dispatchers.Main) {
            if (focused().key == meta.key) {
                selected = updated
                loadLyrics(updated)
                loadCovers(updated)
            }
            // 曲库列表里的那一行也同步刷新
            library =
                library.map {
                    if (it.key == meta.key) updated else it
                }
        }
    }
    // ── 在线歌词候选 ─────────────────────────────────────────────────────────

    /** 歌词候选列表状态（⑦）。 */
    var lyricMatch by mutableStateOf<LyricMatch>(LyricMatch.Idle)
        private set

    /** 搜索在线歌词候选（只要声明了 LYRICAL 能力的源）。 */
    fun searchLyrics() {
        val meta = focused()
        if (!meta.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        val token = Epoch.nextOnline()
        lyricMatch = LyricMatch.Searching
        scope.launch {
            // 流式上屏：哪个源先回就先列哪一组，不等最慢的源。
            // 旧实现用 searchGrouped（内部 awaitAll），用户要等全部源才能看到任何候选。
            val collected = java.util.Collections.synchronizedList(mutableListOf<SourceGroup>())
            aggregator.streamGrouped(
                query = SearchQuery(meta.title, meta.artist, meta.album, meta.durationMs),
                capability = Capability.LYRICAL,
            ) { group ->
                collected += group
                withContext(Dispatchers.Main) {
                    if (!Epoch.onlineAlive(token)) return@withContext
                    // 每组一回来就重排一次：组间按源权重，组内已按匹配等级排好
                    val snapshot =
                        collected.toList().sortedWith(compareByDescending<SourceGroup> { it.weight })
                    lyricMatch = LyricMatch.Done(snapshot)
                    val total = snapshot.sumOf { it.items.size }
                    postStatus(
                        Status(
                            "歌词候选：$total 条 / ${snapshot.size} 个源…",
                            Status.Kind.Busy,
                        ),
                    )
                }
            }
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                val groups = collected.toList().sortedWith(compareByDescending<SourceGroup> { it.weight })
                val total = groups.sumOf { it.items.size }
                val failed = groups.filter { it.failure != null }
                val okSources = groups.size - failed.size
                lyricMatch =
                    if (total == 0 && failed.isNotEmpty() && okSources == 0) {
                        LyricMatch.Failed(failed.joinToString("；") { "${it.label}: ${it.failure}" })
                    } else {
                        LyricMatch.Done(groups)
                    }
                postStatus(
                    Status(
                        text =
                            "歌词候选：$total 条 / ${groups.size} 个源" +
                                if (failed.isNotEmpty()) "，${failed.size} 个源失败" else "",
                        kind =
                            if (total == 0) Status.Kind.Warn else Status.Kind.Ok,
                    ),
                )
            }
        }
    }

    /** 从分组结果里取一条候选的歌词（同源优先，失败回退到其它歌词源）。 */
    fun chooseGroupedLyrics(
        sourceId: String,
        candidate: io.github.univers629.spwtagworkbench.backend.source.SourceCandidate,
    ) {
        loadOnlineLyricsFrom(sourceId, candidate)
    }

    /** 选一个歌词候选：拉歌词 → 解析 → 载入编辑区 → 进草稿。 */
    fun chooseLyricsCandidate(ranked: RankedCandidate) = loadOnlineLyrics(ranked)

    /** 用某个源的具体候选取歌词：同源优先，取不到按权重回退。 */
    private fun loadOnlineLyricsFrom(
        sourceId: String,
        candidate: io.github.univers629.spwtagworkbench.backend.source.SourceCandidate,
    ) {
        val meta = focused()
        if (!meta.isPresent) return
        val token = Epoch.nextOnline()
        scope.launch {
            val ranked =
                io.github.univers629.spwtagworkbench.backend.source.RankedCandidate(
                    candidate = candidate,
                    level = 1,
                    allSources = listOf(sourceId),
                    idsBySource = mapOf(sourceId to candidate.remoteId),
                )
            val (text, label) = fetchLyrics(ranked)
            withContext(Dispatchers.Main) {
                if (!Epoch.onlineAlive(token)) return@withContext
                if (text.isNullOrBlank()) {
                    postStatus(Status("该候选没有可用歌词", Status.Kind.Warn))
                    return@withContext
                }
                Drafts.stageLyrics(meta.key, text)
                val parsed = parseLyrics(text)
                editedLines = parsed
                lines = parsed
                // 在线原始 LRC 作为源文本；编辑栏默认显示它
                lyricBase = text
                lyricText = text
                lyricsEpoch++
                lyricSource = "在线 · ${label ?: "未知源"}"
                postStatus(
                    Status(
                        text = "歌词已载入（来源：${label ?: "未知"}），${parsed.size} 行",
                        kind = Status.Kind.Ok,
                    ),
                )
            }
        }
    }

    /**
     * 把编辑区的歌词写入草稿（预览制：不落盘）。
     *
     * **直接用源文本，不做任何解析/重组**。
     *
     * 原来的写法是 `editedLines.joinToString("\n") { it.text }` ——
     * 那会把每行的时间戳全部丢掉，只剩纯文字。用户看到的现象就是
     * 「从 QQ 拉下来明明是带时间戳的，保存后只剩文字」。
     * 歌词标签里存什么，界面就显示什么；这条链路不该有任何转换。
     */
    fun saveLyricsToDraft() {
        val meta = focused()
        if (!meta.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        // 源文本优先：它就是文件里该有的内容
        val text =
            lyricText.ifBlank {
                editedLines.ifEmpty { lines }.joinToString("\n") { it.text }
            }
        if (text.isBlank()) {
            postStatus(Status("歌词为空，未写入草稿", Status.Kind.Warn))
            return
        }
        Drafts.stageLyrics(meta.key, text)
        postStatus(Status("歌词已加入草稿，保存后写入", Status.Kind.Ok))
    }
    // ── 草稿（预览制） ───────────────────────────────────────────────────────

    /** 编辑一个字段：只进草稿，不落盘。 */
    fun stageField(
        key: Tags.Key,
        value: String,
    ) {
        val meta = focused()
        if (!meta.isPresent) {
            postStatus(Status("没有选中曲目", Status.Kind.Error))
            return
        }
        Drafts.stageField(meta.key, key.field, value)
        postStatus(
            Status(
                text =
                    if (Drafts.dirtyCount > 0) {
                        "已加入待保存（${Drafts.dirtyCount} 首有变更）"
                    } else {
                        "已撤销该变更"
                    },
                kind = Status.Kind.Busy,
            ),
        )
    }

    /** 撤销当前曲目的草稿。 */
    fun discardCurrentDraft() {
        val meta = focused()
        if (meta.isPresent) Drafts.unstage(meta.key)
    }

    /** 全部放弃。 */
    fun discardAllDrafts() {
        Drafts.clear()
        postStatus(Status("已放弃全部未保存变更", Status.Kind.Warn))
    }

    /**     * 保存全部草稿。     *     * 顺序固定：释放音频引擎 → 逐个写文件 → 刷新音乐库 → 重载当前播放歌词 → 清草稿。     * 单首失败不中断，失败的保留脏标记供重试。     */
    fun saveDrafts() {
        val keys = Drafts.dirtyKeys
        if (keys.isEmpty()) {
            postStatus(Status("没有待保存的变更", Status.Kind.Warn))
            return
        }
        val token = Epoch.nextSave()
        postStatus(Status("保存中：${keys.size} 首…", Status.Kind.Busy))
        scope.launch {
            // 1. 释放音频引擎
            val released = HostBridge.releaseAudioEngine()
            Diag.log("save: releaseAudioEngine=$released tracks=${keys.size}")
            var ok = 0
            val failures = mutableListOf<String>()
            // 2. 逐个写文件
            for (key in keys) {
                if (!Epoch.saveAlive(token)) break
                val meta = (
                    library.firstOrNull { it.key == key }
                        ?: selected?.takeIf { it.key == key }
                        ?: track.takeIf { it.key == key }
                )
                if (meta == null) {
                    failures += "$key：找不到曲目"
                    continue
                }
                if (!meta.hasFile) {
                    failures += "${meta.title.ifBlank { key }}：没有可写文件"
                    continue
                }
                val draft = Drafts.draftFor(key)
                if (draft == null || draft.isEmpty) {
                    withContext(Dispatchers.Main) { Drafts.markSaved(key) }
                    continue
                }
                val patch = TagPatch()
                for ((field, value) in draft.fields) patch.set(field, value)
                draft.lyrics?.let {
                    patch.set(TagField.Lyrics, it)
                }
                val backend = Track(id = meta.id, path = meta.path)
                // 只有当真有封面草稿时才读回字节：没有就传 null，
                // 免得白白把原封面读进内存又原样写回去。
                val coverArt =
                    draft.cover?.readBytes()?.let {
                        io.github.univers629.spwtagworkbench.backend.read.CoverMime
                            .asCover(it)
                    }
                val outcome =
                    AudioTagWriter.write(
                        track = backend,
                        patch = patch,
                        cover = coverArt,
                        refreshHost = false,
                    )
                // 艺术家图是第二张图（type=8），必须单独再写一次：
                // 一次 write 只能带一张封面，类型也不同，合并会互相覆盖。
                val artistOutcome =
                    draft.artistCover?.readBytes()?.let { bytes ->
                        io.github.univers629.spwtagworkbench.backend.read.CoverMime.asCover(bytes)?.let { art ->
                            AudioTagWriter.writeCover(backend, art, pictureType = 8)
                        }
                    }
                if (outcome.ok && (artistOutcome == null || artistOutcome.ok)) {
                    ok++
                    withContext(Dispatchers.Main) { Drafts.markSaved(key) }
                } else {
                    val why = if (!outcome.ok) outcome.message else artistOutcome?.message.orEmpty()
                    failures += "${meta.path.substringAfterLast('\\')}：$why"
                }
            }
            // 3. 刷新音乐库 / 4. 重载当前播放歌词
            if (ok > 0) {
                HostBridge.refreshLibrary("保存草稿 $ok 首")
                val currentId = track.id
                if (currentId.isNotEmpty()) HostBridge.reloadLyrics(currentId)
                // 草稿已清空，界面要从磁盘重读，否则预览停在草稿值上会与文件不一致
                val focus = focused()
                if (focus.isPresent && focus.hasFile) refreshFocusedFromDisk(focus)
                withContext(Dispatchers.Main) { refreshLibrary() }
            }
            withContext(Dispatchers.Main) {
                if (!Epoch.saveAlive(token)) return@withContext
                postStatus(
                    Status(
                        text =
                            buildString {
                                append("保存完成：成功 $ok / ${keys.size}")
                                if (failures.isNotEmpty()) append("，失败 ${failures.size}")
                            },
                        kind =
                            if (failures.isEmpty()) Status.Kind.Ok else Status.Kind.Warn,
                    ),
                )
                lastSaveFailures = failures
            }
        }
    }

    /** 顶栏的联网状态短标签。 */
    fun netLabel(): String = if (io.github.univers629.spwtagworkbench.backend.net.NetProbe.offline) "离线" else ""

    /** 最近一次保存的失败明细，UI 直接展示。 */
    var lastSaveFailures by mutableStateOf<List<String>>(emptyList())
        private set

    // ── 状态 ────────────────────────────────────────────────────────────────
    fun postStatus(value: Status?) {
        status = value
        if (value != null) {
            io.github.univers629.spwtagworkbench.core.Diag
                .log("status[" + value.kind + "] " + value.text)
        }
    }

    /** 取消候选勾选（不清歌曲原封面，也不动预览）。 */
    fun clearCoverSelection() {
        selectedCoverId = null
    }

    fun clearStatus() {
        status = null
    }

    // ── 转换与解析 ───────────────────────────────────────────────────────────
    private fun PlaybackExtensionPoint.MediaItem.toMeta() =
        TrackMeta(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year.toString(),
            path = path,
            durationMs = duration,
            readable = readable,
        )

    /**     * 解析歌词文本：自动识别 LRC（含逐字）/ SPL / 纯文本。     *     * 统一走 [io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat.parse]。     * 这里曾经另有一份简易 LRC 解析，遇到「一行多个时间戳」的逐字歌词时     * `substring(最后一个时间戳之后)` 会得到空串，整首歌解析成空文本 —— 已删除。     */
    fun parseLyrics(raw: String): List<LyricLine> =
        io.github.univers629.spwtagworkbench.backend.lyrics.LyricsFormat
            .parse(raw)

    /**
     * 把歌词行序列化成**歌词标签文本**。
     *
     * 关键：**有逐字单元时必须保留逐字时间**。
     *
     * 原来这里只输出 `[行时间]整行文字`，直接把 `line.cells` 丢掉了——
     * 于是从 QQ 拉下来的逐字歌词（每个字都有起止时间）保存之后只剩一行行纯文字，
     * 用户看到的就是「拉下来带时间戳，保存就只剩文字」。
     *
     * 逐字行按 SPL 写法输出：`[行时间][字时间]字[字时间]字…`，
     * 与 `LyricsFormat.toSpl` 一致——这样写进标签的内容仍是本插件可识别的格式，
     * 重新载入后逐字时间完整还原。
     */
    fun buildLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (line.cells.isEmpty()) {
                // 逐行：行首一个时间戳
                sb.append(stamp(line.startMs.coerceAtLeast(0L))).append(line.text).append('\n')
            } else {
                // 逐字：行首时间 + 每个字自己的时间
                sb.append(stamp(line.startMs.coerceAtLeast(0L)))
                for (cell in line.cells) {
                    sb.append(stamp(cell.startMs.coerceAtLeast(0L))).append(cell.text)
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** `[mm:ss.xx]` 时间戳，LRC 精度到 10 毫秒。 */
    private fun stamp(millis: Long): String =
        buildString {
            append('[')
            append((millis / 60000).toString().padStart(2, '0')).append(':')
            append((millis / 1000 % 60).toString().padStart(2, '0')).append('.')
            append((millis % 1000 / 10).toString().padStart(2, '0'))
            append(']')
        }

    /** 把一行文本切成字单元：中日韩按字，拉丁按词。 */
    fun splitCells(text: String): List<LyricCell> {
        if (text.isBlank()) {
            return emptyList()
        }
        val cells = mutableListOf<LyricCell>()
        val latin = StringBuilder()

        fun flushLatin() {
            if (latin.isNotEmpty()) {
                cells += LyricCell(latin.toString())
                latin.setLength(0)
            }
        }
        for (ch in text) {
            if (ch.isWhitespace()) {
                flushLatin()
                continue
            }
            val wide = ch.code > 0x2E80
            if (wide) {
                flushLatin()
                cells += LyricCell(ch.toString())
            } else {
                latin.append(ch)
            }
        }
        flushLatin()
        return cells
    }

    // ── 打轴：与宿主播放同步 ─────────────────────────────────────────────────

    /** 打轴页选中的曲目是否就是宿主正在播放的那首。 */
    val hostSynced: Boolean
        get() {
            val focus = focused()
            return focus.isPresent && track.isPresent && focus.id == track.id
        }

    /** 宿主播放状态文本，供状态灯使用。 */
    fun hostSyncLabel(): String =
        when {
            !focused().isPresent -> "未选择曲目"
            !track.isPresent -> "宿主未播放"
            hostSynced -> "已同步"
            else -> "未同步"
        }

    /**
     * 让宿主切到当前编辑的曲目并开始播放（打轴页用）。
     *
     * dev23 的 `WorkshopApi.playback` 只有 play/pause/next/previous/seekTo，
     * 没有「播放指定曲目」；实际入口在宿主内部的 `PlaybackController`，
     * 详见 [HostBridge.playTrack] 与 dev23-api-guide §9.16。
     *
     * 必须主线程调用。成功后不会立刻改 [track]——宿主切换是异步的，
     * 等播放回调（或 2 秒轮询）把它带回来，[hostSynced] 随之变绿。
     */
    fun switchHostToFocused(): Boolean {
        val focus = focused()
        if (!focus.isPresent) {
            postStatus(Status("未选择曲目", Status.Kind.Warn))
            return false
        }
        val ok =
            runCatching {
                HostBridge.playTrack(
                    id = focus.id,
                    title = focus.title,
                    artist = focus.artist,
                    album = focus.album,
                    albumArtist = focus.albumArtist,
                    path = focus.path,
                )
            }.getOrDefault(false)
        if (ok) {
            Diag.log("host: 已请求切歌 id=${focus.id}")
            postStatus(Status("已让宿主切到本曲", Status.Kind.Ok))
        } else {
            Diag.log("host: 切歌失败，见上方 play-track 日志")
            postStatus(
                Status(
                    text = "切歌失败，详情见 diag.log",
                    kind = Status.Kind.Warn,
                ),
            )
        }
        return ok
    }

    /** 把某行的起始时间设为当前位置；结束时间由下一行的起始时间推导。 */
    fun stampLineStart(lineIndex: Int) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return
        val at = positionMs.coerceAtLeast(0L)
        val nextStart = list.drop(lineIndex + 1).firstOrNull { it.startMs >= 0 }?.startMs ?: -1L
        list[lineIndex] = line.copy(startMs = at, endMs = nextStart)
        editedLines = list
        lines = list
        Diag.log("timeline: 行 ${lineIndex + 1} 起始 = $at ms")
    }

    /** 清空某行的时间（行首与全部字单元）。 */
    fun clearLineTiming(lineIndex: Int) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line =
            list.getOrNull(lineIndex)
                ?: return
        list[lineIndex] =
            line.copy(
                startMs = -1L,
                endMs = -1L,
                cells = line.cells.map { it.copy(startMs = -1L, endMs = -1L) },
            )
        editedLines = list
        lines = list
    }

    fun stampCell(
        lineIndex: Int,
        cellIndex: Int,
        startMs: Long,
        endMs: Long,
    ) {
        val list = editedLines.ifEmpty { lines }.toMutableList()
        val line = list.getOrNull(lineIndex) ?: return
        val cells = line.cells.toMutableList()
        val cell =
            cells.getOrNull(cellIndex)
                ?: return
        cells[cellIndex] = cell.copy(startMs = startMs, endMs = endMs)
        list[lineIndex] = line.copy(cells = cells)
        editedLines = list
    }

    /** 打轴用行：优先编辑中的行（含字单元），否则回退到时间轴。 */
    fun timelineLines(): List<LyricLine> = editedLines.ifEmpty { lines }.filter { it.text.isNotBlank() }
}

/** 元数据在线匹配状态。 */
sealed interface MetaMatch {
    data object Idle : MetaMatch

    data object Searching : MetaMatch

    data class Done(
        val result: io.github.univers629.spwtagworkbench.backend.source.AggregateResult,
    ) : MetaMatch

    data class Failed(
        val reason: String,
    ) : MetaMatch
}

/** 在线歌词候选状态。 */
sealed interface LyricMatch {
    data object Idle : LyricMatch

    data object Searching : LyricMatch

    data class Done(
        val groups: List<io.github.univers629.spwtagworkbench.backend.source.SourceGroup>,
    ) : LyricMatch

    data class Failed(
        val reason: String,
    ) : LyricMatch
}
