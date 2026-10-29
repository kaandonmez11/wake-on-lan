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
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, motionSpec(SpringToken.Smooth, reduceMotion)) }

    PredictiveBackHandler(enabled = open) { events ->
        try {
            events.collect { e -> progress.snapTo(1f - e.progress * 0.9f) }
            onClose()
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
                    .drawBehind {
                        // Arka plan sabit kaldığı için panelin kaydığını belli eden ince cam tabaka + sol kenar gölgesi.
                        val p = progress.value
                        drawRect(Color(0xFF0C0D10).copy(alpha = 0.30f))
                        val edge = 18.dp.toPx()
                        val a = 0.35f * kotlin.math.sin(Math.PI.toFloat() * p.coerceIn(0f, 1f))
                        if (a > 0.005f) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = a)), startX = -edge, endX = 0f),
                                topLeft = androidx.compose.ui.geometry.Offset(-edge, 0f),
                                size = androidx.compose.ui.geometry.Size(edge, size.height),
                            )
                        }
                        drawRect(Color.White.copy(alpha = 0.10f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
                    },
            ) {
                top()
            }
        }
    }
}

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
