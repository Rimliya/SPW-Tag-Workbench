// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.net

import io.github.univers629.spwtagworkbench.core.Diag
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局网络闸门。
 *
 * 所有出站请求都必须经过这里，保证**同时最多 [MAX_CONCURRENT] 个在跑**。
 *
 * 为什么需要：
 * - 一次匹配会同时打 5~6 个源；封面页每源还要再下 2~3 张图，
 *   瞬时请求数能到 20 个，低配机器（4 核）直接被网络等待拖住；
 * - iTunes 官方限制约 20 次/分钟/IP，裸打批量匹配几首就会超限。
 *
 * 上限为什么是 8：
 * - 用户实机可能只有 4 个物理核，并发开满也吃不下；
 * - 8 个并发已经能覆盖「6 个源同时搜索」，再多只是排队。
 * [PEAK_LIMIT] 是硬上限，任何情况下都不允许超过 16。
 */
object NetGate {
    /**
     * 常规并发上限。
     *
     * 为什么是 12：
     * - 单次匹配最多 6 个源并发，8 够用；
     * - 但封面页是「4 个源 × 最多 6 张图并行下载」，峰值能到 24 个请求，
     *   8 会让图片排队，等到一张接一张地出，体验上就是「只有一张图」；
     * - 12 既能明显提速，又没到 [PEAK_LIMIT] 16 的极限，低配 4 核机器也吃得下。
     */
    const val MAX_CONCURRENT = 12

    /** 硬上限：绝对不允许超过这个值。 */
    const val PEAK_LIMIT = 16

    private val permits = Semaphore(MAX_CONCURRENT, true)

    private val inFlight = AtomicInteger(0)
    private val peakSeen = AtomicInteger(0)
    private val totalRequests = AtomicLong(0)
    private val totalWaitMs = AtomicLong(0)

    /**
     * 在闸门内执行一次网络操作。
     *
     * 这里是**阻塞式**获取许可：调用方都在 `Dispatchers.IO` 上，
     * 短暂阻塞一个 IO 线程换取全局并发可控，比让 20 个请求同时打出去划算。
     */
    fun <T> run(block: () -> T): T {
        val waitStart = System.nanoTime()
        permits.acquire()
        val waited = (System.nanoTime() - waitStart) / 1_000_000
        if (waited > 50) totalWaitMs.addAndGet(waited)

        val now = inFlight.incrementAndGet()
        peakSeen.updateAndGet { prev -> maxOf(prev, now) }
        totalRequests.incrementAndGet()
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
            permits.release()
        }
    }

    /** 诊断用：当前并发、历史峰值、累计请求数、累计排队时间。 */
    fun snapshot(): String =
        "net: inflight=${inFlight.get()} peak=${peakSeen.get()}/$PEAK_LIMIT " +
            "total=${totalRequests.get()} waitTotal=${totalWaitMs.get()}ms"

    /** 把统计写进日志（排查限流时用）。 */
    fun logSnapshot(tag: String) {
        Diag.log("$tag ${snapshot()}")
    }
}
