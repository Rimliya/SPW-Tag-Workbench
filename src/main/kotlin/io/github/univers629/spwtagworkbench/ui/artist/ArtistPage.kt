// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.artist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.EmptyState
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.TapChip
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.sourceDoneFraction
import io.github.univers629.spwtagworkbench.data.CoverCandidate
import io.github.univers629.spwtagworkbench.data.CoverTile
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.ui.cover.CheckMark
import io.github.univers629.spwtagworkbench.ui.cover.pickImageFile
import io.github.univers629.spwtagworkbench.ui.cover.saveImageFile
import io.github.univers629.spwtagworkbench.ui.cover.suggestedName
import io.github.univers629.spwtagworkbench.ui.icons.IconArtist
import io.github.univers629.spwtagworkbench.ui.source.SourceEmptyTile
import io.github.univers629.spwtagworkbench.ui.source.SourceLoadingTile
import io.github.univers629.spwtagworkbench.ui.space.DetailScaffold

/**
 * 艺术家图匹配页。
 *
 * 版式与封面页完全一致：
 * - 左栏 = **预览窗格**：一开始是文件里的艺术家图，只有点「写入草稿」才换成勾选的候选
 * - 右栏 = 每源 3 格候选，右下角勾选框，点一下只勾选、**只能勾一张**
 *
 * 只用声明了 `ARTIST_COVER` 的源（QQ `T001`、酷狗 `softhead`、网易云 `img1v1Url`）。
 */
@Composable
fun ArtistPage(interactive: Boolean) {
    val palette = Salt

    LaunchedEffect(Repo.focused().key) {
        if (Repo.focused().path.isNotBlank()) Repo.loadNetworkArtistCovers()
    }

    val chosen = Repo.artistCandidate
    // 当前编辑的槽位：两个槽位都写 type=8，互不影响
    val slotIndex = Repo.artistSlotIndex
    val slotLabel = "槽 ${slotIndex + 1}"

    DetailScaffold(
        page = SpacePage.Artist,
        label = "艺术家图",
        interactive = interactive,
        coverSize = 92.dp,
        // 与其它三页统一：进度走顶栏中间的进度条，源标签统计不再显示
        progress = sourceDoneFraction(Repo.artistSourceStates()),
        actions = {
            // 槽位切换：两个槽位都写 type=8，只改所选槽位的图
            repeat(Repo.artistSlotCount) { index ->
                TapChip(
                    label = "槽 ${index + 1}",
                    onClick = { Repo.selectArtistSlot(index) },
                    accent = if (slotIndex == index) palette.accent else palette.textTertiary,
                    enabled = interactive,
                )
                Spacer(Modifier.width(8.dp))
            }
            // 导入 / 导出已移到左侧本地图下方；顶栏只留「写入草稿」
            TapChip(
                label = "写入草稿",
                onClick = { Repo.applyArtistCover() },
                enabled = interactive && chosen != null && Repo.focused().hasFile,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                // ── 左：**本地**艺术家图（只读展示）─────────────────────────
                Column(
                    modifier =
                        Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt("艺术家图预览（$slotLabel）", size = T.micro, color = palette.textTertiary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cardRaised)
                                .border(0.5.dp, palette.border, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val local = Repo.artistPreview
                        if (local != null) {
                            Image(
                                bitmap = local,
                                contentDescription = "本地艺术家图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // 空占位用矢量图标，不再用 emoji（emoji 在不同系统字体下观感不一）
                            IconArtist(
                                color = palette.textTertiary,
                                modifier = Modifier.fillMaxSize(0.34f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Txt(
                        text = Repo.artistPreviewInfo.ifBlank { "无艺术家封面" },
                        size = T.caption,
                        color = palette.textSecondary,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(14.dp))
                    // 导入 / 导出放在本地图**下方**——与封面页一致，
                    // 这两个动作作用于本地文件，理应贴着本地图而不是待在顶栏。
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TapChip(
                            label = "导入",
                            onClick = {
                                pickImageFile("选择艺术家图片") { Repo.importImage(it, artistMode = true) }
                            },
                            enabled = interactive,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TapChip(
                            label = "导出",
                            onClick = {
                                saveImageFile(suggestedName("artist", chosen)) { path ->
                                    Repo.exportCover(path, artistMode = true)
                                }
                            },
                            enabled = interactive && chosen != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Box(Modifier.width(0.5.dp).fillMaxHeight().background(palette.border))

                // ── 右：每源 3 格候选网格 ────────────────────────────────────
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("候选艺术家图", size = T.heading, color = palette.text, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        if (chosen != null) {
                            Txt(
                                text = "已勾选：${chosen.origin}",
                                size = T.caption,
                                color = palette.accent,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    val slots = Repo.networkArtistCovers
                    if (slots.isEmpty()) {
                        EmptyState(
                            glyph = "☺",
                            title = if (Repo.artistLoading) "正在检索艺术家图…" else "还没有结果",
                            hint = "换一首曲目会重新检索",
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                    } else {
                        slots.chunked(3).forEach { rowSlots ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowSlots.forEach { slot ->
                                    when (slot) {
                                        is CoverTile.Ready ->
                                            ArtistTileCard(
                                                candidate = slot.candidate,
                                                selected = slot.candidate.id == chosen?.id,
                                                onClick = { Repo.chooseArtistCover(slot.candidate) },
                                                modifier = Modifier.weight(1f),
                                            )

                                        is CoverTile.Searching ->
                                            SourceLoadingTile(
                                                label = slot.label,
                                                index = slot.index,
                                                modifier = Modifier.weight(1f),
                                            )

                                        is CoverTile.Empty ->
                                            SourceEmptyTile(
                                                label = slot.label,
                                                modifier = Modifier.weight(1f),
                                            )
                                    }
                                }
                                repeat(3 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 一张艺术家图候选，右下角带勾选框。 */
@Composable
private fun ArtistTileCard(
    candidate: CoverCandidate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.cardRaised)
                    .border(
                        width = if (selected) 2.dp else 0.5.dp,
                        color = if (selected) palette.accent else palette.border,
                        shape = RoundedCornerShape(10.dp),
                    ).clickable(onClick = onClick),
        ) {
            if (candidate.image != null) {
                Image(
                    bitmap = candidate.image,
                    contentDescription = candidate.origin,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconArtist(color = palette.textTertiary, modifier = Modifier.fillMaxSize(0.4f))
                }
            }
            CheckMark(
                selected = selected,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        // 图下一行：左边来源、右边分辨率与大小。
        // 右侧信息先占位、不参与压缩；来源拿剩下的宽度，放不下才省略。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(
                text = candidate.origin,
                size = T.micro,
                color = if (selected) palette.accent else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Txt(
                text = candidate.info,
                size = T.micro,
                color = palette.textTertiary,
                maxLines = 1,
            )
        }
    }
}
