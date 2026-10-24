// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.extension

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import io.github.univers629.spwtagworkbench.data.Repo
import org.pf4j.Extension

/**
 * 播放状态推送。侧栏与宽视图都靠它保持跟随当前歌曲。
 * 回调可能在 IO 线程，写状态的动作都很轻，界面侧用 Compose 状态观察。
 *
 * 宿主对「禁用插件的扩展点是否停止分发」无明文保证；
 * [Repo.stopped] 为 true 时（stop 已执行）回调直接丢弃，
 * 不再进入已取消的作用域，杜绝「插件已停仍被宿主驱动」。
 */
@Extension
class PlaybackExt : PlaybackExtensionPoint {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (Repo.stopped) return
        Repo.postOnMain { Repo.onPlayingChanged(isPlaying) }
    }

    override fun onPositionUpdated(position: Long) {
        if (Repo.stopped) return
        Repo.postOnMain { Repo.onPosition(position) }
    }

    override fun onSeekTo(position: Long) {
        if (Repo.stopped) return
        Repo.postOnMain { Repo.onPosition(position) }
    }

    override fun onLyricsLinesUpdated(lyricsLines: List<PlaybackExtensionPoint.LyricsLine>) {
        if (Repo.stopped) return
        Repo.postOnMain { Repo.onLyricLines(lyricsLines) }
    }

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        if (Repo.stopped) return
        Repo.postOnMain { Repo.onLyricLine(lyricsLine) }
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        if (Repo.stopped) return null
        // 这个回调在 IO 线程，状态写入交给主线程
        Repo.postOnMain { Repo.onTrackChanged(mediaItem) }
        return null
    }
}
