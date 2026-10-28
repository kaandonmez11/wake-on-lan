package com.kdgames.wake_on_lan.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.automation.AutomationScreen
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
import com.kdgames.wake_on_lan.ui.components.LocalHazeState
import com.kdgames.wake_on_lan.ui.devices.DevicesScreen
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val TabShift = 12.dp
private val TopCenter = TransformOrigin(0.5f, 0f)
private const val TAB_ENTER_SCALE = 0.98f

/** Uygulama kökü: canlı arka plan + sekmeler + yüzen nav (spec 10, 12.2). */
@Composable
fun AppNav(onAddDevice: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Devices) }
    val hazeState = rememberHazeState()
    val reduceMotion = LocalReduceMotion.current
    val shiftPx = with(LocalDensity.current) { TabShift.roundToPx() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            LivingBackground(
                scene = if (tab == MainTab.Devices) Scene.Devices else Scene.Automation,
                modifier = Modifier.hazeSource(hazeState, zIndex = 0f),
            )
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState, zIndex = 1f),
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(REDUCED_CROSSFADE_MS)) togetherWith fadeOut(tween(REDUCED_CROSSFADE_MS))
                    } else {
                        // Sağdaki sekmeye giderken içerik sağdan, soldakine giderken soldan girer.
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        val offsetSpec = SpringToken.Smooth.spec(IntOffset.VisibilityThreshold)
                        (fadeIn(SpringToken.Smooth.spec()) +
                            // Ölçek ekseni üst kenar: başlık dikeyde oynamaz (merkez eksen onu 0.98→1
                            // boyunca aşağıdan yukarı kaydırıyordu — S01 cihaz testi).
                            scaleIn(SpringToken.Smooth.spec(), initialScale = TAB_ENTER_SCALE, transformOrigin = TopCenter) +
                            slideInHorizontally(offsetSpec) { shiftPx * dir }) togetherWith
                            (fadeOut(SpringToken.Smooth.spec()) + slideOutHorizontally(offsetSpec) { -shiftPx * dir })
                    }
                },
                label = "tabContent",
            ) { target ->
                when (target) {
                    MainTab.Devices -> DevicesScreen()
                    MainTab.Automation -> AutomationScreen()
                }
            }
            FloatingNavBar(
                selected = tab,
                onSelect = { tab = it },
                onAdd = onAddDevice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
