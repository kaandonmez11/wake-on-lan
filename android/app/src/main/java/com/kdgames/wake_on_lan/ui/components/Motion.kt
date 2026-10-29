package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.motionSpec

/** Basma ölçeği (spec 12.3): basılıyken 0.96 (`quick`), bırakınca `snappy` ile geri. */
const val PRESSED_SCALE = 0.96f

@Composable
fun Modifier.pressScale(pressed: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = motionSpec(if (pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Dokunulabilir öğe: ripple yok, yerine basma ölçeği. Tüm özel butonlar bunu kullanır.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    /** Basılı tutma (bağlam menüsü, S08); null ise yalnız tıklama. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .pressScale(pressed && enabled)
        .then(
            if (onLongClick == null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = role,
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = role,
                    onClickLabel = onClickLabel,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
            },
        )
}

/**
 * Durum metni: yukarı kayıp çıkar, aşağıdan girer + fade (`snappy`), genişlik akar (spec 12.3).
 */
@Composable
fun AnimatedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(motionSpec(SpringToken.Snappy, true)) togetherWith fadeOut(motionSpec(SpringToken.Snappy, true))
            } else {
                (slideInVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { it / 2 } +
                    fadeIn(SpringToken.Snappy.spec())) togetherWith
                    (slideOutVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { -it / 2 } +
                        fadeOut(SpringToken.Snappy.spec()))
            } using SizeTransform(clip = false) { _, _ -> SpringToken.Snappy.spec() }
        },
        label = "animatedText",
    ) { value ->
        Text(
            value, style = style, color = color, maxLines = maxLines, softWrap = maxLines != 1,
            overflow = if (maxLines == 1) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
        )
    }
}

/**
 * Haneli yuvarlanan sayı (spec 12.3): her hane bağımsız kayar; artışta yukarı, azalışta aşağı.
 * Hane sayısı değişirse yeni hane soldan belirir.
 */
@Composable
fun RollingNumber(
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val reduceMotion = LocalReduceMotion.current
    // Önceki değer yalnız yön için; state değil, kompozisyon sonrası güncellenir.
    val previous = remember { longArrayOf(value) }
    val text = value.toString()
    val increasing = value >= previous[0]
    SideEffect { previous[0] = value }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Sağdan hizalı anahtar: birler hanesi hep aynı slotta kalır.
        text.forEachIndexed { index, char ->
            val slotFromRight = text.length - 1 - index
            key(slotFromRight) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(motionSpec(SpringToken.Snappy, true)) togetherWith
                                fadeOut(motionSpec(SpringToken.Snappy, true))
                        } else {
                            val dir = if (increasing) 1 else -1
                            (slideInVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { it * dir } +
                                fadeIn(SpringToken.Snappy.spec())) togetherWith
                                (slideOutVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { -it * dir } +
                                    fadeOut(SpringToken.Snappy.spec()))
                        } using SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { digit ->
                    Text(digit.toString(), style = style, color = color)
                }
            }
        }
    }
}
