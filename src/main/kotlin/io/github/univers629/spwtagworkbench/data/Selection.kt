// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.data

import androidx.compose.runtime.mutableStateMapOf

/** 勾选的原因。勾选状态是「用户意图」+「匹配结果」的合成，不是单一布尔量。 */
enum class SelectReason {
    /** 用户手动勾选 */
    UserChecked,

    /** 匹配到高质量结果（EXACT / CORE） */
    MatchOk,

    /** 匹配到低质量结果（STRONG），需确认 */
    MatchWeak,

    /** 没有匹配到任何候选 */
    NoMatch,

    /** 匹配过程失败（网络等） */
    MatchFailed,
}

/** 一行的选择状态。 */
data class RowSelection(
    val checked: Boolean,
    val reason: SelectReason,
    /** 用户手动改过就不再被匹配结果自动改写 */
    val userOverrode: Boolean = false,
) {
    val needsConfirm: Boolean get() = reason == SelectReason.MatchWeak
    val failed: Boolean get() = reason == SelectReason.MatchFailed
}

/**
 * 曲库多选状态。
 *
 * 规则（与提示词一致）：
 * - 用户手动勾选/取消 → 记 [SelectReason.UserChecked] 并标 [RowSelection.userOverrode]
 * - 匹配出强结果 → 保持勾选；弱结果 → 保持勾选但标记需确认
 * - 没有匹配 → 自动取消勾选
 * - 匹配失败 → 保持勾选并标记可重试
 * - 用户覆盖过的行，匹配结果不再自动改动
 */
object Selection {
    private val rows = mutableStateMapOf<String, RowSelection>()

    val checkedKeys: Set<String>
        get() = rows.filterValues { it.checked }.keys.toSet()

    val checkedCount: Int get() = rows.count { it.value.checked }

    /** 需要用户确认的行（弱匹配）。 */
    val needsConfirmKeys: Set<String>
        get() = rows.filterValues { it.checked && it.needsConfirm }.keys.toSet()

    /** 匹配失败、可重试的行。 */
    val failedKeys: Set<String>
        get() = rows.filterValues { it.failed }.keys.toSet()

    fun rowOf(trackKey: String): RowSelection? = rows[trackKey]

    fun isChecked(trackKey: String): Boolean = rows[trackKey]?.checked == true

    /** 用户点击复选框。 */
    fun toggleByUser(trackKey: String) {
        if (trackKey.isEmpty()) return
        val current = rows[trackKey]
        val next = !(current?.checked ?: false)
        rows[trackKey] =
            RowSelection(
                checked = next,
                reason = SelectReason.UserChecked,
                userOverrode = true,
            )
    }

    fun setByUser(
        trackKey: String,
        checked: Boolean,
    ) {
        if (trackKey.isEmpty()) return
        rows[trackKey] = RowSelection(checked, SelectReason.UserChecked, userOverrode = true)
    }

    /** 全选 / 取消全选当前可见的曲目（用户操作，带覆盖标记）。 */
    fun setAllByUser(
        trackKeys: Collection<String>,
        checked: Boolean,
    ) {
        for (key in trackKeys) {
            if (key.isEmpty()) continue
            rows[key] = RowSelection(checked, SelectReason.UserChecked, userOverrode = true)
        }
    }

    /**
     * 匹配结果回写。
     *
     * 用户手动改过的行不自动改；其余按规则更新。
     */
    fun applyMatchResult(
        trackKey: String,
        reason: SelectReason,
    ) {
        if (trackKey.isEmpty()) return
        val current = rows[trackKey]
        if (current?.userOverrode == true) return
        rows[trackKey] =
            when (reason) {
                SelectReason.MatchOk -> RowSelection(checked = true, reason = reason)
                SelectReason.MatchWeak -> RowSelection(checked = true, reason = reason)
                SelectReason.NoMatch -> RowSelection(checked = false, reason = reason)
                SelectReason.MatchFailed -> RowSelection(checked = current?.checked ?: true, reason = reason)
                SelectReason.UserChecked -> current ?: RowSelection(true, reason, userOverrode = true)
            }
    }

    fun clear() {
        rows.clear()
    }

    /** 移除已经不存在的曲目，避免脏 key 累积。 */
    fun retainOnly(existing: Set<String>) {
        val stale = rows.keys.filter { it !in existing }
        for (key in stale) rows.remove(key)
    }
}
