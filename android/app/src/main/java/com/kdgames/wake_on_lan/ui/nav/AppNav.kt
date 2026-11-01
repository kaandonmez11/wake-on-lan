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
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import com.kdgames.wake_on_lan.ui.theme.motionSpec
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
import com.kdgames.wake_on_lan.ui.components.EdgeBlur
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
    /** Otomasyon sekmesinde "+" (S08, Kaan): zamanlayıcı ekler. */
    onAddSchedule: () -> Unit = {},
    devicesTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    /** Her artışta Cihazlar sekmesine geçilir ve Ayarlar kapanır (panelden kayıt, spec 10.5). */
    showDevicesRequest: Int = 0,
    devices: @Composable (openSettings: () -> Unit) -> Unit = { DevicesPlaceholder() },
    automation: @Composable (openSettings: () -> Unit) -> Unit = { AutomationScreen(onOpenSettings = it) },
    /** Açık başarısız ekranı (spec 10.4; bildirimden ya da karttaki "Detay"dan). null = kapalı. */
    failureRunId: Long? = null,
    onCloseFailure: () -> Unit = {},
    failure: @Composable (runId: Long) -> Unit = {},
    /** Ayarlar ekranı (S10); parametresi geri dönüş. Varsayılan yer tutucu (testler). */
    settings: @Composable (onBack: () -> Unit) -> Unit = { SettingsPlaceholder(onBack = it) },
    /** Kenar bulanıklığının üstünde kalması gerekenler (ör. toast'lar). */
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Devices) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Kapanış animasyonu boyunca son içerik çizilmeye devam eder.
    var lastFailure by remember { mutableStateOf<Long?>(null) }
    if (failureRunId != null) lastFailure = failureRunId
    var failureOnTop by remember { mutableStateOf(false) }
    if (failureRunId != null && !failureOnTop) failureOnTop = true
    LaunchedEffect(settingsOpen) { if (settingsOpen && failureRunId == null) failureOnTop = false }
    val pushOpen = settingsOpen || failureRunId != null
    var tabDirection by remember { mutableIntStateOf(1) }
    val hazeState = rememberHazeState()
    val swipe = remember { TabSwipe() }
    swipe.current = tab
    swipe.devicesTint = devicesTint
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
                pushOpen -> Scene.FullScreen
                tab == MainTab.Devices -> Scene.Devices
                else -> Scene.Automation
            },
            continuous = pushOpen,
            modifier = Modifier.hazeSource(hazeState, zIndex = 0f),
            tint = if (!pushOpen && tab == MainTab.Devices) devicesTint else Color.Unspecified,
            // Parmakla sekme geçişinde sahne sürüklemeye bire bir bağlı (S10, Kaan).
            scrub = remember(swipe) {
                com.kdgames.wake_on_lan.ui.background.SceneScrub(
                    target = { swipe.scrubTarget()?.let { if (it == MainTab.Devices) Scene.Devices else Scene.Automation } },
                    progress = { kotlin.math.abs(swipe.offset.value) },
                    snapping = { swipe.committing },
                    targetTint = { if (swipe.scrubTarget() == MainTab.Devices) swipe.devicesTint else Color.Unspecified },
                )
            },
        )
      PushHost(
        open = pushOpen,
        onClose = { if (failureRunId != null) onCloseFailure() else settingsOpen = false },
        top = {
            val id = lastFailure
            if (failureOnTop && id != null) failure(id) else settings { settingsOpen = false }
        },
        base = {
        Box(Modifier.fillMaxSize()) {
            // İki sekme de canlı tutulur: Cihazlar'a dönüş her seferinde grid ve kartları sıfırdan kurmaz
            // (S06: Otomasyon → Cihazlar gecikiyordu). Gizli sekmede sonsuz döngüler durur, dokunuş ve
            // erişilebilirlik ona ulaşmaz.
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState, zIndex = 1f)
                    .tabSwipe(swipe) { target ->
                        tabDirection = if (target.ordinal > tab.ordinal) 1 else -1
                        tab = target
                    },
            ) {
                MainTab.entries.forEach { t ->
                    key(t) {
                        TabLayer(active = tab == t, direction = tabDirection, shiftPx = shiftPx, self = t, swipe = swipe) {
                            when (t) {
                                MainTab.Devices -> devices { settingsOpen = true }
                                MainTab.Automation -> automation { settingsOpen = true }
                            }
                        }
                    }
                }
            }
            // iOS gibi üst/alt kenar bulanıklığı (S10, Kaan): arka plan + sekme içeriği; yüzen nav üstte, etkilenmez.
            EdgeBlur(hazeState, top = true, extra = 28.dp, modifier = Modifier.align(Alignment.TopCenter), include = { it < 2f })
            EdgeBlur(hazeState, top = false, extra = 112.dp, modifier = Modifier.align(Alignment.BottomCenter), include = { it < 2f })
            overlay()
            FloatingNavBar(
                selected = tab,
                onSelect = {
                    if (it != tab) tabDirection = if (it.ordinal > tab.ordinal) 1 else -1
                    tab = it
                },
                onAdd = { if (tab == MainTab.Automation) onAddSchedule() else onAddDevice() },
                modifier = Modifier.align(Alignment.BottomCenter),
                position = { swipe.offset.value.takeIf { it != 0f }?.let { swipe.from.ordinal - it } },
                snapping = { swipe.committing },
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
private fun TabLayer(active: Boolean, direction: Int, shiftPx: Int, self: MainTab, swipe: TabSwipe, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        // Parmakla geçişte ekran zaten son hâlinde: animasyon tekrar oynatılmaz.
        if (swipe.committing) progress.snapTo(if (active) 1f else 0f)
        else progress.animateTo(if (active) 1f else 0f, if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else SpringToken.Smooth.spec())
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
                // Parmakla sürükleme (S09, Kaan): bırakılan sekme parmakla kayıp solar, komşusu yandan gelir.
                val f = swipe.offset.value
                if (f != 0f) {
                    val from = swipe.from
                    val parallax = size.width * SWIPE_PARALLAX
                    if (self == from) {
                        if (swipe.neighbor(from, f) != null) alpha = p * (1f - kotlin.math.abs(f))
                        translationX += f * parallax
                    } else if (self == swipe.neighbor(from, f)) {
                        val side = if (self.ordinal > from.ordinal) 1f else -1f
                        alpha = maxOf(p, kotlin.math.abs(f))
                        translationX = (f + side) * parallax
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
            }
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { }.blockTouches()),
    ) {
        CompositionLocalProvider(LocalAnimationsActive provides (active || shown)) { content() }
    }
}

private const val SWIPE_PARALLAX = 0.3f

/** Sekmeler arası parmakla geçiş durumu. [offset]: -1..1 genişlik oranı (negatif = parmak sola = sonraki sekme). */
@androidx.compose.runtime.Stable
private class TabSwipe {
    val offset = Animatable(0f)
    var from = MainTab.Devices
    var current = MainTab.Devices
    var committing = false

    var devicesTint = Color.Unspecified

    fun neighbor(from: MainTab, f: Float): MainTab? = MainTab.entries.getOrNull(from.ordinal + if (f < 0f) 1 else -1)

    /**
     * Sürüklenen komşu sekme. Bitişte sekme değiştikten sonra da (sürükleme sıfırlanana dek) aynı hedefi verir:
     * yeni sahnenin oturduğu karede eski görüntüye dönüş (flaş) olmaz (S10 cihaz testi).
     */
    fun scrubTarget(): MainTab? {
        val f = offset.value
        return if (f == 0f) null else neighbor(from, f)
    }
}

/**
 * Yatay sürükleme ile sekme değişimi: parmağı takip eder; genişliğin %30 kadarını geçince ya da hızlı fırlatınca
 * geçer (smooth), yoksa yaylanarak geri döner. Komşu yoksa kenarda hafif lastik etkisi.
 */
private fun Modifier.tabSwipe(swipe: TabSwipe, onCommit: (MainTab) -> Unit): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val commit by androidx.compose.runtime.rememberUpdatedState(onCommit)
    pointerInput(Unit) {
        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                if (!swipe.committing) {
                    swipe.from = swipe.current
                    scope.launch { swipe.offset.stop() }
                }
            },
            onDragCancel = { if (!swipe.committing) scope.launch { swipe.offset.animateTo(0f, motionSpec(SpringToken.Snappy, reduceMotion)) } },
            onDragEnd = {
                if (!swipe.committing) {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val v = tracker.calculateVelocity().x / w
                    val f = swipe.offset.value
                    val target = swipe.neighbor(swipe.from, f)
                    val fling = kotlin.math.abs(v) > 1.2f && kotlin.math.sign(v) == kotlin.math.sign(f)
                    if (target != null && f != 0f && (kotlin.math.abs(f) > 0.3f || fling)) {
                        swipe.committing = true
                        scope.launch {
                            swipe.offset.animateTo(if (f < 0f) -1f else 1f, motionSpec(SpringToken.Smooth, reduceMotion), initialVelocity = v)
                            commit(target)
                            // Yeni sekmenin katmanı son hâline oturana dek bekle, sonra sürüklemeyi sıfırla.
                            androidx.compose.runtime.withFrameNanos { }
                            androidx.compose.runtime.withFrameNanos { }
                            swipe.offset.snapTo(0f)
                            swipe.committing = false
                        }
                    } else {
                        scope.launch { swipe.offset.animateTo(0f, motionSpec(SpringToken.Snappy, reduceMotion), initialVelocity = v) }
                    }
                }
            },
        ) { change, dx ->
            if (!swipe.committing) {
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                val w = size.width.toFloat().coerceAtLeast(1f)
                val next = swipe.offset.value + dx / w
                val f = if (swipe.neighbor(swipe.from, next) == null) (swipe.offset.value + dx / w * 0.25f).coerceIn(-0.12f, 0.12f)
                else next.coerceIn(-1f, 1f)
                scope.launch { swipe.offset.snapTo(f) }
            }
        }
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
