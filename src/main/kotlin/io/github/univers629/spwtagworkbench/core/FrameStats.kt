// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

/**
 * 帧耗时统计：只在内存累加，每 [REPORT_SECONDS] 秒往日志写一次汇总。
 *
 * 为什么不能每帧直接 `Diag.log`：那是**同步写文件**，在 60fps 下等于每秒 60 次
 * 磁盘写 + 时间格式化，测量行为本身就会把被测对象拖慢，测出来的数字不可信。
 *
 * 用累计值 + 定期汇总：采样本身只有两次 `System.nanoTime()` 之间的几个算术操作，
 * 对被测代码的扰动可以忽略。
 */
object FrameStats {
    /** 汇总周期（秒）。太短会刷屏，太长看不出抖动。 */
    private const val REPORT_SECONDS = 10.0

    /** 一帧超过这个时长就算「掉帧」（ms）。60fps 的预算只有 16.7ms。 */
    const val FRAME_BUDGET_MS = 16.7

    private var windowStart = System.nanoTime()
    private var frames = 0
    private var totalMs = 0.0
    private var worstMs = 0.0
    private var dropped = 0

    /** 帧间隔统计：判断「均匀低帧率」还是「节奏不齐」。 */
    private var lastFrameNanos = 0L
    private var intervalMin = Long.MAX_VALUE
    private var intervalMax = 0L
    private var intervalTotal = 0L
    private var intervalCount = 0

    /** 单独统计某个片段的耗时，便于定位是谁慢。 */
    private var lastFrameStart = 0L

    /** 标记一帧开始。 */
    fun frameStart() {
        lastFrameStart = System.nanoTime()
    }

    /** 标记一帧结束并累计。 */
    fun frameEnd(tag: String = "") {
        val now = System.nanoTime()
        // 帧间隔：如果取到的是均匀的 ~28ms，说明帧率被压到 35fps；
        // 如果忽大忽小（如 8/50/12/60），说明是节奏抖动，体感更差。
        if (lastFrameNanos != 0L) {
            val gap = now - lastFrameNanos
            if (gap < intervalMin) intervalMin = gap
            if (gap > intervalMax) intervalMax = gap
            intervalTotal += gap
            intervalCount++
        }
        lastFrameNanos = now

        if (lastFrameStart == 0L) return
        val ms = (now - lastFrameStart) / 1_000_000.0
        lastFrameStart = 0L
        frames++
        totalMs += ms
        if (ms > worstMs) worstMs = ms
        if (ms > FRAME_BUDGET_MS) dropped++
        maybeReport(tag)
    }

    /**
     * 直接记录一段耗时（用于测量非帧循环代码，如波形计算、绘制）。
     *
     * **必须单独累计**，不能和 [frameEnd] 混用同一个窗口：
     * 帧循环每秒几十次、绘制每秒几十次，混在一起会互相淹没，
     * 每个标签都得不到独立汇总。
     */
    fun record(
        label: String,
        nanos: Long,
    ) {
        val ms = nanos / 1_000_000.0
        val acc = segments.getOrPut(label) { Segment() }
        acc.count++
        acc.total += ms
        if (ms > acc.worst) acc.worst = ms
        if (ms > FRAME_BUDGET_MS) acc.over++
    }

    private class Segment {
        var count = 0
        var total = 0.0
        var worst = 0.0
        var over = 0
    }

    private val segments = java.util.concurrent.ConcurrentHashMap<String, Segment>()

    /** 位置提交次数：和 fps 对比就知道宿主推送够不够密。 */
    private var posUpdates = 0

    fun countPositionUpdate() {
        posUpdates++
    }

    /** 调试快照：playing / 是否收到过位置 / 当前值。 */
    private var phPlaying: Boolean = false
    private var phHasPos: Boolean = false
    private var phValue: Long = 0L

    fun notePlayhead(
        playing: Boolean,
        hasPos: Boolean,
        pos: Long,
    ) {
        phPlaying = playing
        phHasPos = hasPos
        phValue = pos
    }

    /** 汇报各片段的累计耗时（由帧循环周期性调用）。 */
    fun reportSegments() {
        if (segments.isEmpty()) return
        val sb = StringBuilder()
        for ((label, acc) in segments) {
            val avg = if (acc.count > 0) acc.total / acc.count else 0.0
            sb
                .append(label)
                .append(": ")
                .append("n=")
                .append(acc.count)
                .append(" avg=")
                .append("%.2f".format(avg))
                .append("ms")
                .append(" worst=")
                .append("%.2f".format(acc.worst))
                .append("ms")
                .append(" over=")
                .append(acc.over)
                .append(" | ")
        }
        if (sb.isNotEmpty()) Diag.log("perf[segments]: $sb")
        segments.clear()
    }

    private fun maybeReport(tag: String) {
        val elapsed = (System.nanoTime() - windowStart) / 1_000_000_000.0
        if (elapsed < REPORT_SECONDS) return
        val avg = if (frames > 0) totalMs / frames else 0.0
        val fps = if (frames > 0) frames / elapsed else 0.0
        val gapAvg = if (intervalCount > 0) intervalTotal / intervalCount / 1_000_000.0 else 0.0
        val gapMin = if (intervalMin == Long.MAX_VALUE) 0.0 else intervalMin / 1_000_000.0
        val gapMax = intervalMax / 1_000_000.0
        Diag.log(
            "perf[${tag.ifBlank { "frame" }}]: " +
                "avg=${"%.2f".format(avg)}ms worst=${"%.2f".format(worstMs)}ms " +
                "fps=${"%.1f".format(fps)} pos=$posUpdates dropped=$dropped " + "playing=$phPlaying hasPos=$phHasPos val=$phValue | " +
                "gap avg=${"%.1f".format(gapAvg)} min=${"%.1f".format(gapMin)} max=${"%.1f".format(gapMax)}ms",
        )
        // 重置窗口
        windowStart = System.nanoTime()
        frames = 0
        totalMs = 0.0
        worstMs = 0.0
        dropped = 0
        intervalMin = Long.MAX_VALUE
        intervalMax = 0L
        intervalTotal = 0L
        intervalCount = 0
        posUpdates = 0
        reportSegments()
    }

    /** 打轴页进入时调用，让统计窗口从零开始，避免带上别的页面的数据。 */
    fun reset() {
        windowStart = System.nanoTime()
        frames = 0
        totalMs = 0.0
        worstMs = 0.0
        dropped = 0
        lastFrameStart = 0L
        lastFrameNanos = 0L
        intervalMin = Long.MAX_VALUE
        intervalMax = 0L
        intervalTotal = 0L
        intervalCount = 0
    }
}
