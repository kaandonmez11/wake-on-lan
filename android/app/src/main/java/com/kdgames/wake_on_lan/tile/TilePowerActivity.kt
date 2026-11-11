package com.kdgames.wake_on_lan.tile

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.ui.background.LivingBackground
import com.kdgames.wake_on_lan.ui.background.Scene
import com.kdgames.wake_on_lan.ui.components.GlassSurface
import com.kdgames.wake_on_lan.ui.components.LocalHazeState
import com.kdgames.wake_on_lan.ui.theme.LocalReduceMotion
import com.kdgames.wake_on_lan.ui.theme.REDUCED_CROSSFADE_MS
import com.kdgames.wake_on_lan.ui.theme.SpringToken
import com.kdgames.wake_on_lan.ui.theme.WolColors
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import com.kdgames.wake_on_lan.ui.theme.motionSpec
import kotlinx.coroutines.flow.map

/** Cihazın canlı durumu + komut; activity ve dialog ortak kullanır. */
@Composable
private fun PowerPanel(context: Context, deviceId: Long, compact: Boolean, orbScale: () -> Float, onDone: () -> Unit) {
    val deps = remember { context.tileDeps() }
    val device by remember { deps.devices().observeDevice(deviceId) }.collectAsState(initial = deps.engine().device(deviceId))
    val state by remember { deps.engine().states.map { it[deviceId] } }.collectAsState(initial = deps.engine().state(deviceId))
    TilePowerContent(
        device = device,
        state = state,
        onAction = { action: PowerAction ->
            TilePower.execute(context, deviceId, action)
            onDone()
        },
        onCancel = onDone,
        compact = compact,
        orbScale = orbScale,
    )
}

/**
 * Mod A (spec 10.8, 12.2): şeffaf, arkası bulanık tam ekran güç paneli. Giriş: arka bulanıklığı 0 → hedef +
 * orb 0.8 → 1 (`bouncy`); kapanışta tersi. Arka plan sürekli canlı (yarı saydam). Komut sonrası kapanır.
 */
class TilePowerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val deviceId = intent.getLongExtra(EXTRA_DEVICE, -1)
        if (deviceId < 0) {
            finish()
            return
        }
        // Arkadaki pencerenin bulanıklığı (destekleyen cihazlarda); ilerlemeyle birlikte artar.
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        setBlur(0f)
        setContent {
            WolTheme {
                val reduceMotion = LocalReduceMotion.current
                val progress = remember { Animatable(0f) }
                var closing by remember { mutableStateOf(false) }
                LaunchedEffect(closing) {
                    if (!closing) {
                        progress.animateTo(1f, if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else SpringToken.Bouncy.spec())
                    } else {
                        progress.animateTo(0f, if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else SpringToken.Snappy.spec())
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }
                LaunchedEffect(Unit) { snapshotFlow { progress.value.coerceIn(0f, 1f) }.collect { setBlur(it) } }
                BackHandler { closing = true }
                Box(
                    Modifier
                        .fillMaxSize()
                        // Boşluğa dokunmak = Vazgeç.
                        .pointerInput(Unit) { detectTapGestures { closing = true } },
                ) {
                    CompositionLocalProvider(LocalHazeState provides null) {
                        LivingBackground(
                            scene = Scene.FullScreen,
                            continuous = true,
                            modifier = Modifier.graphicsLayer { alpha = 0.72f * progress.value.coerceIn(0f, 1f) },
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp)
                                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                            contentAlignment = Alignment.Center,
                        ) {
                            PowerPanel(this@TilePowerActivity, deviceId, compact = false, orbScale = { 0.8f + 0.2f * progress.value }) { closing = true }
                        }
                    }
                }
            }
        }
    }

    private fun setBlur(progress: Float) {
        runCatching {
            val lp = window.attributes
            lp.blurBehindRadius = (progress * BLUR_DP * resources.displayMetrics.density).toInt()
            window.attributes = lp
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "deviceId"
        private const val BLUR_DP = 28f

        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, TilePowerActivity::class.java).putExtra(EXTRA_DEVICE, deviceId)
    }
}

/** Mod B (spec 10.8): `TileService.showDialog` ile panelin üstünde küçük cam dialog; aynı hold butonları. */
internal object TilePowerDialog {
    fun create(context: Context, deviceId: Long, onDismiss: () -> Unit): Dialog {
        val dialog = Dialog(context, com.kdgames.wake_on_lan.R.style.Theme_Wol_Dialog)
        val owner = DialogOwner()
        val view = ComposeView(context).apply {
            setContent {
                WolTheme {
                    CompositionLocalProvider(LocalHazeState provides null) {
                        GlassSurface(shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth().drawBehind { drawRect(WolColors.Background.copy(alpha = 0.88f)) }) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp)) {
                                PowerPanel(context, deviceId, compact = true, orbScale = { 1f }) { dialog.dismiss() }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding((16 * context.resources.displayMetrics.density).toInt(), 0, (16 * context.resources.displayMetrics.density).toInt(), 0)
            // Compose, pencerenin kök görünümünde yaşam döngüsü sahiplerini arar (Service'te activity yok).
            decorView.setViewTreeLifecycleOwner(owner)
            decorView.setViewTreeSavedStateRegistryOwner(owner)
            decorView.setViewTreeViewModelStoreOwner(owner)
        }
        dialog.setOnShowListener { owner.resume() }
        dialog.setOnDismissListener {
            owner.destroy()
            onDismiss()
        }
        return dialog
    }

    /** Dialog penceresi için asgari yaşam döngüsü / saved-state / ViewModelStore sahibi. */
    private class DialogOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        init {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
