// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import io.github.univers629.spwtagworkbench.core.Diag

/**
 * 中文简繁转换。
 *
 * 用 Windows 自带的 locale 映射（`LCMapStringEx`），不额外打包一份转换字典——
 * 那类字典动辄几百 KB，而且版权与维护都是负担。
 *
 * 非 Windows 或 kernel32 加载失败时**原样返回**，不抛异常：
 * 歌词编辑不该因为一个可选功能而整页崩掉。
 */
object ChineseText {
    private const val LCMAP_SIMPLIFIED_CHINESE = 0x02000000
    private const val LCMAP_TRADITIONAL_CHINESE = 0x04000000

    /** @param traditional true = 转繁体，false = 转简体 */
    fun convert(
        source: String,
        traditional: Boolean,
    ): String {
        if (source.isEmpty()) return source
        val input = source.toCharArray()
        val flag = if (traditional) LCMAP_TRADITIONAL_CHINESE else LCMAP_SIMPLIFIED_CHINESE
        val locale = if (traditional) "zh-TW" else "zh-CN"
        return runCatching {
            val required =
                Kernel32Holder.INSTANCE.LCMapStringEx(
                    WString(locale),
                    flag,
                    input,
                    input.size,
                    null,
                    0,
                    null,
                    null,
                    0,
                )
            if (required <= 0) return@runCatching source
            val output = CharArray(required)
            val written =
                Kernel32Holder.INSTANCE.LCMapStringEx(
                    WString(locale),
                    flag,
                    input,
                    input.size,
                    output,
                    output.size,
                    null,
                    null,
                    0,
                )
            if (written <= 0) source else String(output, 0, written)
        }.getOrElse { t ->
            Diag.log("chinese: 简繁转换失败 ${t.javaClass.simpleName}: ${t.message}")
            source
        }
    }

    /** 延迟加载 kernel32：非 Windows 环境下加载失败不应在类初始化时就炸掉。 */
    private object Kernel32Holder {
        val INSTANCE: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    }

    private interface Kernel32 : Library {
        // 方法名必须与 Win32 导出名完全一致，不能改成小驼峰。
        //
        // versionInformation / reserved 必须声明为**可空**并传 null：
        // JNA 5.13+ 给 Pointer 参数加了非空校验，传 `Pointer.NULL` 会抛
        // `NullPointerException: NULL must not be null`。
        @Suppress("ktlint:standard:function-naming")
        fun LCMapStringEx(
            localeName: WString,
            mapFlags: Int,
            source: CharArray,
            sourceLength: Int,
            destination: CharArray?,
            destinationLength: Int,
            versionInformation: Pointer?,
            reserved: Pointer?,
            sortHandle: Long,
        ): Int
    }
}
