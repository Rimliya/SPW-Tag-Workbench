// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import io.github.univers629.spwtagworkbench.core.Diag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 频谱渲染。
 *
 * 解码用宿主自带的 BASS：**只创建自己的 decode 流，绝不 init/shutdown 宿主引擎**。
 * 每个流用完立刻 `BASS_StreamFree`，切歌/切页/插件停止都不留句柄。
 *
 * 渲染要点：
 * - 双声道上下分栏，各自横轴时间、纵轴频率
 * - 幅度转 dB 再映射亮度；**色相随频率变化**，不是单色深浅
 * - 颜色查表 256×256 预计算，避免每像素实时算色彩函数
 * - 直接写像素数组，不逐个画矩形
 * - 分批计算，先发布部分结果
 */
object Spectrogram {
    private const val BASS_SAMPLE_FLOAT = 0x100

    /** BASS_Init 的 flags：解码输出浮点采样，省掉读 PCM 时的整型转换。 */
    private const val BASS_DEVICE_DECODEFLOAT = 0x8000

    /** BASS_ErrorGetCode 返回值：BASS 尚未初始化。 */
    private const val BASS_ERROR_INIT = 8

    private const val BASS_STREAM_DECODE = 0x200000
    private const val BASS_UNICODE = 0x80000000.toInt()
    private const val BASS_POS_BYTE = 0
    private const val BASS_DATA_FFT_INDIVIDUAL = 0x10
    private const val BASS_DATA_FFT_REMOVEDC = 0x40
    private const val BASS_DATA_FFT4096 = 0x80000004.toInt()

    private const val FFT_SIZE = 4096
    private const val FFT_BINS = FFT_SIZE / 2
    private const val MIN_DB = -80.0
    private const val MAX_DB = -10.0

    const val IMAGE_WIDTH = 760
    const val IMAGE_HEIGHT = 260
    private const val COLUMN_BATCH = 24

    /** 一次渲染的结果；[partial] 为 true 表示还有更多列在路上。 */
    data class Result(
        /** 左右声道画在同一张图里：左半为 L，右半为 R（与参考实现一致） */
        val image: ImageBitmap,
        val displayChannels: Int,
        val envelope: FloatArray,
        val progress: Float,
        val partial: Boolean,
        val durationMs: Long,
        /**
         * 源采样率。频率轴的上限 = 采样率 / 2（奈奎斯特频率），
         * 界面据此画自适应刻度，而不是写死 16k/8k/4k。
         */
        val sampleRate: Int = 44100,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Failure(
        val reason: String,
    )

    /** 渲染回调。回调都在后台线程，UI 侧自行切主线程。 */
    interface Listener {
        fun onProgress(result: Result)

        fun onFinished(result: Result?)

        fun onFailed(failure: Failure)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * 渲染整首歌的频谱。阻塞调用，必须在后台线程执行。
     */
    fun render(
        path: String,
        listener: Listener,
    ) {
        cancelled = false
        if (path.isBlank()) {
            listener.onFailed(Failure("曲目没有文件路径"))
            return
        }

        val bass =
            try {
                loadBass()
            } catch (t: Throwable) {
                Diag.log("spectrum: 加载 bass 失败 ${t.javaClass.simpleName}")
                listener.onFailed(Failure("未找到 SPW 内置音频解码器"))
                return
            }

        var stream = 0
        try {
            val widePath = Path.of(path).toAbsolutePath().toString()
            // BASS_UNICODE 要求 UTF-16 指针：传 String 会按 ANSI 解，解码器打不开文件（错误码 2）
            stream =
                bass.BASS_StreamCreateFile(
                    false,
                    com.sun.jna.WString(widePath),
                    0L,
                    0L,
                    BASS_SAMPLE_FLOAT or BASS_STREAM_DECODE or BASS_UNICODE,
                )
            if (stream == 0) {
                val code = bass.BASS_ErrorGetCode()
                Diag.log("spectrum: StreamCreateFile 失败 code=$code path=$widePath")
                listener.onFailed(Failure(friendly(code)))
                return
            }

            val info = BassChannelInfo()
            if (!bass.BASS_ChannelGetInfo(stream, info)) {
                listener.onFailed(Failure(friendly(bass.BASS_ErrorGetCode())))
                return
            }
            val sourceChannels = max(1, info.chans)
            val displayChannels = min(2, sourceChannels)
            val byteLength = bass.BASS_ChannelGetLength(stream, BASS_POS_BYTE)
            val seconds = bass.BASS_ChannelBytes2Seconds(stream, byteLength)
            val durationMs = (seconds * 1000.0).toLong()
            if (byteLength <= 0L || durationMs <= 0L) {
                listener.onFailed(Failure("无法读取音频长度"))
                return
            }

            val width = IMAGE_WIDTH
            val height = IMAGE_HEIGHT
            val gap = if (displayChannels == 2) 2 else 0
            val channelWidth = (width - gap) / displayChannels

            val pixels = IntArray(width * height)
            val levels = IntArray(width * height)
            val envelope = FloatArray(width)
            val fft = FloatArray(FFT_BINS * sourceChannels)

            // 顺序解码：每读一个 FFT 窗口推进一帧，帧号映射到画布列。
            // 不 seek —— 对 decode 流逐列定位既慢又容易出原生问题。
            val sampleRate = max(1, if (info.freq > 0) info.freq else 44100)
            val expected = max(1L, kotlin.math.ceil(seconds * sampleRate / FFT_SIZE).toLong())
            var frame = 0L
            var lastX = -1
            var lastPublishX = 0

            while (frame < expected && lastX < channelWidth - 1) {
                if (cancelled) {
                    listener.onFinished(null)
                    return
                }
                val got =
                    bass.BASS_ChannelGetData(
                        stream,
                        fft,
                        BASS_DATA_FFT4096 or BASS_DATA_FFT_INDIVIDUAL or BASS_DATA_FFT_REMOVEDC,
                    )
                if (got < 0) break

                // 每列代表一个时间片；列号范围是「单声道宽度」，不是整图宽度
                val x = min(channelWidth - 1, (frame * channelWidth / expected).toInt())
                var fill = lastX + 1
                while (fill <= x) {
                    paintColumn(
                        x = fill,
                        fft = fft,
                        sourceChannels = sourceChannels,
                        displayChannels = displayChannels,
                        channelWidth = channelWidth,
                        gap = gap,
                        width = width,
                        height = height,
                        pixels = pixels,
                        levels = levels,
                        envelope = envelope,
                    )
                    fill++
                }
                lastX = x
                frame++

                if (x - lastPublishX >= COLUMN_BATCH) {
                    lastPublishX = x
                    listener.onProgress(
                        Result(
                            image = argbToImage(pixels, width, height),
                            displayChannels = displayChannels,
                            envelope = envelope.copyOf(),
                            progress = x.toFloat() / channelWidth,
                            partial = true,
                            durationMs = durationMs,
                            sampleRate = sampleRate,
                        ),
                    )
                }
            }

            fillEnvelopeGaps(envelope)
            val final =
                Result(
                    image = argbToImage(pixels, width, height),
                    displayChannels = displayChannels,
                    envelope = envelope,
                    progress = 1f,
                    partial = false,
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                )
            Diag.log("spectrum: done ${Path.of(path).fileName} ${width}x$height ch=$sourceChannels frames=$frame")
            listener.onFinished(final)
        } catch (t: Throwable) {
            Diag.log("spectrum: 渲染失败 ${t.javaClass.simpleName} ${t.message.orEmpty().take(80)}")
            listener.onFailed(Failure(t.message?.take(80) ?: "频谱渲染失败"))
        } finally {
            if (stream != 0) {
                // 只释放自己创建的流；宿主的引擎不碰
                try {
                    bass.BASS_StreamFree(stream)
                } catch (_: Throwable) {
                    Diag.log("spectrum: StreamFree 失败")
                }
            }
        }
    }

    /** 把一帧 FFT 画到指定列。 */
    private fun paintColumn(
        x: Int,
        fft: FloatArray,
        sourceChannels: Int,
        displayChannels: Int,
        channelWidth: Int,
        gap: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
        levels: IntArray,
        envelope: FloatArray,
    ) {
        for (display in 0 until displayChannels) {
            val source = if (sourceChannels == 2) display else 0
            val left = display * (channelWidth + gap)
            for (y in 0 until height) {
                val ratio = (height - 1.0 - y) / max(1.0, (height - 1).toDouble())
                val bin = max(1, min(FFT_BINS - 1, (ratio * (FFT_BINS - 1)).roundToInt()))
                if (bin <= 2) continue // 前几个 bin 是恒定底噪，画出来是一条亮横线
                val magnitude = fft[bin * sourceChannels + source]
                val db = 20.0 * log10(max(1e-9, magnitude.toDouble()))
                val level = ((255.0 * (db - MIN_DB) / (MAX_DB - MIN_DB)).roundToInt()).coerceIn(0, 255)
                val index = y * width + left + x
                if (level > levels[index]) {
                    levels[index] = level
                    pixels[index] = colorFor(level, ratio)
                }
            }
        }

        // 概览条包络
        var energy = 0.0
        var count = 0
        var bin = 1
        while (bin < FFT_BINS) {
            for (channel in 0 until sourceChannels) {
                val m = fft[bin * sourceChannels + channel]
                energy += m * m
                count++
            }
            bin += 2
        }
        val rms = sqrt(energy / max(1, count))
        // x 是声道内列号（0..channelWidth-1）；包络数组按整幅宽度：
        // 映射到全宽索引，否则只有左半段有值，彩带只画一半。
        val envX = if (channelWidth <= 1) {
            0
        } else {
            ((x.toFloat() / (channelWidth - 1)) * (width - 1)).roundToInt().coerceIn(0, width - 1)
        }
        envelope[envX] = max(envelope[envX], (levelOf(rms * 10.0) / 255f))
    }

    // ── 图像构建 ────────────────────────────────────────────────────────────

    /**
     * ARGB IntArray → ImageBitmap。
     *
     * skiko 的 N32 在小端上是 BGRA 字节序，这里转一次再 installPixels。
     */
    fun argbToImage(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ImageBitmap {
        // 走 AWT：setRGB 一次性灌像素，再转 Compose 位图。
        // 比逐像素建 skiko Bitmap 简单，也不受 installPixels 重载解析的影响。
        val buffer = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buffer.setRGB(0, 0, width, height, pixels, 0, width)
        return buffer.toComposeImageBitmap()
    }

    // ── 色表 ────────────────────────────────────────────────────────────────

    private fun levelOf(magnitude: Double): Int {
        val db = 20.0 * log10(max(1e-9, magnitude))
        return ((255.0 * (db - MIN_DB) / (MAX_DB - MIN_DB)).roundToInt()).coerceIn(0, 255)
    }

    /**
     * 颜色查找表：`level × 256 + tBucket`。
     * 每像素一次查表，避免实时算色彩函数。
     */
    private val lut: IntArray by lazy {
        val table = IntArray(256 * 256)
        for (level in 0..255) {
            for (bucket in 0..255) {
                table[level * 256 + bucket] = computeColor(level, bucket / 255.0)
            }
        }
        table
    }

    private fun colorFor(
        level: Int,
        frequencyRatio: Double,
    ): Int {
        val t = sqrt(frequencyRatio.coerceIn(0.0, 1.0))
        val bucket = (t * 255.0).roundToInt().coerceIn(0, 255)
        return lut[level.coerceIn(0, 255) * 256 + bucket]
    }

    /** 色相随频率推进（低频红黄 → 中频绿青 → 高频蓝紫），亮度由 dB 决定。 */
    private fun computeColor(
        level: Int,
        t: Double,
    ): Int {
        val from: IntArray
        val to: IntArray
        val local: Double
        when {
            t < 0.14 -> {
                from = intArrayOf(255, 92, 24)
                to = intArrayOf(255, 218, 0)
                local = t / 0.14
            }

            t < 0.36 -> {
                from = intArrayOf(255, 218, 0)
                to = intArrayOf(31, 222, 76)
                local = (t - 0.14) / 0.22
            }

            t < 0.62 -> {
                from = intArrayOf(31, 222, 76)
                to = intArrayOf(0, 190, 215)
                local = (t - 0.36) / 0.26
            }

            t < 0.82 -> {
                from = intArrayOf(0, 190, 215)
                to = intArrayOf(42, 72, 204)
                local = (t - 0.62) / 0.20
            }

            else -> {
                from = intArrayOf(42, 72, 204)
                to = intArrayOf(91, 27, 156)
                local = (t - 0.82) / 0.18
            }
        }
        val strength = (level / 255.0).coerceIn(0.0, 1.0)
        val highlight = max(0.0, (strength - 0.58) / 0.42)
        val whiteMix = 0.42 * highlight * highlight * highlight
        val baseR = (from[0] + (to[0] - from[0]) * local).roundToInt()
        val baseG = (from[1] + (to[1] - from[1]) * local).roundToInt()
        val baseB = (from[2] + (to[2] - from[2]) * local).roundToInt()
        val r = (baseR + (255 - baseR) * whiteMix).roundToInt()
        val g = (baseG + (248 - baseG) * whiteMix).roundToInt()
        val b = (baseB + (222 - baseB) * whiteMix).roundToInt()
        val alpha = (255.0 * Math.pow(strength, 0.88)).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fillEnvelopeGaps(envelope: FloatArray) {
        var previous = -1
        for (x in envelope.indices) {
            if (envelope[x] > 0f) {
                if (previous >= 0 && x - previous > 1) {
                    val left = envelope[previous]
                    val right = envelope[x]
                    for (fill in previous + 1 until x) {
                        envelope[fill] = left + (right - left) * (fill - previous) / (x - previous).toFloat()
                    }
                }
                previous = x
            }
        }
    }

    // ── BASS 绑定 ───────────────────────────────────────────────────────────

    @Volatile
    private var native: BassNative? = null

    // 供 Waveform 复用同一个 BASS 连接：
    // BASS 是进程级单例库，重复 `Native.load` 会拿到同一份原生状态，
    // 但会多建一层 JNA 代理；统一走一个入口更稳妥。

    /**
     * BASS 加载/初始化的互斥锁。
     *
     * `bassOrNull()` 被 Spectrogram 与 Waveform 两个模块从不同协程调用
     * （打轴页波形 + 频谱页），而 BASS 大多数 API 非线程安全；
     * 之前靠「两页不会同时活跃」的巧合保证串行，现在显式加锁：
     * 首次加载（含探针与可能的 BASS_Init）全程互斥，杜绝并发初始化。
     */
    private val bassLock = Any()

    internal fun bassOrNull(): BassNative? =
        runCatching {
            synchronized(bassLock) { loadBass() }
        }.getOrNull()

    private fun loadBass(): BassNative {
        native?.let { return it }
        val path = locateBass()
        val loaded = Native.load(path.toString(), BassNative::class.java)
        // **初始化策略：先试「复用宿主已初始化的 BASS」，失败才自己 Init。**
        //
        // 宿主自己在用 BASS 播放，同一进程里的 bass.dll 是共享的，
        // 它的 BASS_Init 已执行过。此时再 Init 一次会因「已初始化」
        // 返回 false —— 但**不改用失败就放弃**：先探一次真实调用（试建解码流），
        // 能建成就直接复用，不必再 Init。
        val probe =
            runCatching {
                probeDecodeStream(loaded)
            }.getOrDefault(false)
        if (probe) {
            Diag.log("bass: 复用宿主已初始化的实例")
            native = loaded
            return loaded
        }
        // device=0 才是「默认设备」；-1 是无效编号，会导致 BASS_ERROR_DEVICE。
        val ok =
            runCatching {
                loaded.BASS_Init(
                    device = 0,
                    freq = 44100,
                    flags = BASS_DEVICE_DECODEFLOAT,
                    win = null,
                    clsid = null,
                )
            }.getOrDefault(false)
        Diag.log(
            if (ok) "bass: 已初始化" else "bass: BASS_Init 失败 code=${loaded.BASS_ErrorGetCode()}，波形/频谱不可用",
        )
        native = loaded
        return loaded
    }

    /**
     * 探针：试着建一个解码流再释放，判断 BASS 是否已可用（不必关心文件存不存在）。
     *
     * 用一个不存在的路径调 `BASS_StreamCreateFile`：
     * - BASS 已初始化 → 返回 0，错误码是 `BASS_ERROR_FILEOPEN(2)`
     * - BASS 未初始化 → 返回 0，错误码是 `BASS_ERROR_INIT(8)`
     * 区分这两者就知道能不能复用。
     */
    private fun probeDecodeStream(bass: BassNative): Boolean {
        // 接收返回值并在非零时释放：当前探针路径必然失败（返回 0），
        // 但若 BASS 行为变化导致意外建流成功，不释放就会漏一个解码句柄
        val handle =
            bass.BASS_StreamCreateFile(
                false,
                com.sun.jna.WString("__probe_not_exist__.bin"),
                0L,
                0L,
                BASS_STREAM_DECODE or BASS_UNICODE,
            )
        if (handle != 0) runCatching { bass.BASS_StreamFree(handle) }
        val code = bass.BASS_ErrorGetCode()
        // 2 = FILEOPEN（已初始化，只是文件不存在）；8 = INIT（未初始化）
        return code != BASS_ERROR_INIT
    }

    /** 宿主把 bass.dll 放在 resources 目录；按运行目录与系统属性两路找。 */
    private fun locateBass(): Path {
        val property = System.getProperty("compose.application.resources.dir").orEmpty()
        if (property.isNotBlank()) {
            val candidate = Path.of(property).resolve("bass.dll")
            if (Files.isRegularFile(candidate)) return candidate
        }
        val command =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("")
        if (command.isNotBlank()) {
            val exe = Path.of(command).toAbsolutePath()
            val candidate =
                exe.parent
                    .resolve("app")
                    .resolve("resources")
                    .resolve("bass.dll")
            if (Files.isRegularFile(candidate)) return candidate
        }
        throw IllegalStateException("未找到 bass.dll")
    }

    private fun friendly(code: Int): String =
        when (code) {
            2 -> "无法打开音频文件（解码器读不到该路径）"
            3 -> "无法打开音频文件"
            6 -> "SPW 音频引擎尚未初始化"
            20 -> "当前格式不受解码器支持"
            21 -> "音频编码器不可用"
            41 -> "音频文件无法解码"
            else -> "频谱解码失败（BASS $code）"
        }

    /** BASS_CHANNELINFO 的内存布局；JNA 按公有字段算偏移，必须 @JvmField。 */
    @Structure.FieldOrder("freq", "chans", "flags", "ctype", "origres", "plugin", "sample", "filename")
    class BassChannelInfo : Structure() {
        @JvmField
        var freq: Int = 0

        @JvmField
        var chans: Int = 0

        @JvmField
        var flags: Int = 0

        @JvmField
        var ctype: Int = 0

        @JvmField
        var origres: Int = 0

        @JvmField
        var plugin: Int = 0

        @JvmField
        var sample: Int = 0

        @JvmField
        var filename: com.sun.jna.Pointer? = null

        val channels: Int get() = chans
    }

    /**
     * 只声明用得到的几个符号。
     * 原生方法名必须保持 BASS 原始拼写，JNA 按名字解析。
     */
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    interface BassNative : Library {
        /**
         * 初始化 BASS。
         *
         * **必须先调用它**，否则后续 `BASS_StreamCreateFile` 一律返回 0
         * 且 `BASS_ErrorGetCode` 给出 8（`BASS_ERROR_INIT`）；漏掉这一步会导致
         * 波形与频谱全部无法打开。
         *
         * `device = 0` 是默认设备；`BASS_DEVICE_DECODEFLOAT (0x8000)` 让解码
         * 输出浮点采样，读 PCM 时不必再做整型转换。
         * 失败时（例如没有可用设备）返回 false，调用方应退化为无波形模式。
         */
        fun BASS_Init(
            device: Int,
            freq: Int,
            flags: Int,
            win: com.sun.jna.ptr.PointerByReference?,
            clsid: com.sun.jna.Pointer?,
        ): Boolean

        fun BASS_StreamCreateFile(
            mem: Boolean,
            file: com.sun.jna.WString,
            offset: Long,
            length: Long,
            flags: Int,
        ): Int

        fun BASS_ChannelGetInfo(
            handle: Int,
            info: BassChannelInfo,
        ): Boolean

        fun BASS_ChannelGetLength(
            handle: Int,
            mode: Int,
        ): Long

        fun BASS_ChannelBytes2Seconds(
            handle: Int,
            position: Long,
        ): Double

        fun BASS_ChannelGetData(
            handle: Int,
            buffer: FloatArray,
            length: Int,
        ): Int

        /** 秒 → 字节偏移。波形按时间区间取峰值时用。 */
        fun BASS_ChannelSeconds2Bytes(
            handle: Int,
            seconds: Double,
        ): Long

        /** 定位到指定字节偏移。读区间峰值前必须先定位。 */
        fun BASS_ChannelSetPosition(
            handle: Int,
            position: Long,
            mode: Int,
        ): Boolean

        fun BASS_StreamFree(handle: Int): Boolean

        fun BASS_ErrorGetCode(): Int
    }
}
