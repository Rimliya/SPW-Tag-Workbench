// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 插件设置。读写宿主提供的配置文件，并在设置页改动时实时同步到界面。
 */
object Settings {
    var sidebarRail by mutableStateOf(true)
        private set

    /**
     * 主视图是否显示封面下方那两个「艺术家封面」小槽。
     *
     * 由宿主「模组管理」页的 `view.showArtistSlots` 开关控制——插件的所有设置项
     * 统一在那里改，插件自己不再维护一份界面。这里只是把值读进内存供界面使用。
     */
    var showArtistSlots by mutableStateOf(true)

    /**
     * 批量匹配时「已有标签」的处理策略，取值见 [io.github.univers629.spwtagworkbench.data.MatchingPolicy]。
     *
     * 由宿主「模组管理」页的 `matching.policy` 列表项控制。
     */
    var matchingPolicy by mutableStateOf("fill")
        private set

    /**
     * 强制不透明：`replaceComposable` 失败时置位。
     * 那种情况下宿主内容还在自己画，让页面透明不会透出材质，只会露出宿主 UI。
     */
    var forceOpaque by mutableStateOf(false)

    /**
     * 各音源启用状态。
     *
     * 默认值与 `preference_config.json` 里声明的 `sources.<id>.enabled` 保持一致；
     * 真实值由 [read] 从配置读入，宿主「模组管理」页改完即生效。
     */
    private val sourceEnabled =
        mutableStateMapOf(
            "itunes" to true,
            "lrclib" to true,
            "netease" to true,
            "qqmusic" to true,
            "kugou" to true,
        )

    fun isSourceEnabled(id: String): Boolean = sourceEnabled[id] ?: true

    fun setSourceEnabled(
        id: String,
        enabled: Boolean,
    ) {
        sourceEnabled[id] = enabled
        runCatching {
            val config = helper ?: return@runCatching
            config.set("sources.$id.enabled", enabled)
            config.save()
        }
    }

    private var helper: com.xuncorp.spw.workshop.api.config.ConfigHelper? = null
    private var listenerRegistered = false

    fun load() {
        runCatching {
            val manager = WorkshopApi.manager.createConfigManager()
            // 与 preference_config.json 里声明的 config 文件名一致
            val config = manager.getConfig("config.json").also { runCatching { it.reload() } }
            helper = config
            read(config)
            if (!listenerRegistered) {
                listenerRegistered = true
                manager.addConfigChangeListener { changed -> read(changed) }
            }
        }
    }

    /**
     * 重新从磁盘读一次配置。
     *
     * 宿主配置页的写入**不会**触发 [addConfigChangeListener]——那个回调只在
     * 插件自己 `save()` 时来。所以从宿主配置页改完开关回到工作台时，
     * 内存里的值还是旧的，表现就是「改了设置但主视图没变」。
     *
     * 工作台每次打开时调用本方法，即可让外部改动生效。
     */
    fun refresh() {
        val config = helper ?: return
        runCatching {
            config.reload()
            read(config)
        }
    }

    private fun read(config: com.xuncorp.spw.workshop.api.config.ConfigHelper) {
        sidebarRail =
            runCatching {
                config.get<Boolean>("sidebar.rail", true) ?: true
            }.getOrDefault(true)
        showArtistSlots =
            runCatching {
                config.get<Boolean>(SHOW_ARTIST_SLOTS_KEY, true) ?: true
            }.getOrDefault(true)
        matchingPolicy =
            runCatching {
                config.get<String>(MATCHING_POLICY_KEY, "fill") ?: "fill"
            }.getOrDefault("fill")
        // 音源开关也从配置读，宿主「模组管理」页改完才能反映到工具栏下拉
        for (id in sourceEnabled.keys.toList()) {
            sourceEnabled[id] =
                runCatching {
                    config.get<Boolean>("sources.$id.enabled", true) ?: true
                }.getOrDefault(true)
        }
    }

    private const val SHOW_ARTIST_SLOTS_KEY = "view.showArtistSlots"
    private const val MATCHING_POLICY_KEY = "matching.policy"
}
