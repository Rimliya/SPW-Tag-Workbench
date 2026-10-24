// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.Image

/**
 * 宿主能力封装。所有对宿主内部的反射集中在这里，失败一律返回空值并在
 * [lastError] 里留下痕迹，绝不让插件因为宿主版本差异整体崩掉。
 */
object Host {
    private val loader: ClassLoader
        get() = Host::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    /** 最近一次反射失败的说明，便于在界面上给出可见终态。 */
    var lastError: String? = null
        private set

    private inline fun <T> guard(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (t: Throwable) {
            lastError = "$what: ${t.javaClass.simpleName} ${t.message.orEmpty().take(60)}"
            null
        }

    // ── AppConfig ────────────────────────────────────────────────────────────

    private val appConfig: Any? by lazy {
        guard("AppConfig") {
            Class
                .forName("com.xuncorp.voxzen.util.AppConfig", false, loader)
                .getField("INSTANCE")
                .get(null)
        }
    }

    private fun config(name: String): Any? = guard("AppConfig.$name") { appConfig?.javaClass?.getMethod(name)?.invoke(appConfig) }

    val windowStyle: String get() = config("getSpwWindowStyle")?.toString() ?: "None"

    /** 宿主是否启用了窗口材质（云母 / 亚克力 / 振动）。 */
    val hasWindowMaterial: Boolean
        get() = windowStyle != "None"

    /**
     * 是否深色主题。宿主 isDarkTheme 是 @Composable，无法反射调用，
     * 因此按指南的近似策略：显式 Light / Dark 直读，FollowSystem 退化为系统主题。
     */
    fun detectDark(): Boolean {
        when (config("getLightDarkTheme")?.toString()) {
            "Dark" -> return true
            "Light" -> return false
        }
        val system =
            guard("SystemTheme") {
                Class
                    .forName("org.jetbrains.skiko.SystemTheme_awtKt", false, loader)
                    .getMethod("getCurrentSystemTheme")
                    .invoke(null)
                    ?.toString()
            }
        return system == "DARK"
    }

    // ── 字体：直接复用宿主 classpath 里的字形资源 ─────────────────────────────

    private fun readResource(vararg paths: String): ByteArray? {
        for (path in paths) {
            val stream =
                guard("resource:$path") {
                    loader.getResourceAsStream(path) ?: ClassLoader.getSystemResourceAsStream(path)
                } ?: continue
            try {
                val bytes = stream.readBytes()
                if (bytes.isNotEmpty()) return bytes
            } catch (_: Throwable) {
                // 换下一条路径
            } finally {
                runCatching { stream.close() }
            }
        }
        return null
    }

    private fun family(vararg faces: Pair<String, Pair<String, FontWeight>>): FontFamily? {
        val fonts =
            faces.mapNotNull { (path, spec) ->
                val (identity, weight) = spec
                readResource(path)?.let { bytes ->
                    guard("font:$path") { Font(identity, bytes, weight) }
                }
            }
        if (fonts.isEmpty()) return null
        return guard("FontFamily") { FontFamily(fonts) }
    }

    /** 字体加载状态，便于在日志里确认到底用没用上宿主字体。 */
    var fontStatus: String = "未加载"
        private set

    private const val MISANS_FULL =
        "composeResources/voxzen.composeapp.generated.resources/font/misans.ttf"

    private const val MISANS_SHORT = "font/misans.ttf"

    /** 主字体：宿主 MiSans（dev23 指南 9.15），失败回退系统默认。 */
    val sans: FontFamily by lazy {
        val loaded =
            family(
                MISANS_FULL to ("HostMiSans" to FontWeight.Normal),
                MISANS_SHORT to ("HostMiSansShort" to FontWeight.Normal),
                "misans.ttf" to ("MiSansPlain" to FontWeight.Normal),
            )
        fontStatus = if (loaded != null) "MiSans 已加载" else "MiSans 加载失败，回退默认字体"
        loaded ?: FontFamily.Default
    }

    /** 等宽字体：用于时间码、数值对齐。 */
    val mono: FontFamily by lazy {
        family(
            "fonts/jetbrains-mono/JetBrainsMono-Regular.ttf" to ("HostMonoRegular" to FontWeight.Normal),
            "fonts/jetbrains-mono/JetBrainsMono-Medium.ttf" to ("HostMonoMedium" to FontWeight.Medium),
            "composeResources/voxzen.composeapp.generated.resources/font/jetbrains-mono/JetBrainsMono-Regular.ttf" to
                ("HostMonoAlt" to FontWeight.Normal),
        ) ?: sans
    }

    // ── Navigator ────────────────────────────────────────────────────────────

    private val localNavigator: ProvidableCompositionLocal<*>? by lazy {
        guard("LocalNavigator") {
            Class
                .forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", false, loader)
                .getMethod("getLocalNavigator")
                .invoke(null) as? ProvidableCompositionLocal<*>
        }
    }

    /** 只能在组合内读取。 */
    @Composable
    fun rememberNavigator(): Any? = localNavigator?.current

    /**
     * 宿主播放条底栏占用的高度（dp）。
     *
     * 取自宿主编译期常量：非浮动 74dp；浮动胶囊 16 + 64 + 24 = 104dp。
     * 开关在未混淆的 `com.xuncorp.voxzen.util.AppConfig.floatingMiniPlayer`，默认 true。
     * 读不到时按 104dp 保守预留。
     */
    fun miniPlayerReservedDp(): Int {
        val floating = guard("AppConfig.floatingMiniPlayer") {
            val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig", false, loader)
            val instance = cls.getField("INSTANCE").get(null)
            val prop = instance.javaClass.methods.firstOrNull {
                it.name == "getFloatingMiniPlayer"
            }
            prop?.invoke(instance) as? Boolean
        } ?: null
        return when (floating) {
            false -> 74
            true -> 104
            null -> 104
        }
    }

    /** 跳到本插件的配置页（宿主 ModConfigContent 路由）。 */
    fun openPluginConfig(
        navigator: Any?,
        pluginId: String,
    ): Boolean {
        navigator ?: return false
        return guard("Navigator.navigate(ModConfigContent)") {
            val routeCls =
                Class.forName(
                    "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ModConfigContent",
                    false,
                    loader,
                )
            val route = routeCls.getConstructor(String::class.java).newInstance(pluginId)
            val routeBase = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false, loader)
            val navCls = Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator", false, loader)
            navCls.getMethod("navigate", routeBase).invoke(navigator, route)
            true
        } ?: false
    }

    /** 让宿主 Navigator 执行返回。 */
    fun hostBack(navigator: Any?): Boolean =
        guard("Navigator.back") {
            navigator ?: return@guard false
            navigator.javaClass.getMethod("back").invoke(navigator)
            true
        } ?: false

    // ── 曲库刷新：写完标签后让宿主立即重读 ────────────────────────────────────

    /**
     * 触发宿主重读曲库。RepoService 是单例，rebuildMusicLibrary() 未混淆，
     * 调用后宿主列表与正在播放的元数据都会刷新。
     */
    fun reloadHostLibrary(): Boolean =
        guard("RepoService.rebuildMusicLibrary") {
            val repo =
                Class
                    .forName("com.xuncorp.voxzen.service.RepoService", false, loader)
                    .getField("INSTANCE")
                    .get(null)
                    ?: return@guard false
            repo.javaClass.getMethod("rebuildMusicLibrary").invoke(repo)
            true
        } ?: false

    // ── 图片 ────────────────────────────────────────────────────────────────

    fun decodeImage(bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        // 先走 AWT：频谱那条通路已验证可用，不依赖 skiko 原生库
        try {
            val buffered = javax.imageio.ImageIO.read(bytes.inputStream())
            if (buffered != null) {
                return buffered.toComposeImageBitmap()
            }
        } catch (t: Throwable) {
            lastError = "decodeImage(awt): ${t.javaClass.simpleName}"
        }
        // 兜底：skiko
        return try {
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (t: Throwable) {
            lastError = "decodeImage(skiko): ${t.javaClass.simpleName}"
            null
        }
    }

    /**
     * **降采样解码**：解码结果最长边不超过 [maxEdge] 像素。
     *
     * 为什么需要它：候选列表一次可能驻留几十张网络图。全尺寸解码意味着
     * 一张 1500×1500 的图占 ~9MB 位图内存（宿主 `-Xmx512M` +
     * `SoftMaxHeapSize=128M`，25 张候选最坏 ~250MB，足以打穿软上限、
     * 触发高频 GC）。列表与预览的显示尺寸不超过 320dp，
     * 降采样到 512px 后视觉无差、内存降一个数量级。
     *
     * 用 AWT ImageReader 的子采样（只在解码阶段跳行跳列读像素），
     * 比先全解再缩更快也更省。原始终终由调用方保留：写入标签用的是原始 `bytes`，
     * 这里降的只是「给界面看的位图」。
     */
    fun decodeImageSampled(
        bytes: ByteArray?,
        maxEdge: Int = 512,
    ): ImageBitmap? {
        val buffered = decodeBufferedSampled(bytes, maxEdge) ?: return null
        return runCatching { buffered.toComposeImageBitmap() }.getOrNull()
    }

    /**
     * 降采样解码到 AWT BufferedImage（纯 JVM 可测，不依赖 skiko native）。
     *
     * 用 AWT ImageReader 的子采样（只在解码阶段跳行跳列读像素），
     * 比先全解再缩更快也更省。返回 null 表示两条路径都失败。
     */
    fun decodeBufferedSampled(
        bytes: ByteArray?,
        maxEdge: Int = 512,
    ): java.awt.image.BufferedImage? {
        if (bytes == null || bytes.isEmpty()) return null
        try {
            val stream = javax.imageio.ImageIO.createImageInputStream(bytes.inputStream())
            stream.use { s ->
                val readers = javax.imageio.ImageIO.getImageReaders(s)
                if (!readers.hasNext()) return decodeBufferedFull(bytes)
                val reader = readers.next()
                try {
                    reader.input = s
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0) return decodeBufferedFull(bytes)
                    val scale =
                        maxOf(w, h).let { longest ->
                            if (longest <= maxEdge) 1 else (longest + maxEdge - 1) / maxEdge
                        }
                    if (scale == 1) return decodeBufferedFull(bytes)
                    val param = reader.defaultReadParam
                    param.setSourceSubsampling(scale, scale, 0, 0)
                    return reader.read(0, param) ?: decodeBufferedFull(bytes)
                } finally {
                    reader.dispose()
                }
            }
        } catch (t: Throwable) {
            lastError = "decodeImageSampled: ${t.javaClass.simpleName}"
            return decodeBufferedFull(bytes)
        }
    }

    /** 全尺寸 AWT 解码（降采样的退回路径，同样可测）。 */
    private fun decodeBufferedFull(bytes: ByteArray): java.awt.image.BufferedImage? =
        runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull()

    /**
     * 只读图片尺寸，不解码像素。
     *
     * 界面要在封面旁标「宽 × 高 px」，用 `ImageReader` 读头部即可，
     * 比完整解码快得多（大图解码可达几百毫秒）。
     */
    fun imageSize(bytes: ByteArray?): Pair<Int, Int> {
        if (bytes == null || bytes.isEmpty()) return 0 to 0
        return try {
            val stream = javax.imageio.ImageIO.createImageInputStream(bytes.inputStream())
            stream.use { s ->
                val readers = javax.imageio.ImageIO.getImageReaders(s)
                if (!readers.hasNext()) return 0 to 0
                val reader = readers.next()
                try {
                    reader.input = s
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (t: Throwable) {
            lastError = "imageSize: ${t.javaClass.simpleName}"
            0 to 0
        }
    }
}
