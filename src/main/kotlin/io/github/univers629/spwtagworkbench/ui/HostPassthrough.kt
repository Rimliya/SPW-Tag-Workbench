// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.xuncorp.voxzen.ui.navigation.AppNavigation
import io.github.univers629.spwtagworkbench.router.ShellState

private data class HostPlacement(
    val hidden: Boolean,
) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            if (!hidden) child.placeRelative(0, 0)
        }
    }
}

@Composable
fun RetainedHostContent(
    hidden: Boolean,
    content: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
    Box(Modifier.fillMaxSize().then(HostPlacement(hidden))) { content() }
}

/** Independent-plugin only. The embedded Combined build excludes this file. */
@Composable
fun HostPassthrough() {
    ShellState.replaceInvocations += 1
    RetainedHostContent(hidden = ShellState.onScreen) { AppNavigation() }
    SideEffect { ShellState.replaceAlive = true }
}
