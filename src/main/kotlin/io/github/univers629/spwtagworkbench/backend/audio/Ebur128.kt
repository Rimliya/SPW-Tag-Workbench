// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.univers629.spwtagworkbench.core.Diag
import java.io.File
import kotlin.math.pow

/**
 * EBU R128 响度测量（ITU-R BS.1770）。
 *
 * 算法来自 [libebur128](https://github.com/jiixyj/libebur128)（作者 Jan Kokemüller，**MIT**），
 * 以原生 DLL 形式随插件分发（见仓库 `native/`），这里只做 JNA 绑定。
 *
 * **为什么不用纯 Kotlin 重写**：这个算法是逐采样的 IIR 滤波热点，C 版本能自动向量化，
 * 并依赖 SSE2 的 FTZ（flush-to-zero）消除 denormal 惩罚 —— JVM 两样都没有，
 * 重写会慢 2~3 倍且要承担数值偏差风险。原生 DLL 只有约 33 KB。
 *
 * **本模块不解码音频**：只接受 PCM 采样。解码由调用方负责
 * （插件里走宿主自带的 BASS，见 [Spectrogram]）。
 */
object Ebur128 {
    /** 集成响度可用（`ebur128_loudness_global`）。 */
    private const val MODE_I = (1 shl 2) or (1 shl 0)

    /** 采样峰值可用。 */
    private const val MODE_SAMPLE_PEAK = (1 shl 4) or (1 shl 0)

    /** 真实峰值可用（需要 192 kHz 重采样，开销更大）。 */
    private const val MODE_TRUE_PEAK = (1 shl 5) or (1 shl 0) or MODE_SAMPLE_PEAK

    /** 一次喂给原生库的最大帧数，避免单次调用持有过大的数组。 */
    private const val CHUNK_FRAMES = 1 shl 16

    /** 空音频时 libebur128 返回的地板值。 */
    const val SILENCE_LUFS = -70.0

    /**
     * 一次测量的结果。
     *
     * @property integratedLufs 集成响度（LUFS）
     * @property peakLinear 峰值线性幅度（0..1+，可直接写 `REPLAYGAIN_TRACK_PEAK`）
     * @property peakDbfs 峰值 dBFS（仅用于展示与诊断）
     */
    data class Result(
        val integratedLufs: Double,
        val peakLinear: Double,
        val peakDbfs: Double,
    ) {
        /**
         * 到达 [referenceLufs] 所需的增益（dB），可直接写 `REPLAYGAIN_TRACK_GAIN`。
         *
         * 公式：`gain = 参考响度 - 实测集成响度`
         */
        fun gainDb(referenceLufs: Double): Double = referenceLufs - integratedLufs
    }

    /** 原生库是否可用；不可用时 [measure] 抛异常，界面应回退到「不提供响度功能」。 */
    val available: Boolean by lazy { runCatching { library() }.isSuccess }

    /** 加载失败的原因，可用时为 null。 */
    val unavailableReason: String? by lazy {
        runCatching { library() }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }

    private val lib: Ebur128Lib by lazy { library() }

    private fun library(): Ebur128Lib = Native.load(Ebur128Loader.resolve().absolutePath, Ebur128Lib::class.java)

    /**
     * 测量一段交错（interleaved）PCM 的响度与峰值。
     *
     * @param pcm 交错浮点采样，长度必须是 `channels * frames`
     * @param channels 声道数
     * @param sampleRate 采样率
     * @param truePeak true 用 ITU-R BS.1770-4 的真实峰值（重采样，慢），
     *   false 用采样峰值（快，对响度标签通常足够）
     */
    fun measure(
        pcm: FloatArray,
        channels: Int,
        sampleRate: Int,
        truePeak: Boolean = false,
    ): Result =
        start(channels, sampleRate, truePeak).use { session ->
            session.feed(pcm)
            session.result()
        }

    /**
     * 开一个流式测量会话。**长音频应当用这个**，而不是先攒齐整段 PCM。
     *
     * 一首 4 分钟立体声 44.1 kHz 的器乐有约 2100 万个采样：
     * 存成 `FloatArray` 就是 84 MB，稍不注意就 OOM。流式喂入时内存占用是常数。
     *
     * 用法：
     * ```
     * Ebur128.start(channels, rate).use { session ->
     *     while ((read = bass.read(buf)) > 0) session.feed(buf, read / channels)
     *     val result = session.result()
     * }
     * ```
     */
    fun start(
        channels: Int,
        sampleRate: Int,
        truePeak: Boolean = false,
    ): Session {
        require(channels in 1..64) { "声道数非法: $channels" }
        require(sampleRate > 0) { "采样率非法: $sampleRate" }
        return Session(channels, sampleRate, truePeak)
    }

    /** 一次流式测量。非线程安全；用完必须 [close]。 */
    class Session internal constructor(
        private val channels: Int,
        sampleRate: Int,
        private val truePeak: Boolean,
    ) : AutoCloseable {
        private val mode = MODE_I or if (truePeak) MODE_TRUE_PEAK else MODE_SAMPLE_PEAK
        private val state: Pointer =
            lib.ebur128_init(channels, sampleRate.toLong(), mode)
                ?: error("ebur128_init 返回空指针（声道=$channels 采样率=$sampleRate 模式=$mode）")
        private val handle = PointerByReference(state)
        private var closed = false

        /** 声道数。 */
        val channelCount: Int get() = channels

        /** 已喂入的总帧数。 */
        var frames: Long = 0
            private set

        /**
         * 喂一段交错 PCM。
         *
         * @param pcm 交错浮点采样
         * @param frameCount 帧数；默认按 `pcm.size / channels` 推断。
         *   最后一块可能不足整帧，此时应显式传入向下取整的值。
         */
        fun feed(
            pcm: FloatArray,
            frameCount: Int = pcm.size / channels,
        ) {
            check(!closed) { "会话已关闭" }
            if (frameCount <= 0) return
            require(frameCount.toLong() * channels <= pcm.size) {
                "帧数 $frameCount 超过数组容量（${pcm.size} 个采样 / $channels 声道）"
            }
            val error = lib.ebur128_add_frames_float(state, pcm, frameCount.toLong())
            check(error == 0) { "ebur128_add_frames_float 失败: 错误码 $error" }
            frames += frameCount
        }

        /** 取当前结果。可以在喂入过程中反复调用（拿到的是「到目前为止」的响度）。 */
        fun result(): Result {
            check(!closed) { "会话已关闭" }
            check(frames > 0) { "还没有喂入任何采样" }

            val loudness = DoubleArray(1)
            check(lib.ebur128_loudness_global(state, loudness) == 0) { "取集成响度失败" }

            var peakLinear = 0.0
            for (ch in 0 until channels) {
                val out = DoubleArray(1)
                val error =
                    if (truePeak) {
                        lib.ebur128_true_peak(state, ch, out)
                    } else {
                        lib.ebur128_sample_peak(state, ch, out)
                    }
                if (error == 0 && out[0] > peakLinear) peakLinear = out[0]
            }

            return Result(
                integratedLufs = loudness[0],
                peakLinear = peakLinear,
                peakDbfs = peakToDbfs(peakLinear),
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            lib.ebur128_destroy(handle)
        }
    }

    /** 峰值线性幅度转 dBFS；0 视为静音地板。 */
    fun peakToDbfs(linear: Double): Double = if (linear <= 0.0) SILENCE_LUFS else 20.0 * kotlin.math.log10(linear)

    /** dBFS 转线性幅度，例如 -6 dBFS → 约 0.5012。 */
    fun dbfsToPeak(dbfs: Double): Double = 10.0.pow(dbfs / 20.0)
}

/**
 * `ebur128` 的公开接口。
 *
 * 只声明用到的 6 个；上游共导出 23 个（`native/README.md` 有完整清单）。
 *
 * `size_t` 在 Win64 上是 64 位，因此帧数用 `Long` —— **不要**用 `NativeLong`，
 * 那个在 Windows 上映射到 32 位 C `long`，会截断长音频的帧数。
 */
internal interface Ebur128Lib : Library {
    fun ebur128_init(
        channels: Int,
        samplerate: Long,
        mode: Int,
    ): Pointer?

    fun ebur128_add_frames_float(
        state: Pointer,
        src: FloatArray,
        frames: Long,
    ): Int

    fun ebur128_loudness_global(
        state: Pointer,
        out: DoubleArray,
    ): Int

    fun ebur128_sample_peak(
        state: Pointer,
        channel: Int,
        out: DoubleArray,
    ): Int

    fun ebur128_true_peak(
        state: Pointer,
        channel: Int,
        out: DoubleArray,
    ): Int

    fun ebur128_destroy(state: PointerByReference)
}

/**
 * 把随插件分发的 `ebur128.dll` 落盘，返回可加载的文件。
 *
 * `.spmod` 的打包规则是「`classes/` 收 Jar task 输出、`lib/` 只收 `.jar`」，
 * 所以裸 DLL 只能走 `src/main/resources` → `classes/` 这条路。
 *
 * 宿主会把 `classes/` 解压到磁盘，因此常态是直接命中 `file:` 协议、原地加载；
 * 万一运行在 jar 内（IDE 里跑测试就是这样），才解压到临时文件。
 */
internal object Ebur128Loader {
    private const val RESOURCE = "/native/win-x64/ebur128.dll"

    @Volatile
    private var cached: File? = null

    fun resolve(): File {
        cached?.let { if (it.isFile) return it }

        val resource = Ebur128Loader::class.java.getResource(RESOURCE)
            ?: error("插件内缺少 $RESOURCE（检查 native/build-native.ps1 是否已执行）")

        val file =
            if (resource.protocol == "file") {
                File(resource.toURI())
            } else {
                val temp = File.createTempFile("ebur128-", ".dll")
                temp.deleteOnExit()
                Ebur128Loader::class.java
                    .getResourceAsStream(RESOURCE)!!
                    .use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                temp
            }

        Diag.log("ebur128: 原生库就绪 ${file.absolutePath} (${file.length()} 字节)")
        cached = file
        return file
    }
}
