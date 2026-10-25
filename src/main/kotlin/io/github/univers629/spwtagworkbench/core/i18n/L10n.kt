// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core.i18n

import io.github.univers629.spwtagworkbench.backend.model.TagField
import io.github.univers629.spwtagworkbench.core.Diag
import io.github.univers629.spwtagworkbench.core.HostLanguage
import java.util.Properties

/**
 * 插件界面文案。
 *
 * 语言在插件启动时确定（见 [install]），之后界面直接读 [t]，不再随宿主切换语言变化。
 * 查表顺序：当前语言 → 英文 → 中文 → key 本身，保证任何缺失翻译都能显示可读文本。
 */
object L10n {
    private var current: Lang = Lang.ZH
    private var primary: Map<String, String> = emptyMap()
    private var english: Map<String, String> = emptyMap()
    private var chinese: Map<String, String> = emptyMap()

    /** 当前生效语言。 */
    val lang: Lang get() = current

    /** 按语言标签安装文案表；插件启动时调用一次。 */
    fun install(tag: String) {
        current = Lang.resolve(tag)
        chinese = load(Lang.ZH)
        english = load(Lang.EN)
        primary =
            when (current) {
                Lang.ZH -> chinese
                Lang.EN -> english
                else -> load(current)
            }
        Diag.log("i18n: tag=$tag lang=${current.tag} keys=${primary.size}")
    }

    /** 取文案；[args] 会按 `String.format` 规则替换占位符。 */
    fun t(
        key: String,
        vararg args: Any?,
    ): String {
        if (primary.isEmpty()) {
            install(HostLanguage.effectiveTag())
        }
        val raw = primary[key] ?: english[key] ?: chinese[key] ?: key
        return if (args.isEmpty()) raw else raw.format(*args)
    }

    /** 标签字段名（标题 / 艺术家 / 增益 …）。 */
    fun tagLabel(field: TagField): String = t(fieldKey(field))

    /** 标签字段名，按宿主字段枚举的固定顺序给键，未知字段回退到宿主原始标签。 */
    private fun fieldKey(field: TagField): String =
        when (field) {
            TagField.Title -> "field.title"
            TagField.Artist -> "field.artist"
            TagField.Album -> "field.album"
            TagField.Year -> "field.year"
            TagField.Track -> "field.track"
            TagField.Disc -> "field.disc"
            TagField.Genre -> "field.genre"
            TagField.AlbumArtist -> "field.albumArtist"
            TagField.Composer -> "field.composer"
            TagField.Lyricist -> "field.lyricist"
            TagField.Comment -> "field.comment"
            TagField.ReplayGainTrackGain -> "field.replayGainTrackGain"
            TagField.ReplayGainTrackPeak -> "field.replayGainTrackPeak"
            TagField.ReplayGainAlbumGain -> "field.replayGainAlbumGain"
            TagField.ReplayGainAlbumPeak -> "field.replayGainAlbumPeak"
            TagField.Lyrics -> field.tagKey
        }

    private fun load(lang: Lang): Map<String, String> {
        val name = "/i18n/strings-${lang.tag}.properties"
        val stream = L10n::class.java.getResourceAsStream(name) ?: run {
            Diag.log("i18n: 缺少资源 $name")
            return emptyMap()
        }
        return stream.use { input ->
            val properties = Properties()
            properties.load(input.reader(Charsets.UTF_8))
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }
    }
}
