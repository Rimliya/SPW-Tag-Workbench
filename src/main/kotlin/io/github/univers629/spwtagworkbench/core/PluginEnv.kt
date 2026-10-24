// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File

/**
 * 宿主注入的本插件运行期信息。
 *
 * 插件 ID 与数据目录都由宿主给出，代码里不再抄写字面量：改 ID 时只需改
 * `build.gradle.kts` 的 `PluginId`，以及 `preference_config.json` 里
 * 无法在运行期求值的 `on_click`，不会再出现「改了清单忘了改代码」的偏差。
 */
object PluginEnv {
    /** 宿主注入的插件 ID；[bind] 之前为空串。 */
    var id: String = ""
        private set

    fun bind(pluginId: String) {
        id = pluginId
    }

    /**
     * 宿主分配给本插件的数据目录，即配置文件的父目录。
     *
     * 宿主 API 不可用时返回 null，由调用方决定退回行为。
     */
    val dataDir: File? by lazy {
        runCatching {
            WorkshopApi.manager
                .createConfigManager()
                .getConfig()
                .getConfigPath()
                .toFile()
                .parentFile
        }.getOrNull()
    }
}
