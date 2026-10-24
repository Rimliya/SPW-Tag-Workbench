// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.Card
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.FieldBox
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.LocalPalette
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.fmtTime
import io.github.univers629.spwtagworkbench.core.sidebarSolid
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Status
import io.github.univers629.spwtagworkbench.data.Tags
import io.github.univers629.spwtagworkbench.data.TrackMeta
import io.github.univers629.spwtagworkbench.router.SharedKey
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpaceMetrics
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.DraggableFab
import io.github.univers629.spwtagworkbench.ui.NoIndication
import io.github.univers629.spwtagworkbench.ui.PanelEase
import io.github.univers629.spwtagworkbench.ui.PanelMs
import io.github.univers629.spwtagworkbench.ui.icons.IconChevronRight
import io.github.univers629.spwtagworkbench.ui.rememberHostPalette
import io.github.univers629.spwtagworkbench.ui.space.CoverThumb

/** 侧栏自身的开合。只控制这一个面板，不控制任何页面显隐。 */
private object SidebarUi {
    var open by mutableStateOf(false)
}

/**
 * 侧栏当前挂在哪一层。
 *
 * - [TOP]：宿主顶层（guide §9.2 层级 A），画在底栏与玻璃浮层之上 —— 由
 *   `MainPlugin.installTopLevelSidebar` 注册成功后设置
 * - [PAGE]：应用页层（层级 B），随 [io.github.univers629.spwtagworkbench.ui.Shell.PluginOverlay]
 *   渲染 —— 顶层注册失败时的退回位置
 *
 * 渲染侧据此二选一：[SidebarLayer]（顶层入口）只在 TOP 时画，
 * [SidebarHost]（页层入口）只在非 TOP 时画，避免双份。
 */
enum class SidebarLayerLocation { TOP, PAGE }

/** 侧栏当前所在层级（由 MainPlugin 注册结果决定）。 */
object SidebarLayerState {
    var location: SidebarLayerLocation = SidebarLayerLocation.PAGE
}

private val PanelWidth = 340.dp
private val PanelShape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)

/**
 * 层级 A（顶层）的标题栏安全高度（guide §9.16）。
 *
 * 宿主自绘标题栏实测约 44dp：右上角是窗口按钮区，左侧是功能按钮与拖动区。
 * 面板内容从这里之下开始，标题行不再被窗口按钮遮挡；面板背景仍从 0 铺满。
 */
private val TITLEBAR_SAFE_TOP = 44.dp

/**
 * 顶层（层级 A）渲染入口。
 *
 * guide §9.2 层级图：
 * ```
 * MainSurfaceContent
 *   ├ AppNavigation        ← 层级 B：当前 PluginOverlay 挂这里，会被底栏盖住
 *   ├ PlayerSurfaceGlass   ← 玻璃浮层
 *   └ PlayerBottomSheet    ← 播放条底栏
 * ```
 * 层级 A 画在以上全部之后 → 侧栏与 FAB 盖住底栏，不再被遮挡。
 *
 * 注意 guide §9.15 的**顶部安全区**：全局层从窗口 y=0 开始，
 * 顶部一段是宿主标题栏（窗口按钮 + 拖动区）。侧栏是右侧竖条、
 * FAB 在垂直居中附近，均不触顶，无需额外让位。
 */
@Composable
fun SidebarLayer() {
    if (SidebarLayerState.location != SidebarLayerLocation.TOP) return
    // 层级 A 挂在宿主自己的 composition 里，**拿不到** PluginOverlay 提供的
    // LocalPalette：不在这层重新提供的话，[Salt] 会退回默认的 darkSolid
    // （material = false），于是材质模式下 FAB 与面板反而全是不透明色。
    val palette = rememberHostPalette()
    CompositionLocalProvider(
        LocalPalette provides palette,
        // 与 PluginOverlay 保持一致：不画点击波纹
        androidx.compose.foundation.LocalIndication provides NoIndication,
    ) {
        SidebarContent()
    }
}

/**
 * 应用页层（层级 B）渲染入口 —— 顶层注册失败时的退回位置。
 *
 * 顶层挂载成功时（[SidebarHost.layer] == [SidebarLayerLocation.TOP]），
 * 侧栏由 [SidebarLayer] 在层级 A 渲染，这里直接跳过，避免双份。
 *
 * 形态：**上下到顶的右侧面板**，紧贴窗口右边缘，左侧两角圆角、右侧直角；
 * 半透明底 + 发丝描边，透出宿主材质；没有全屏遮罩 —— 它是侧边栏，不是模态框。
 *
 * 平时完全不存在，只有一颗悬浮按钮（FAB）在召唤。面板从右边缘向左滑出。
 */
@Composable
fun SidebarHost() {
    if (SidebarLayerState.location == SidebarLayerLocation.TOP) return
    // 层级 B 在 PluginOverlay 子树内，色板与波纹已在外面提供好
    SidebarContent()
}

/** 两个层级共用的实际内容。 */
@Composable
private fun SidebarContent() {
    if (!Settings.sidebarRail) return

    val palette = Salt
    val open = SidebarUi.open

    // 打开面板时若还没封面，立即补一次（不依赖之前是否加载成功）
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) {
            val meta = if (Repo.track.isPresent) Repo.track else Repo.focused()
            if (Repo.cover == null && meta.path.isNotBlank()) Repo.loadCovers(meta)
        }
    }
    // 宽视图展开过程中侧栏本体淡出，不跟着缩放
    val squeezed = 1f - ShellState.progress.coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            // 进出完全对称：同时长、同曲线
            enter =
                slideInHorizontally(
                    animationSpec = tween(PanelMs, easing = PanelEase),
                    initialOffsetX = { it },
                ) + fadeIn(tween(PanelMs, easing = PanelEase)),
            exit =
                slideOutHorizontally(
                    animationSpec = tween(PanelMs, easing = PanelEase),
                    targetOffsetX = { it },
                ) + fadeOut(tween(PanelMs, easing = PanelEase)),
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        ) {
            SidebarPanel(
                onClose = { SidebarUi.open = false },
                modifier =
                    Modifier
                        .width(PanelWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = squeezed },
            )
        }

        // 悬浮召唤按钮：默认停在窗口右侧垂直居中，可上下拖动，
        // 静置后自动贴边半隐，鼠标移过去再滑出来。
        DraggableFab(
            icon = { color ->
                io.github.univers629.spwtagworkbench.ui.icons.IconMusicTag(
                    color = color,
                    modifier = androidx.compose.ui.Modifier
                        .size(24.dp),
                )
            },
            expanded = open,
            onClick = { SidebarUi.open = !open },
        )
    }
}

@Composable
private fun SidebarPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // **侧栏面板取浮层配方、材质下压到 75%**（[Palette.sidebarSolid]，
    // 与悬浮按钮同一档不透明度；弹窗/下拉仍是 90%）。
    //
    // 半透明底在 Win11 材质下与背后内容重叠，所以用浮层那套校准过的实色底，
    // 只是把不透明度从 90% 降到 75%，让材质透出来一些。
    val palette = Salt.sidebarSolid
    val track = Repo.track
    val original = if (track.isPresent) track else Repo.focused()
    // 预览制：显示草稿合成后的值
    val meta =
        io.github.univers629.spwtagworkbench.data.Drafts
            .merge(original)

    // 贴右边：整块面板铺满高度，右边缘与窗口齐平。
    //
    // **顶部安全区（guide §9.16）**：层级 A 从窗口 y=0 开始铺，标题行会被
    // 宿主右上角的窗口按钮（最小化/最大化/关闭）遮挡。按 guide 的做法：
    // 背景照常从 0 铺满（面板底色连续，不留透明缺口），内容整体下移
    // TITLEBAR_SAFE_TOP，标题行落在安全区之下。
    // 该区域同时是窗口拖动区——面板内容不占据这里，指针不会被面板吃掉。
    Column(
        modifier =
            modifier
                .clip(PanelShape)
                .background(palette.cardOverlay)
                .border(0.5.dp, palette.border, PanelShape),
    ) {
        Spacer(Modifier.height(TITLEBAR_SAFE_TOP))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("音乐标签工作台", size = T.title, color = palette.text, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.hover)
                        .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Txt("✕", size = Glyph.inline, color = palette.textSecondary, maxLines = 1)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.border),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (!meta.isPresent) {
                Spacer(Modifier.height(20.dp))
                Txt(
                    text = "当前没有播放中的曲目。\n宿主开始播放后，这里会显示曲目信息。",
                    size = T.caption,
                    color = palette.textTertiary,
                    maxLines = 3,
                )
                return@Column
            }

            // 这张卡片就是进入工作台动画的锚点
            AnchorCard(meta)

            Spacer(Modifier.height(14.dp))
            val dirty = io.github.univers629.spwtagworkbench.data.Drafts.dirtyCount
            Txt(
                text = if (dirty > 0) "改动只在内存 · $dirty 首待保存" else "常用标签 · 编辑后点工作台保存",
                size = T.micro,
                color = if (dirty > 0) palette.warn else palette.textTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.height(9.dp))

            SidebarField("标题", meta.title, Tags.Key.Title, meta.hasFile)
            SidebarField("艺术家", meta.artist, Tags.Key.Artist, meta.hasFile)
            SidebarField("专辑", meta.album, Tags.Key.Album, meta.hasFile)
            SidebarField("流派", meta.genre, Tags.Key.Genre, meta.hasFile)

            Spacer(Modifier.height(10.dp))
            StatusLine()

            Spacer(Modifier.height(16.dp))
            OpenWorkbenchButton(onClose)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 基础信息卡片 —— 进入工作台动画的视觉锚点。
 * 每帧把整卡与封面 / 标题的屏幕矩形登记下来，切换时从这些矩形长出去。
 */
@Composable
private fun AnchorCard(meta: TrackMeta) {
    val palette = Salt
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    // 只在量到真实尺寸时登记，避免退场过程把零矩形写进去
                    val r = it.boundsInWindow()
                    if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchor(r)
                },
        color = palette.cardRaised,
        radius = 14.dp,
        outline = palette.border,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.onGloballyPositioned {
                        val r = it.boundsInWindow()
                        if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchorPart(SharedKey.Cover, r)
                    },
            ) {
                CoverThumb(64.dp, radius = 12.dp)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Box(
                    modifier =
                        Modifier.onGloballyPositioned {
                            val r = it.boundsInWindow()
                            if (r.width > 1f && r.height > 1f) SpaceMetrics.putAnchorPart(SharedKey.Title, r)
                        },
                ) {
                    Txt(
                        text = meta.title.ifBlank { "未知标题" },
                        size = T.label,
                        color = palette.text,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Txt(
                    text = meta.artist.ifBlank { "未知艺术家" },
                    size = T.caption,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Txt(
                    text = meta.album.ifBlank { "未知专辑" },
                    size = T.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Txt(
                        text = if (Repo.playing) "播放中" else "已暂停",
                        size = T.caption,
                        color = if (Repo.playing) palette.accent else palette.textTertiary,
                        maxLines = 1,
                    )
                    Txt(
                        text = "${fmtTime(Repo.positionMs)} / ${fmtTime(meta.durationMs)}",
                        size = T.caption,
                        color = palette.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarField(
    label: String,
    value: String,
    key: Tags.Key,
    editable: Boolean,
) {
    val palette = Salt
    Column(Modifier.padding(bottom = 9.dp)) {
        // 字段标签：caption(11)，不小于它下面 label(12) 的值，避免标签比内容还小
        Txt(label, size = T.caption, color = palette.textTertiary, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        FieldBox(
            value = value,
            onCommit = { Repo.stageField(key, it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "未填写",
            readOnly = !editable,
            size = T.label,
        )
    }
}

@Composable
private fun StatusLine() {
    val palette = Salt
    val status = Repo.status ?: return
    val color =
        when (status.kind) {
            Status.Kind.Ok -> palette.ok
            Status.Kind.Warn -> palette.warn
            Status.Kind.Error -> palette.error
            Status.Kind.Busy -> palette.accent
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Txt(status.text, size = T.caption, color = palette.textSecondary, maxLines = 2)
    }
}

@Composable
private fun OpenWorkbenchButton(onClose: () -> Unit) {
    val palette = Salt
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(palette.accent.copy(alpha = 0.16f))
                .clickable {
                    // 面板开着，AnchorCard 每帧在登记，此刻锚点一定新鲜
                    Diag.log(
                        "open workbench: anchor=" + SpaceMetrics.anchor() +
                            " cover=" + SpaceMetrics.anchorPart(SharedKey.Cover),
                    )
                    onClose()
                    // 焦点对齐到正在播放的那首，保证主视图与侧栏同曲
                    Repo.focusPlaying()
                    SpaceRouter.open(SpacePage.Desk)
                }.padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("打开工作台", size = T.label, color = palette.accent, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Txt(
                    text = "歌词 · 封面 · 频谱 · 打轴",
                    size = T.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                )
            }
            IconChevronRight(color = palette.accent, modifier = Modifier.size(14.dp))
        }
    }
}
