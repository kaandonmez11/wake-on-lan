package com.kdgames.wake_on_lan.ui.gates

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.net.NetPermissions
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
import com.kdgames.wake_on_lan.ui.components.AnimatedText
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.LocalHazeState
import com.kdgames.wake_on_lan.ui.components.breathing
import com.kdgames.wake_on_lan.ui.components.loopProgress
import com.kdgames.wake_on_lan.ui.components.pressable
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import com.kdgames.wake_on_lan.ui.theme.LocalAnimationsActive
import com.kdgames.wake_on_lan.ui.theme.LocalHaptics
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.theme.SymbolIcon
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolDimens
import com.kdgames.wake_on_lan.ui.theme.WolType
import com.kdgames.wake_on_lan.ui.theme.cssLinearGradient
import com.kdgames.wake_on_lan.ui.theme.motionSpec

/** Kapıların eylemleri. */
class GateActions(
    /** İzin sonucu / ekrana dönüş: izinleri yeniden oku. */
    val onRefresh: () -> Unit = {},
    val onOnboardingDone: () -> Unit = {},
    /** Konum izni verildi: Wi-Fi adı yeniden okunsun. */
    val onLocationGranted: () -> Unit = {},
)

/**
 * Tam ekran kapılar (spec 10.6, 12.2): her şeyin üstünde. Giriş 1.04 → 1 ölçek + fade; çıkış yukarı hafif kayıp
 * çözülme, alttaki ekran ortaya çıkar. Kendi sürekli canlı arka planı var (opak). Azaltılmış harekette kısa solma.
 */
@Composable
fun GateHost(ui: GateUi, actions: GateActions, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val visible = ui.gate != Gate.NONE
    // Çıkış animasyonu boyunca son kapı çizilir.
    var shown by remember { mutableStateOf(ui.gate) }
    if (visible) shown = ui.gate
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f, if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else SpringToken.Smooth.spec())
    }
    val present by remember { derivedStateOf { progress.value > 0.001f } }
    if (!visible && !present) return

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        actions.onRefresh()
        onPauseOrDispose { }
    }
    // Geri: alttaki ekranlar tepki vermesin; uygulama arka plana gider.
    val activity = LocalActivity.current
    BackHandler(enabled = visible) { activity?.moveTaskToBack(true) }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                if (!reduceMotion) {
                    if (visible) {
                        val s = 1.04f - 0.04f * p
                        scaleX = s
                        scaleY = s
                    } else {
                        translationY = -(1f - p) * 28.dp.toPx()
                    }
                }
            }
            // Alttaki ekrana dokunuş geçmez.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag("gate"),
    ) {
        // Kapıdaki camlar alttaki ekranı değil kendi arka planını görür.
        CompositionLocalProvider(LocalHazeState provides null, LocalAnimationsActive provides true) {
            LivingBackground(scene = Scene.FullScreen, continuous = true)
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(motionSpec(SpringToken.Smooth, reduceMotion)) togetherWith fadeOut(motionSpec(SpringToken.Snappy, reduceMotion)) },
                label = "gate",
            ) { gate ->
                when (gate) {
                    Gate.ONBOARDING -> OnboardingScreen(ui.permissions, actions)
                    Gate.NO_PERMISSION -> NoPermissionScreen(actions)
                    Gate.NO_WIFI -> NoWifiScreen(ui.mobile)
                    Gate.NONE -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Ortak düzen: halkalı ikon, başlık, açıklama, isteğe bağlı bilgi kartı, buton, alt not. */
@Composable
private fun GateLayout(
    symbol: String,
    accent: Color,
    title: String,
    text: String,
    button: String,
    note: String,
    tag: String,
    onClick: () -> Unit,
    info: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulseIcon(symbol, accent)
        Spacer(Modifier.height(20.dp))
        Text(title, style = WolType.ScreenTitle, color = WolColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text(text, style = WolType.Body, color = WolColors.TextSecondary, textAlign = TextAlign.Center)
        if (info != null) {
            Spacer(Modifier.height(22.dp))
            info()
        }
        Spacer(Modifier.height(26.dp))
        PrimaryButton(button, Modifier.testTag("${tag}_button")) { haptics.click(); onClick() }
        Spacer(Modifier.height(14.dp))
        Text(note, style = WolType.BodySmall, color = WolColors.TextTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = DevicePalette.Ice
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .drawBehind { drawRect(cssLinearGradient(135f, size, 0f to palette.start, 1f to palette.end)) }
            .pressable(onClick = onClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) { AnimatedText(label, style = WolType.BodyLarge, color = WolColors.OnGradient, maxLines = 1) }
}

/**
 * 124 cam daire + ikon + [accent] parlama; etrafında sürekli dışa yayılan 3 halka (spec 12.5: ölçek 0.6 → 1.6,
 * opaklık 0.5 → 0, 2.4 sn, 0.8 sn faz) ve dairenin hafif nefesi. Azaltılmış harekette halkalar durur.
 */
@Composable
private fun PulseIcon(symbol: String, accent: Color) {
    val reduceMotion = LocalReduceMotion.current
    val loop = loopProgress(RING_PERIOD_MS, rest = 0f, label = "gateRings")
    val breath = breathing(3_200, 0.97f, 1.03f, label = "gateBreath")
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val base = 62.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent), radius = base * 1.7f), radius = base * 1.7f)
                repeat(RING_COUNT) { i ->
                    // Azaltılmış harekette sabit, eş aralıklı soluk halkalar.
                    val t = if (reduceMotion) (i + 1f) / (RING_COUNT + 1) else (loop.value + i.toFloat() / RING_COUNT) % 1f
                    val scale = 0.6f + 1.0f * t
                    drawCircle(accent.copy(alpha = 0.5f * (1f - t)), radius = base * scale, style = Stroke(1.5.dp.toPx()))
                }
            },
        )
        GlassSurface(shape = CircleShape, accent = accent, modifier = Modifier.size(124.dp).graphicsLayer { scaleX = breath.value; scaleY = breath.value }) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SymbolIcon(symbol, contentDescription = null, size = 52.dp, tint = accent)
            }
        }
    }
}

private const val RING_COUNT = 3
private const val RING_PERIOD_MS = 2_400

/** Spec 10.6: Wi-Fi'a bağlı değilken. Wi-Fi gelince [GateHost] kendiliğinden kapatır. */
@Composable
internal fun NoWifiScreen(mobile: MobileInfo) {
    val context = LocalContext.current
    GateLayout(
        symbol = Sym.wifi_off,
        accent = WolColors.Amber,
        title = "Wi-Fi'a bağlı değilsin",
        text = "Bilgisayarlarına yerel ağ üzerinden ulaşıyorum. Mobil veriyle çalışmaz.",
        button = "Wi-Fi ayarlarını aç",
        note = "Wi-Fi'a bağlanınca bu ekran kendiliğinden kapanır",
        tag = "gate_wifi",
        onClick = {
            val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(panel) }.isFailure) {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
        info = {
            GlassSurface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolIcon(if (mobile.onCellular) Sym.smartphone else Sym.info, contentDescription = null, size = 20.dp, tint = WolColors.TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    AnimatedText(mobile.label, style = WolType.Body, color = WolColors.TextPrimary, maxLines = 1, modifier = Modifier.testTag("gate_mobile"))
                }
            }
        },
    )
}

/** Spec 10.6: yerel ağ izni yokken. İzin ister; kalıcı reddedilmişse uygulama ayarlarını açar. */
@Composable
internal fun NoPermissionScreen(actions: GateActions) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permanent by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Reddedildi ve sistem artık sormuyor ("bir daha sorma" ya da ikinci ret) → ayarlar.
        if (!granted) permanent = activity?.shouldShowRequestPermissionRationale(NetPermissions.LOCAL_NETWORK) != true
        actions.onRefresh()
    }
    GateLayout(
        symbol = Sym.router,
        accent = DevicePalette.Ice.start,
        title = "Yerel ağ izni gerekli",
        text = "PC'lerini bulmak, uyandırmak ve kapatmak için evindeki ağa erişmem gerekiyor. İnternete hiçbir şey gönderilmez.",
        button = if (permanent) "Uygulama ayarlarını aç" else "İzin ver",
        note = if (permanent) "İzinler › Yakındaki cihazlar › İzin ver. Dönünce bu ekran kendiliğinden kapanır" else "İzin verince bu ekran kendiliğinden kapanır",
        tag = "gate_permission",
        onClick = {
            if (permanent) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else {
                launcher.launch(NetPermissions.LOCAL_NETWORK)
            }
        },
    )
}

private data class OnboardingPage(val symbol: String, val accent: Color, val title: String, val text: String, val permissions: Array<String>, val skippable: Boolean)

private val PAGES = listOf(
    OnboardingPage(
        Sym.router, DevicePalette.Ice.start, "Yerel ağ erişimi",
        "PC'lerini bulmak, uyandırmak ve kapatmak için evindeki ağa erişmem gerekiyor. Her şey telefonla PC arasında kalır.",
        arrayOf(NetPermissions.LOCAL_NETWORK), skippable = false,
    ),
    OnboardingPage(
        Sym.location_on, WolColors.Success, "Wi-Fi adını görmek için konum",
        "Android, bağlı olduğun Wi-Fi'ın adını yalnız konum izniyle veriyor. \"Eve gelince\" zamanlayıcıları için de gerekli. Konumun hiçbir yere gönderilmez.",
        NetPermissions.LOCATION, skippable = true,
    ),
    OnboardingPage(
        Sym.notifications, WolColors.Amber, "Bildirimler",
        "Bir zamanlayıcı PC'ni açamazsa ya da kapatamazsa haber veririm. Başarılı çalışmalar için bildirim göndermem.",
        arrayOf(Manifest.permission.POST_NOTIFICATIONS), skippable = true,
    ),
)

/**
 * İlk açılış (spec 14): 3 sayfa, cam kartlar, canlı arka plan; sayfalar arası yatay `smooth` yay. Her sayfada izin
 * istenir; sonuç ne olursa olsun ilerlenir (yerel ağ reddedilirse sonra izin kapısı çıkar). Sonunda bayrak yazılır.
 */
@Composable
internal fun OnboardingScreen(snapshot: com.kdgames.wake_on_lan.ui.settings.PermissionSnapshot, actions: GateActions) {
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val position by animateFloatAsState(page.toFloat(), motionSpec(SpringToken.Smooth, reduceMotion), label = "onboardingPage")
    fun next() {
        if (page < PAGES.lastIndex) page++ else actions.onOnboardingDone()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (page == 1 && result.values.any { it }) actions.onLocationGranted()
        actions.onRefresh()
        next()
    }
    fun granted(p: OnboardingPage) = p.permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(vertical = 24.dp).testTag("gate_onboarding")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PAGES.forEachIndexed { i, p ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val d = i - position
                            translationX = d * size.width
                            alpha = (1f - kotlin.math.abs(d)).coerceIn(0f, 1f)
                        }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Görünmeyen sayfanın halkaları kare üretmez.
                    CompositionLocalProvider(LocalAnimationsActive provides (i == page)) { PageCard(p) }
                }
            }
        }
        Dots(page)
        Spacer(Modifier.height(18.dp))
        val current = PAGES[page]
        // snapshot okunur: izin dışarıdan verilince buton metni güncellenir.
        val has = remember(page, snapshot) { granted(current) }
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton(if (has) (if (page == PAGES.lastIndex) "Başla" else "Devam") else "İzin ver", Modifier.testTag("onboarding_primary")) {
                haptics.click()
                if (has) next() else launcher.launch(current.permissions)
            }
            Spacer(Modifier.height(6.dp))
            val skipAlpha by animateFloatAsState(if (current.skippable && !has) 1f else 0f, motionSpec(SpringToken.Snappy, reduceMotion), label = "skip")
            Box(
                Modifier
                    .height(44.dp)
                    .graphicsLayer { alpha = skipAlpha }
                    .clip(RoundedCornerShape(50))
                    .pressable(onClick = { haptics.tick(); next() }, enabled = current.skippable && !has, onClickLabel = "Şimdi değil")
                    .padding(horizontal = 18.dp)
                    .testTag("onboarding_skip"),
                contentAlignment = Alignment.Center,
            ) { Text("Şimdi değil", style = WolType.BodySmall, color = WolColors.TextSecondary) }
        }
    }
}

@Composable
private fun PageCard(p: OnboardingPage) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PulseIcon(p.symbol, p.accent)
        Spacer(Modifier.height(8.dp))
        GlassSurface(shape = RoundedCornerShape(WolDimens.CornerCard), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.title, style = WolType.SheetTitle, color = WolColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                Text(p.text, style = WolType.Body, color = WolColors.TextSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Sayfa göstergesi: aktif nokta `bouncy` ile uzar. */
@Composable
private fun Dots(page: Int) {
    val reduceMotion = LocalReduceMotion.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        PAGES.indices.forEach { i ->
            val w by animateDpAsState(if (i == page) 22.dp else 7.dp, motionSpec(SpringToken.Bouncy, reduceMotion), label = "dotW")
            val a by animateFloatAsState(if (i == page) 1f else 0.35f, motionSpec(SpringToken.Snappy, reduceMotion), label = "dotA")
            Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w).graphicsLayer { alpha = a }.clip(CircleShape).background(Color.White))
        }
    }
}
