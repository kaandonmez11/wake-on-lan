package com.kdgames.wake_on_lan.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.JetBrainsMono
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Saf: yazılan metin geçerli bir değer mi. */
object StepperInput {
    fun parse(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }
}

/**
 * Spec 10.7 / 12.3: −/+ stepper + ortada sayısal klavyeli mono alan (odakta Buz halkası), birimi "dk".
 * Stepper'la değişimde haneler yuvarlanır; basılı tutunca hızlanır, sınırda durur. Aralık dışı/boş giriş ya da
 * [onCommit]'in reddettiği değer (ör. vazgeçme < aralık) → alan kırmızımsı yatay titrer (3 salınım, `bouncy`) ve
 * son geçerli değere döner.
 */
@Composable
fun NumberStepperField(
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Boolean,
    description: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    // Optimistik yerel değer: DataStore yazımı arkadan gelir, art arda adımlar eski değerden başlamaz.
    var current by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) { current = value }
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue(value.toString())) }
    val shake = remember { Animatable(0f) }
    val error = remember { Animatable(0f) }
    val commit by rememberUpdatedState(onCommit)

    fun reject() {
        haptics.error()
        scope.launch {
            launch {
                error.snapTo(1f)
                delay(500)
                error.animateTo(0f, motionSpec(SpringToken.Smooth, reduceMotion))
            }
            if (!reduceMotion) {
                for (amp in listOf(10f, 7f, 4f)) {
                    shake.animateTo(amp, SpringToken.Quick.spec())
                    shake.animateTo(-amp, SpringToken.Quick.spec())
                }
                shake.animateTo(0f, SpringToken.Bouncy.spec())
            }
        }
    }

    fun step(delta: Int): Boolean {
        val next = current + delta
        if (next !in range || !commit(next)) {
            reject()
            return false
        }
        haptics.tick()
        current = next
        text = TextFieldValue(next.toString())
        return true
    }

    fun commitText() {
        val parsed = StepperInput.parse(text.text, range)
        if (parsed != null && (parsed == current || commit(parsed))) current = parsed else reject()
        text = TextFieldValue(current.toString())
    }

    val ice = DevicePalette.Ice.start
    val ring by animateColorAsState(
        if (focused) ice else Color.White.copy(alpha = 0.10f),
        motionSpec(SpringToken.Snappy, reduceMotion),
        label = "stepperRing",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(up = false, enabled = current > range.first, description = "$description azalt", tag = "${tag}_minus") { step(-1) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .background(WolColors.Danger.copy(alpha = 0.28f * error.value))
                .border(1.5.dp, if (error.value > 0.01f) WolColors.Danger.copy(alpha = 0.4f + 0.6f * error.value) else ring, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val style = WolType.TechnicalLarge.copy(fontFamily = JetBrainsMono, fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp), color = WolColors.TextPrimary, textAlign = TextAlign.Center)
            BasicTextField(
                value = text,
                onValueChange = { v -> text = v.copy(text = v.text.filter(Char::isDigit).take(3)) },
                singleLine = true,
                textStyle = style.copy(color = if (focused) WolColors.TextPrimary else Color.Transparent),
                cursorBrush = SolidColor(ice),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .width(56.dp)
                    .focusRequester(requester)
                    .onFocusChanged { f ->
                        if (f.isFocused && !focused) text = text.copy(selection = TextRange(0, text.text.length))
                        if (!f.isFocused && focused) commitText()
                        focused = f.isFocused
                    }
                    .semantics { contentDescription = description }
                    .testTag(tag),
            )
            // Odak dışında haneler yuvarlanır (spec 12.3).
            if (!focused) RollingNumber(current, style)
        }
        Spacer(Modifier.width(6.dp))
        Text("dk", style = WolType.BodySmall, color = WolColors.TextTertiary)
        Spacer(Modifier.width(8.dp))
        StepButton(up = true, enabled = current < range.last, description = "$description artır", tag = "${tag}_plus") { step(1) }
    }
}

@Composable
private fun RollingNumber(value: Int, style: androidx.compose.ui.text.TextStyle) {
    val reduceMotion = LocalReduceMotion.current
    val previous = remember { intArrayOf(value) }
    val increasing = value >= previous[0]
    SideEffect { previous[0] = value }
    val text = value.toString()
    Row {
        // Sağdan hizalı haneler: basamak sayısı değişince yalnız yeni hane girer.
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (increasing) 1 else -1
                        if (reduceMotion) fadeIn() togetherWith fadeOut()
                        else (slideInVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { it * dir } + fadeIn(SpringToken.Snappy.spec())) togetherWith
                            (slideOutVertically(SpringToken.Snappy.spec(IntOffset.VisibilityThreshold)) { -it * dir } + fadeOut(SpringToken.Snappy.spec())) using
                            SizeTransform(clip = true)
                    },
                    label = "stepperDigit",
                ) { c -> Text(c.toString(), style = style) }
            }
        }
    }
}

/** Basılı tutunca hızlanan −/+ (400 ms sonra 90 ms'de bir); sınırda soluk ve durur. */
@Composable
private fun StepButton(up: Boolean, enabled: Boolean, description: String, tag: String, onStep: () -> Boolean) {
    val step by rememberUpdatedState(onStep)
    val canStep by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motionSpec(if (pressed) SpringToken.Quick else SpringToken.Snappy, reduceMotion), label = "stepScale")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, motionSpec(SpringToken.Snappy, reduceMotion), label = "stepAlpha")
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
                onClick { if (canStep) step(); true }
            }
            .testTag(tag)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    coroutineScope {
                        val job = launch {
                            if (!canStep || !step()) return@launch
                            delay(400)
                            while (canStep && step()) delay(90)
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (up) SymbolIcon(Sym.add, contentDescription = null, size = 20.dp)
        else Box(Modifier.size(width = 13.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(WolColors.TextPrimary))
    }
}
