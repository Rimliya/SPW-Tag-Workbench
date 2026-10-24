// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

/**
 * 已完成源数 / 总源数；没有源时返回 null（顶栏不画进度条）。
 *
 * 源状态在这里**只以汇总比例呈现**，具体到「某个源搜到几条」的标签列表已不再显示：
 * 那排标签在顶栏占掉大半宽度，把进度条挤成中间一小截，而且字号放大后更挤。
 * 逐源细节需要时看 diag.log 里的 `artistCovers:` / `covers:` 行。
 */
fun sourceDoneFraction(states: List<Pair<String, String>>): Float? {
    if (states.isEmpty()) return null
    val done = states.count { !it.second.contains("搜索中") && !it.second.contains("检索中") }
    return done.toFloat() / states.size
}
