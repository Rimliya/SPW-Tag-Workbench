// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.net

import io.github.univers629.spwtagworkbench.core.Diag

/**
 * 联网探针。
 *
 * 启动时做一次轻量连通性检查并写日志，网络可达性因此可以直接从
 * diag.log 判断，不需要先操作界面。
 */
object NetProbe {
    /** 可观察：界面据此显示"离线"。 */
    private val state = androidx.compose.runtime.mutableStateOf("未探测")

    val lastResult: String get() = state.value

    private fun publish(value: String) {
        state.value = value
    }

    /** 探测过且失败 → 视为离线。 */
    val offline: Boolean get() = lastResult.startsWith("不可访问") || lastResult.startsWith("探测异常")

    fun probe() {
        try {
            val response =
                Http.get(
                    url = "https://itunes.apple.com/search",
                    query = mapOf("term" to "test", "media" to "music", "entity" to "song", "limit" to "1"),
                    timeoutMs = 7000,
                    retries = 0,
                )
            publish(
                when {
                    response.ok -> "可访问（HTTP ${response.status}，${response.body.length} 字节）"
                    response.error.isNotBlank() -> "不可访问：${response.error}"
                    else -> "异常：HTTP ${response.status}"
                },
            )
        } catch (t: Throwable) {
            publish("探测异常：${t.javaClass.simpleName}")
        }
        Diag.log("net: ${state.value}")
    }
}
