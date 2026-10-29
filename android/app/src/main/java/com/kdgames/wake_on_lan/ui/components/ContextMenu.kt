package com.kdgames.wake_on_lan.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec

data class MenuItem(val label: String, val symbol: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * Basılı tutulan noktanın pencere koordinatı. Menü karta değil bu noktaya sabitlenir: kartın basma ölçeği
 * (0.96 → 1) parmak kalkınca geri yaylanırken menü yerinden oynamaz (S08 cihaz testi).
 */
@Stable
class TouchAnchor {
    internal var coords: LayoutCoordinates? = null
    internal var down = Offset.Zero
    var window by mutableStateOf(Offset.Zero)
        private set

    /** Uzun basma anında: son dokunuşu pencere koordinatına çevirip sabitler. */
    fun capture() {
        coords?.takeIf { it.isAttached }?.let { window = it.localToWindow(down) }
    }
}

@Composable
fun rememberTouchAnchor(): TouchAnchor = remember { TouchAnchor() }

/** Dokunuşları tüketmeden izler; kartın tıklama ve uzun basma davranışı aynen kalır. */
fun Modifier.trackTouch(anchor: TouchAnchor): Modifier = this
    .onGloballyPositioned { anchor.coords = it }
    .pointerInput(anchor) {
        awaitPointerEventScope {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) anchor.down = change.position
            }
        }
    }

private val SHADOW_PAD = 16.dp

/**
 * Menünün pencere konumu (saf; testli). Sağ yarıda basıldıysa görünen panel parmağın [gap] soluna, sol yarıda
 * sağına; dikeyde parmak hizasında ortalanır ve ekrana sığdırılır. [shadowPad] panelin dışındaki gölge payı.
 */
fun menuPosition(point: Offset, windowSize: IntSize, contentSize: IntSize, shadowPad: Int, gap: Int): IntOffset {
    val opensLeft = point.x > windowSize.width / 2f
    val x = if (opensLeft) point.x.toInt() - contentSize.width + shadowPad - gap else point.x.toInt() - shadowPad + gap
    val y = point.y.toInt() - contentSize.height / 2
    return IntOffset(
        x.coerceIn(0, (windowSize.width - contentSize.width).coerceAtLeast(0)),
        y.coerceIn(0, (windowSize.height - contentSize.height).coerceAtLeast(0)),
    )
}

/**
 * Basılı tutunca açılan bağlam menüsü (S08, Kaan): içerik kadar geniş cam panel. Ekranın sağ yarısında
 * basıldıysa parmağın soluna, sol yarısında sağına açılır; dikeyde parmak hizasında, ekrana sığacak şekilde.
 * Parmağın olduğu kenardan `bouncy` ile büyür, solarak kapanır; dışarı dokununca kapanır.
 */
@Composable
fun ContextMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, anchor: TouchAnchor) {
    val reduceMotion = LocalReduceMotion.current
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visible.targetState = expanded }
    if (!visible.currentState && !visible.targetState) return

    val point = anchor.window
    val density = LocalDensity.current
    val screenWidthPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val opensLeft = point.x > screenWidthPx / 2f
    val shadowPad = with(density) { SHADOW_PAD.roundToPx() }
    val gap = with(density) { 6.dp.roundToPx() }
    val provider = remember(point, opensLeft) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                menuPosition(point, windowSize, popupContentSize, shadowPad, gap)
        }
    }
    val origin = TransformOrigin(if (opensLeft) 1f else 0f, 0.5f)
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = visible,
            enter = scaleIn(motionSpec(SpringToken.Bouncy, reduceMotion), 0.85f, origin) + fadeIn(motionSpec(SpringToken.Snappy, reduceMotion)),
            exit = scaleOut(motionSpec(SpringToken.Snappy, reduceMotion), 0.9f, origin) + fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)),
        ) {
            val shape = RoundedCornerShape(20.dp)
            // Gölge statik (animasyonlu değil, S06 dersi); kırpılmasın diye dış boşluk.
            Column(
                Modifier
                    .padding(SHADOW_PAD)
                    .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.45f), offset = DpOffset(0.dp, 6.dp)))
                    // İçerik kadar geniş (S08 cihaz testi: "gereksiz çok uzun").
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 132.dp)
                    .clip(shape)
                    .background(Color(0xFF23242D))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                    .padding(vertical = 4.dp),
            ) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                    val color = if (item.destructive) WolColors.Danger else WolColors.TextPrimary
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(onClick = { onDismiss(); item.onClick() }, onClickLabel = item.label)
                            .testTag("menu_${item.label}")
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SymbolIcon(item.symbol, contentDescription = null, size = 20.dp, tint = color)
                        Spacer(Modifier.width(10.dp))
                        Text(item.label, style = WolType.BodyLarge, color = color)
                    }
                }
            }
        }
    }
}
