// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

/**
 * 宿主界面语言。
 *
 * 读取点位（1.19.2 实测，未混淆）：`com.xuncorp.voxzen.util.AppConfig.getLanguageTag()`。
 * 该方法返回用户设置的语言标签，**空串表示跟随系统**；宿主启动时按该标签调用
 * `Locale.setDefault(Locale.forLanguageTag(tag))`，因此空串时应取系统默认语言。
 *
 * 语言切换只写配置，进程默认 Locale 在下次启动才更新；插件在启动时读一次即可。
 */
object HostLanguage {
    private const val APP_CONFIG = "com.xuncorp.voxzen.util.AppConfig"

    private val appConfig: Any? by lazy {
        runCatching {
            Class
                .forName(APP_CONFIG, false, HostLanguage::class.java.classLoader)
                .getField("INSTANCE")
                .get(null)
        }.getOrNull()
    }

    /** 用户设置的语言标签；空串表示跟随系统。 */
    fun settingTag(): String =
        runCatching {
            appConfig?.javaClass?.getMethod("getLanguageTag")?.invoke(appConfig) as? String
        }.getOrNull().orEmpty()

    /** 生效语言标签：有设置按设置，否则按系统默认。 */
    fun effectiveTag(): String {
        val tag = settingTag()
        return tag.ifBlank {
            java.util.Locale
                .getDefault()
                .toLanguageTag()
        }
    }
}
