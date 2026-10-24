// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.write

import io.github.univers629.spwtagworkbench.backend.model.BatchProgress
import io.github.univers629.spwtagworkbench.backend.model.BatchReport
import io.github.univers629.spwtagworkbench.backend.model.CoverArt
import io.github.univers629.spwtagworkbench.backend.model.TagPatch
import io.github.univers629.spwtagworkbench.backend.model.Track
import io.github.univers629.spwtagworkbench.backend.model.WriteOutcome
import io.github.univers629.spwtagworkbench.core.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量写入。
 *
 * 行为约定：
 * - 单文件失败不影响其余；最终汇总失败项与原因
 * - 进度实时上报（UI 直接渲染）
 * - 可取消：取消点落在两个文件之间，不留半个文件（单文件内部的原子性由 [AudioTagWriter] 保证）
 * - 宿主刷新只在最后做一次：逐个 refresh 会让宿主反复重扫，卡顿明显
 */
class BatchWriter(
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(BatchProgress(0, 0, 0))
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel(CancellationException("用户取消批量写入"))
        job = null
    }

    /**
     * 启动批量写入。
     *
     * @param tracks 待写曲目
     * @param patchFor 每条曲目对应的字段集合（可按曲目生成）
     * @param coverFor 每条曲目的封面（可为 null）
     * @param reloadLyricsForId 当前播放歌曲 id，写完据此重载歌词
     */
    fun start(
        tracks: List<Track>,
        patchFor: (Track) -> TagPatch,
        coverFor: (Track) -> CoverArt? = { null },
        reloadLyricsForId: String? = null,
        onFinished: (BatchReport) -> Unit,
    ) {
        if (running) return
        job =
            scope.launch {
                val report = runCatchingReport(tracks, patchFor, coverFor, reloadLyricsForId)
                onFinished(report)
            }
    }

    private suspend fun runCatchingReport(
        tracks: List<Track>,
        patchFor: (Track) -> TagPatch,
        coverFor: (Track) -> CoverArt?,
        reloadLyricsForId: String?,
    ): BatchReport =
        withContext(Dispatchers.IO) {
            var done = 0
            var failedCount = 0
            val failures = mutableListOf<WriteOutcome>()
            var cancelled = false

            _progress.value = BatchProgress(tracks.size, 0, 0)

            for (track in tracks) {
                val ctx = currentCoroutineContext()
                if (ctx[Job]?.isActive == false) {
                    cancelled = true
                    break
                }
                try {
                    ctx.ensureActive()
                    _progress.value = BatchProgress(tracks.size, done, failedCount, track.path)
                    val patch = patchFor(track)
                    val cover = coverFor(track)
                    val outcome =
                        AudioTagWriter.write(
                            track = track,
                            patch = patch,
                            cover = cover,
                            refreshHost = false,
                            reloadLyricsForId = null,
                        )
                    if (outcome.ok) {
                        done++
                    } else {
                        failedCount++
                        failures += outcome
                    }
                } catch (c: CancellationException) {
                    cancelled = true
                    break
                } catch (t: Throwable) {
                    failedCount++
                    failures += WriteOutcome.failure(track.path, "${t.javaClass.simpleName}: ${t.message.orEmpty().take(60)}")
                }
                _progress.value = BatchProgress(tracks.size, done, failedCount, track.path)
            }

            // 全部写完后统一刷新一次宿主
            if (done > 0) {
                io.github.univers629.spwtagworkbench.backend.host.HostBridge
                    .refreshLibrary("批量写入 $done 首")
                if (!reloadLyricsForId.isNullOrEmpty()) {
                    io.github.univers629.spwtagworkbench.backend.host.HostBridge
                        .reloadLyrics(reloadLyricsForId)
                }
            }

            _progress.value = BatchProgress(tracks.size, done, failedCount, "", finished = true)
            Diag.log("batch done: total=${tracks.size} ok=$done fail=$failedCount cancelled=$cancelled")
            BatchReport(
                total = tracks.size,
                succeeded = done,
                failed = failures,
                cancelled = cancelled,
            )
        }
}
