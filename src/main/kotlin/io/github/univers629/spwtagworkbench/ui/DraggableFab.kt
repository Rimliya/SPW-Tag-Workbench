// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.router.SpaceMetrics
import kotlinx.coroutines.delay

/**
 * 可拖动、会自动贴边半隐的悬浮召唤按钮。
 *
 * 行为：
 * - **只能上下移动**：横向恒定贴在窗口右缘，位置只由 [offsetY] 决定
 * - **松手后整颗露出**，静置 [HIDE_DELAY_MS] 毫秒后自动滑出右缘，只留一半
 * - **鼠标进入立刻滑回**：半隐状态下不用点击也能召唤
 * - 被拖动时贴边而不是跳位：松手位置即新的纵向锚点
 *
 * 为什么不做左右移动：宿主右侧已有播放条与侧栏，横向漂移会遮挡它们；
 * 只允许纵向滑动既能避开遮挡，也不会把按钮留在窗口中间碍事。
 */
@Composable
fun DraggableFab(
    /** 按钮主图标，颜色由 FabButton 按展开状态决定。 */
    icon: @Composable (Color) -> Unit,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 按钮直径。贴边半隐时露出的宽度 = 直径的一半。 */
    diameter: androidx.compose.ui.unit.Dp = 44.dp,
    /**
     * 距窗口右缘的留白。
     *
     * 沿用改造前的 16dp，位置看起来才和以前一致（以前是 `padding(end = 16.dp)`）。
     */
    edgePadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val palette = Salt
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 纵向下移量（相对「垂直居中」这个基准），单位 px
    var offsetY by remember { mutableStateOf(0f) }
    // 是否已滑出（只留一半）
    var tucked by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 容器高度，用于把拖动量夹在可视范围内
    var containerHeight by remember { mutableStateOf(0f) }

    val diameterPx = with(density) { diameter.toPx() }
    val edgePaddingPx = with(density) { edgePadding.toPx() }

    /**
     * 收起时向右滑出的距离。
     *
     * **必须把右缘留白算进去**：按钮平时距窗口右缘 [edgePadding]（16dp），
     * 若只移动 `直径 × 比例`，前面那 16dp 全用来抵消留白，
     * 实际被窗口遮住的宽度 = 位移 − 留白，遮不到一半。
     *
     * 目标是让窗口遮住按钮的 [TUCK_RATIO]（约 2/3），
     * 所以位移 = 留白 + 直径 × 2/3。
     */
    val tuckDistance = edgePaddingPx + diameterPx * TUCK_RATIO
    val slide = remember { Animatable(0f) }

    // 展开侧栏时强制露出：用户正在用，不该半隐
    LaunchedEffect(expanded, tucked, dragging) {
        if (expanded || dragging || !tucked) {
            slide.animateTo(0f, tween(180))
        } else {
            slide.animateTo(tuckDistance, tween(260))
        }
    }

    // 静置计时：松手/移开鼠标后延时收起
    LaunchedEffect(hovered, dragging, expanded) {
        if (hovered || dragging || expanded) {
            tucked = false
            return@LaunchedEffect
        }
        delay(HIDE_DELAY_MS)
        if (!hovered && !dragging && !expanded) tucked = true
    }

    Box(
        modifier =
            modifier
                // 必须给满宽度：只写 fillMaxHeight 的话，Box 宽度会被里面的按钮撑成 44dp，
                // 于是 align(CenterEnd) 在 44dp 宽的盒子里对齐 = 整个按钮贴在窗口左边缘。
                .fillMaxSize()
                .onGloballyPositioned { containerHeight = it.size.height.toFloat() },
    ) {
        Box(
            modifier =
                Modifier
                    // 靠右对齐，并留出与宿主窗口右缘的距离（沿用改造前的 16dp）
                    .align(Alignment.CenterEnd)
                    .padding(end = edgePadding)
                    // 位移在绘制阶段做，不触发布局重算；
                    // 横向 translationX 用来做收起/滑出，纵向 translationY 是拖动结果。
                    .graphicsLayer {
                        translationY = offsetY
                        translationX = slide.value
                    }.onGloballyPositioned { SpaceMetrics.putFab(it.boundsInWindow()) }
                    .pointerInput(containerHeight, diameterPx) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                tucked = false
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // 只取纵向分量：横向位移被完全忽略
                                val maxOffset =
                                    ((containerHeight - diameterPx) / 2f - edgePaddingPx)
                                        .coerceAtLeast(0f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(-maxOffset, maxOffset)
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            FabButton(
                icon = icon,
                expanded = expanded,
                onClick = onClick,
                modifier =
                    Modifier
                        .size(diameter)
                        // 悬停热区覆盖整颗按钮（即使它被窗口遮住 2/3）。
                        // 收缩后露出只有 1/3 时鼠标仍能扫到它的边缘就滑出，
                        // 否则「藏起来就再也叫不出来」。
                        .onPointerEvent(PointerEventType.Enter) {
                            hovered = true
                            tucked = false
                        }.onPointerEvent(PointerEventType.Exit) {
                            hovered = false
                        },
            )
        }
    }
}

/** 静置多久后自动半隐。太短会毛躁，太长又显得「不会收」。 */
private const val HIDE_DELAY_MS = 1600L

/**
 * 收起时窗口遮住按钮的比例。
 *
 * 取 3/5：露出 2/5（直径 44dp → 可见约 17.6dp），
 * 既明显「有东西藏在那儿」，又不会挡住右边内容。
 * 不能遮得太狠——可见区域太小的话鼠标很难瞄准它。
 */
private const val TUCK_RATIO = 3f / 5f
