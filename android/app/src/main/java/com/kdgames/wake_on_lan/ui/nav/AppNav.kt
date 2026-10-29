package com.kdgames.wake_on_lan.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.kdgames.wake_on_lan.ui.theme.LocalAnimationsActive
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.ui.automation.AutomationScreen
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
import com.kdgames.wake_on_lan.ui.components.LocalHazeState
import com.kdgames.wake_on_lan.ui.components.TabScaffold
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val TabShift = 12.dp
private val TopCenter = TransformOrigin(0.5f, 0f)
private const val TAB_ENTER_SCALE = 0.98f

/**
 * Uygulama kökü: canlı arka plan + sekmeler + yüzen nav (spec 10, 12.2).
 *
 * @param devices Cihazlar sekmesi içeriği; parametresi Ayarlar'ı açar. Varsayılan boş iskelet (testler).
 * @param devicesTint Cihazlar sahnesinde arka plan tonu (seçili cihaz / uyanma amber'i / kapanma kırmızısı).
 */
@Composable
fun AppNav(
    onAddDevice: () -> Unit = {},
    devicesTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    /** Her artışta Cihazlar sekmesine geçilir ve Ayarlar kapanır (panelden kayıt, spec 10.5). */
    showDevicesRequest: Int = 0,
    devices: @Composable (openSettings: () -> Unit) -> Unit = { DevicesPlaceholder() },
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Devices) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tabDirection by remember { mutableIntStateOf(1) }
    val hazeState = rememberHazeState()
    val reduceMotion = LocalReduceMotion.current
    val shiftPx = with(LocalDensity.current) { TabShift.roundToPx() }
    LaunchedEffect(showDevicesRequest) {
        if (showDevicesRequest > 0 && tab != MainTab.Devices) {
            tabDirection = -1
            tab = MainTab.Devices
        }
        if (showDevicesRequest > 0) settingsOpen = false
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
      Box(modifier.fillMaxSize()) {
        // Arka plan push'un dışında: Ayarlar açılınca yandan kaymaz, yerinde tam ekran sahnesine
        // akarak dönüşür ve sürekli canlı moda geçer (S06 cihaz testi, spec 12.4).
        LivingBackground(
            scene = when {
                settingsOpen -> Scene.FullScreen
                tab == MainTab.Devices -> Scene.Devices
                else -> Scene.Automation
            },
            continuous = settingsOpen,
            modifier = Modifier.hazeSource(hazeState, zIndex = 0f),
            tint = if (!settingsOpen && tab == MainTab.Devices) devicesTint else Color.Unspecified,
        )
      PushHost(
        open = settingsOpen,
        onClose = { settingsOpen = false },
        top = { SettingsPlaceholder(onBack = { settingsOpen = false }) },
        base = {
        Box(Modifier.fillMaxSize()) {
            // İki sekme de canlı tutulur: Cihazlar'a dönüş her seferinde grid ve kartları sıfırdan kurmaz
            // (S06: Otomasyon → Cihazlar gecikiyordu). Gizli sekmede sonsuz döngüler durur, dokunuş ve
            // erişilebilirlik ona ulaşmaz.
            Box(Modifier.fillMaxSize().hazeSource(hazeState, zIndex = 1f)) {
                MainTab.entries.forEach { t ->
                    key(t) {
                        TabLayer(active = tab == t, direction = tabDirection, shiftPx = shiftPx) {
                            when (t) {
                                MainTab.Devices -> devices { settingsOpen = true }
                                MainTab.Automation -> AutomationScreen()
                            }
                        }
                    }
                }
            }
            FloatingNavBar(
                selected = tab,
                onSelect = {
                    if (it != tab) tabDirection = if (it.ordinal > tab.ordinal) 1 else -1
                    tab = it
                },
                onAdd = onAddDevice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        },
      )
      }
    }
}

/**
 * Sekme katmanı (spec 12.2): girerken fade + 0.98→1 ölçek (üst kenar ekseni) + yöne göre 12'lik kayma,
 * çıkarken fade + ters yöne kayma; hepsi `smooth` spring. Değerler yalnız katman aşamasında okunur.
 */
@Composable
private fun TabLayer(active: Boolean, direction: Int, shiftPx: Int, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        progress.animateTo(if (active) 1f else 0f, if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else SpringToken.Smooth.spec())
    }
    val shown by remember { derivedStateOf { progress.value > 0.001f } }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                if (!reduceMotion) {
                    translationX = (1f - p) * shiftPx * direction * (if (active) 1f else -1f)
                    val s = if (active) TAB_ENTER_SCALE + (1f - TAB_ENTER_SCALE) * p else 1f
                    scaleX = s
                    scaleY = s
                    transformOrigin = TopCenter
                }
            }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { }.blockTouches()),
    ) {
        CompositionLocalProvider(LocalAnimationsActive provides (active || shown)) { content() }
    }
}

/** Gizli sekme dokunuşları yutar; alttaki/üstteki ekran yanlışlıkla tetiklenmez. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** Testler ve önizleme için Cihazlar iskeleti. */
@Composable
private fun DevicesPlaceholder() = TabScaffold(title = "Cihazlar")
