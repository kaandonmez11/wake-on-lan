package com.kdgames.wake_on_lan.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import com.kdgames.wake_on_lan.ui.theme.LocalAnimationsActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import com.kdgames.wake_on_lan.ui.components.overscan
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.CancellationException

/** Geri hareketinde kapanma eşiği (sistem ilerlemesi 0..1). */
private const val CLOSE_THRESHOLD = 0.5f

/**
 * iOS tarzı push (spec 12.2): üst ekran sağdan girer, alttaki %30 paralaksla sola kayar ve kararır.
 * Predictive back: geri hareketi parmağı takip eder; bırakınca kapanır ya da geri oturur.
 */
@Composable
fun PushHost(
    open: Boolean,
    onClose: () -> Unit,
    base: @Composable () -> Unit,
    top: @Composable () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion)) }

    PredictiveBackHandler(enabled = open) { events ->
        try {
            var last = 0f
            events.collect { e ->
                last = e.progress
                progress.snapTo(1f - e.progress * 0.9f)
            }
            // iOS gibi (S09 cihaz testi, Kaan): yarıdan az çekip bırakınca ekran geri oturur, kapanmaz.
            if (last < CLOSE_THRESHOLD) progress.animateTo(1f, motionSpec(SpringToken.Snappy, reduceMotion))
            else onClose()
        } catch (e: CancellationException) {
            progress.animateTo(1f, motionSpec(SpringToken.Snappy, reduceMotion))
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
        val covered by remember { derivedStateOf { open && progress.value > 0.999f } }
        val topShown by remember { derivedStateOf { progress.value > 0.001f } }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // %30 paralaks + tamamen solma: arka plan sabit kaldığı için alttaki ekran arkada
                    // görünmemeli (S06 cihaz testi: "panel ekranın 3'te 1'ine kayıyor, arkada görünüyor").
                    val p = progress.value
                    translationX = -size.width * 0.30f * p
                    alpha = (1f - p).coerceIn(0f, 1f)
                },
        ) {
            // Üst ekran tamamen örtünce alttaki sonsuz animasyonlar kare üretmez.
            CompositionLocalProvider(LocalAnimationsActive provides !covered) { base() }
        }
        if (topShown || open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = size.width * (1f - progress.value) }
                    // Ekranı parmakla sağa çekerek kapatma (S10, Kaan): parmağı takip eder; %35'i geçince ya da
                    // hızlı fırlatınca kapanır, yoksa yaylanarak geri oturur. Yatay kayan içerik (çip satırı) önce alır.
                    .pointerInput(open) {
                        if (!open) return@pointerInput
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = {
                                val v = tracker.calculateVelocity().x / size.width.coerceAtLeast(1)
                                if (1f - progress.value > 0.35f || v > 1.0f) onClose()
                                else scope.launch { progress.animateTo(1f, motionSpec(SpringToken.Snappy, reduceMotion), initialVelocity = -v) }
                            },
                            onDragCancel = { scope.launch { progress.animateTo(1f, motionSpec(SpringToken.Snappy, reduceMotion)) } },
                        ) { change, dx ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            val next = (progress.value - dx / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            scope.launch { progress.snapTo(next) }
                        }
                    }
                    .drawBehind {
                        // Arka plan sabit kaldığı için panelin kaydığını belli eden ince cam tabaka + sol kenar gölgesi.
                        val p = progress.value
                        val edge = 18.dp.toPx()
                        val a = 0.35f * kotlin.math.sin(Math.PI.toFloat() * p.coerceIn(0f, 1f))
                        if (a > 0.005f) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = a)), startX = -edge, endX = 0f),
                                topLeft = androidx.compose.ui.geometry.Offset(-edge, 0f),
                                size = androidx.compose.ui.geometry.Size(edge, size.height),
                            )
                        }
                        // Kenar vurgusu yalnız kayarken: panel tam açıkken solda çizgi kalmasın (S06 cihaz testi).
                        val edgeLine = 0.10f * kotlin.math.sin(Math.PI.toFloat() * p.coerceIn(0f, 1f))
                        if (edgeLine > 0.003f) drawRect(Color.White.copy(alpha = edgeLine), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
                    },
            ) {
                // Üst/alt kenar bulanıklığı: arka plan (0) + bu ekranın içeriği (2); alttaki sekmeler (1) karışmaz.
                val haze = com.kdgames.wake_on_lan.ui.components.LocalHazeState.current
                // İnce cam karartma ayrı, ekran dışına taşan bir kaynak (1.5): kenar bulanıklığı her yerde aynı tonu görür.
                val dim = Modifier.drawBehind { drawRect(Color(0xFF0C0D10).copy(alpha = 0.30f)) }
                // Ekranın alt butonları (Başarısız: Tamam / Tekrar dene) bulanıklığın üstünde kalır: ekran onları
                // [LocalPushOverlay] ile buraya verir, yüzen nav gibi net çizilir (S10 cihaz testi, Kaan).
                val overlay = remember { PushOverlay() }
                CompositionLocalProvider(LocalPushOverlay provides overlay) {
                    if (haze != null) {
                        Box(Modifier.fillMaxSize().overscan(com.kdgames.wake_on_lan.ui.components.EdgeOverscan).hazeSource(haze, zIndex = 1.5f).then(dim))
                        Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 2f)) { top() }
                        com.kdgames.wake_on_lan.ui.components.EdgeBlur(haze, top = true, extra = 28.dp, modifier = Modifier.align(Alignment.TopCenter), include = { it != 1f })
                        com.kdgames.wake_on_lan.ui.components.EdgeBlur(
                            haze, top = false, extra = if (overlay.content != null) 96.dp else 36.dp,
                            modifier = Modifier.align(Alignment.BottomCenter), include = { it != 1f },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().then(dim)) { top() }
                    }
                    overlay.content?.invoke(this)
                }
            }
        }
    }
}

/** Push ekranının kenar bulanıklığından etkilenmeyecek alt içeriği (butonlar); [PushHost] bulanıklığın üstüne çizer. */
@androidx.compose.runtime.Stable
class PushOverlay {
    var content by androidx.compose.runtime.mutableStateOf<(@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)?>(null)
}

val LocalPushOverlay = androidx.compose.runtime.staticCompositionLocalOf<PushOverlay?> { null }

/** Ayarlar ekranı yer tutucusu (içerik S10'da). */
@Composable
fun SettingsPlaceholder(onBack: () -> Unit) {
    // Kendi arka planı yok: sabit duran canlı arka plan (AppNav) arkadan görünür.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = WolDimens.ScreenHorizontal, vertical = WolDimens.TopExtra)) {
            GlassSurface(shape = CircleShape, modifier = Modifier.size(44.dp).pressable(onClick = onBack, onClickLabel = "Geri")) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SymbolIcon(Sym.arrow_back_ios_new, contentDescription = "Geri", size = 18.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Ayarlar", style = WolType.PageTitle, color = WolColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text("Ayarlar bir sonraki sprintte (S10) burada olacak.", style = WolType.BodySmall, color = WolColors.TextTertiary)
        }
    }
}
