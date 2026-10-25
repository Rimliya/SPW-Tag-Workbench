// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.core.Glyph
import io.github.univers629.spwtagworkbench.core.Salt
import io.github.univers629.spwtagworkbench.core.T
import io.github.univers629.spwtagworkbench.core.Txt
import io.github.univers629.spwtagworkbench.core.i18n.L10n

/*
 * 多源候选网格里的两种占位卡，封面页与艺术家图页共用。
 *
 * 两页此前各有一份完全相同的实现，统一到这里避免双份维护。
 */

/** 该源正在检索的占位卡。 */
@Composable
internal fun SourceLoadingTile(
    label: String,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.card),
            contentAlignment = Alignment.Center,
        ) {
            Txt("…", size = Glyph.large, color = palette.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Txt("$label $index", size = T.micro, color = palette.textTertiary, maxLines = 1)
    }
}

/** 该源没有更多匹配的占位卡。 */
@Composable
internal fun SourceEmptyTile(
    label: String,
    modifier: Modifier = Modifier,
) {
    val palette = Salt
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.card)
                    .border(0.5.dp, palette.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Txt(L10n.t("tiles.noMoreMatches"), size = T.caption, color = palette.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Txt(label, size = T.micro, color = palette.textTertiary, maxLines = 1)
    }
}
