package com.kdgames.wake_on_lan.ui.sheet

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Panelin hareket durumu: giriş (0..1) + parmakla sürükleme (px, aşağı pozitif).
 * Arkadaki ekranın bulanıklığı ve scrim aynı değerden okunur ([visibility]).
 */
@Stable
class SheetMotion {
    val enter = Animatable(0f)
    var dragPx by mutableFloatStateOf(0f)
    var heightPx by mutableFloatStateOf(1f)

    /** Yukarı çekişte direnç (iOS rubber-band, c = 0.55); aşağı serbest. */
    val shownDragPx: Float
        get() {
            val d = dragPx
            if (d >= 0f) return d
            val h = heightPx.coerceAtLeast(1f)
            return -(1f - 1f / (-d * 0.55f / h + 1f)) * h
        }

    /** 0..1: scrim ve arka plan blur'u. */
    val visibility: Float
        get() = (enter.value * (1f - shownDragPx.coerceAtLeast(0f) / heightPx.coerceAtLeast(1f))).coerceIn(0f, 1f)
}

@Composable
fun rememberSheetMotion(): SheetMotion = remember { SheetMotion() }

/** Panel açıkken arkadaki ekran 6'lık blur alır (spec 10.5); değer yalnız katman aşamasında okunur. */
fun Modifier.sheetBackdrop(motion: SheetMotion): Modifier = graphicsLayer {
    val v = motion.visibility
    renderEffect = if (v > 0.01f) {
        val r = 6.dp.toPx() * v
        BlurEffect(r, r, TileMode.Clamp)
    } else {
        null
    }
}

private const val SCRIM_ALPHA = 0.62f
private const val DISMISS_VELOCITY = 1_800f
private const val DISMISS_FRACTION = 0.33f

/**
 * Modal alt panel kabuğu (spec 10.5, 12.2): alttan `smooth` spring ile çıkar, %62 scrim + blur birlikte açılır;
 * tutamaçtan ya da en üstteyken gövdeden aşağı sürüklenir (yukarı rubber-band), bırakınca hıza ve mesafeye göre
 * kapanır ya da `snappy` ile geri oturur. Predictive back paneli parmakla aşağı iter.
 *
 * [content] tutamaç bölgesine verilecek sürükleme modifier'ını ve gövdeye bağlanacak nested-scroll bağlantısını alır.
 */
@Composable
fun SheetHost(
    open: Boolean,
    motion: SheetMotion,
    onDismiss: () -> Unit,
    content: @Composable BoxScope.(dragHandle: Modifier, bodyScroll: NestedScrollConnection) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(open) {
        if (open) {
            motion.dragPx = 0f
            motion.enter.animateTo(1f, motionSpec(SpringToken.Smooth, reduceMotion))
        } else {
            motion.enter.animateTo(0f, motionSpec(SpringToken.Smooth, reduceMotion))
            motion.dragPx = 0f
        }
    }

    suspend fun settle(velocity: Float) {
        val h = motion.heightPx
        val shouldClose = velocity > DISMISS_VELOCITY || (motion.dragPx > h * DISMISS_FRACTION && velocity > -DISMISS_VELOCITY / 3)
        if (shouldClose) {
            dismiss()
        } else {
            animate(motion.dragPx, 0f, initialVelocity = velocity, animationSpec = motionSpec(SpringToken.Snappy, reduceMotion)) { v, _ ->
                motion.dragPx = v
            }
        }
    }

    val dragState = rememberDraggableState { delta -> motion.dragPx += delta }
    val dragHandle = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        enabled = open,
        onDragStopped = { v -> settle(v) },
    )
    val bodyScroll = remember(motion) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Panel aşağıdayken yukarı kaydırma önce paneli yerine oturtur.
                if (available.y < 0f && motion.dragPx > 0f) {
                    val used = maxOf(available.y, -motion.dragPx)
                    motion.dragPx += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Gövde en üstteyken aşağı çekiş paneli sürükler (fling taşması değil, yalnız parmak).
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    motion.dragPx += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(motion.dragPx) > 0.5f) {
                    settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    PredictiveBackHandler(enabled = open) { events ->
        try {
            events.collect { e -> motion.dragPx = e.progress * motion.heightPx * 0.35f }
            dismiss()
        } catch (e: CancellationException) {
            scope.launch {
                animate(motion.dragPx, 0f, animationSpec = motionSpec(SpringToken.Snappy, reduceMotion)) { v, _ -> motion.dragPx = v }
            }
            throw e
        }
    }

    val shown by remember { derivedStateOf { motion.enter.value > 0.001f } }
    // Isınma: içerik uygulama açılışında görünmez olarak birkaç kare kurulur ve çizilir; ilk "+" basışında
    // sınıf yükleme, ilk kompozisyon ve font hazırlığı panelin giriş animasyonunu takıltmasın (S07 cihaz testi).
    var warmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(WARMUP_FRAMES) { withFrameNanos { } }
        warmed = true
    }
    if (!open && !shown) {
        if (!warmed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.001f }
                    .clearAndSetSemantics { },
            ) { content(Modifier, bodyScroll) }
        }
        return
    }

    val shape = RoundedCornerShape(topStart = WolDimens.CornerSheet, topEnd = WolDimens.CornerSheet)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * motion.visibility)) }
                .pointerInput(open) { if (open) detectTapGestures { dismiss() } },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .onSizeChanged { motion.heightPx = it.height.toFloat() }
                .graphicsLayer {
                    translationY = (1f - motion.enter.value) * motion.heightPx + motion.shownDragPx
                }
                .clip(shape)
                .background(SheetColors.Surface)
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endY = size.height * 0.35f))
                }
                .border(
                    1.dp,
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.08f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent),
                    shape,
                )
                // Panelin içindeki dokunuşlar scrim'e geçmesin.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            content(dragHandle, bodyScroll)
        }
    }
}

const val SHEET_HEIGHT_FRACTION = 0.84f
private const val WARMUP_FRAMES = 3

object SheetColors {
    val Surface = Color(0xFF15161D)
    val Field = Color.White.copy(alpha = 0.07f)
    val FieldBorder = Color.White.copy(alpha = 0.12f)
}
