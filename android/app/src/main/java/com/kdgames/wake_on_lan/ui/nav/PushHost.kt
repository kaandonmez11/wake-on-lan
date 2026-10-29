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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
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
        Box(
            Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * 0.30f * progress.value },
        ) {
            base()
            if (progress.value > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * progress.value)))
            }
        }
        if (progress.value > 0.001f || open) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * (1f - progress.value) }) {
                top()
            }
        }
    }
}

/** Ayarlar ekranı yer tutucusu (içerik S10'da). */
@Composable
fun SettingsPlaceholder(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(WolColors.Background)) {
        LivingBackground(scene = Scene.Automation)
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
