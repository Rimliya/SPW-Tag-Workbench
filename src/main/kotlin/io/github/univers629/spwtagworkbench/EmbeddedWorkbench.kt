// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.HostLanguage
import io.github.univers629.spwtagworkbench.core.LocalPalette
import io.github.univers629.spwtagworkbench.core.PluginEnv
import io.github.univers629.spwtagworkbench.core.Settings
import io.github.univers629.spwtagworkbench.core.i18n.L10n
import io.github.univers629.spwtagworkbench.data.Drafts
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.router.ShellState
import io.github.univers629.spwtagworkbench.router.SpacePage
import io.github.univers629.spwtagworkbench.router.SpaceRouter
import io.github.univers629.spwtagworkbench.ui.NoIndication
import io.github.univers629.spwtagworkbench.ui.WideView
import io.github.univers629.spwtagworkbench.ui.rememberHostPalette

/**
 * Lifecycle adapter used when Tag Workbench is compiled inside another plugin.
 * It deliberately owns no SPW plugin identity, navigation hook, sidebar or overlay mount.
 */
object EmbeddedWorkbenchRuntime {
    @Volatile private var started = false
    val isStarted: Boolean get() = started

    @Synchronized
    fun start(pluginId: String, pluginVersion: String, spwVersion: String, pluginPath: String) {
        if (started) return
        PluginEnv.bind(pluginId)
        L10n.install(HostLanguage.effectiveTag())
        Settings.load()
        Drafts.sweepImageDir()
        Diag.session("embedded start v=$pluginVersion spw=$spwVersion path=$pluginPath")
        Repo.bootstrap()
        Repo.startPlaybackFollow()
        SpaceRouter.hardReset()
        ShellState.progress = 1f
        ShellState.open = true
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        Repo.shutdown()
        ShellState.reset()
        SpaceRouter.hardReset()
        started = false
    }

    @JvmStatic
    fun openSourcePage() {
        runCatching {
            check(java.awt.Desktop.isDesktopSupported()) { "系统不支持打开浏览器" }
            java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://github.com/Rimliya/SPW-Tag-Workbench"))
        }
    }
}

/** Full workbench UI with no sidebar/FAB and no host AppNavigation replacement. */
@Composable
fun EmbeddedWorkbenchContent(onClose: () -> Unit) {
    val palette = rememberHostPalette()
    LaunchedEffect(Unit) {
        Settings.refresh()
    }
    DisposableEffect(Unit) { onDispose { ShellState.reset() } }
    CompositionLocalProvider(
        LocalPalette provides palette,
        androidx.compose.foundation.LocalIndication provides NoIndication,
    ) {
        Box(Modifier.fillMaxSize().background(palette.page)) {
            WideView(onOpenLog = {}, onClose = onClose, embedded = true)
        }
    }
}
