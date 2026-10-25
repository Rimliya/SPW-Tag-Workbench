// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core.i18n

/**
 * 插件界面支持的语言。
 *
 * 取值与宿主语言设置一一对应（宿主 `Language` 枚举的 `tag`）：
 * `zh` / `zh-TW` / `en` / `es` / `pt-BR` / `ru` / `uk` / `id` / `ja`。
 *
 * 韩语不在支持范围内：宿主 MiSans 不含谚文字形（实测谚文音节 0/11184），
 * 显示韩语只能依赖系统字体回退，与界面其余部分字形不一致。
 * 未识别的标签（含 `ko`）一律解析为 [EN]。
 */
enum class Lang(
    /** 资源文件名后缀（`i18n/strings-<tag>.properties`）。 */
    val tag: String,
) {
    ZH("zh"),
    ZH_TW("zh-TW"),
    EN("en"),
    ES("es"),
    PT_BR("pt-BR"),
    RU("ru"),
    UK("uk"),
    ID("id"),
    JA("ja"),
    ;

    companion object {
        /** 把 BCP-47 标签解析成受支持的语言；无法识别时返回 [EN]。 */
        fun resolve(tag: String): Lang {
            val t = tag.trim().replace('_', '-').lowercase()
            if (t.isEmpty()) return EN
            return when {
                t.startsWith("zh") -> if (t.contains("tw") || t.contains("hk") || t.contains("hant")) ZH_TW else ZH
                t.startsWith("en") -> EN
                t.startsWith("es") -> ES
                t.startsWith("pt") -> PT_BR
                t.startsWith("ru") -> RU
                t.startsWith("uk") -> UK
                t == "id" || t == "in" || t.startsWith("id-") -> ID
                t.startsWith("ja") -> JA
                else -> EN
            }
        }
    }
}
